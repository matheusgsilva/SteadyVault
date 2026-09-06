from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'bloco nao encontrado em {path}: {old[:160]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# ---------------------------------------------------------------------------
# Modo novo: interpolacao compensada por movimento.
# ---------------------------------------------------------------------------
models = 'app/src/main/java/com/steadyvault/camera/processing/model/OptimizationModels.kt'
replace_once(
    models,
    '''enum class FrameRepairMode {\n    NONE,\n    SMOOTH_TIMELINE,\n    FILL_MISSING_FRAMES,\n    ADAPTIVE_BLEND;''',
    '''enum class FrameRepairMode {\n    NONE,\n    SMOOTH_TIMELINE,\n    FILL_MISSING_FRAMES,\n    ADAPTIVE_BLEND,\n    MOTION_COMPENSATED;'''
)

# ---------------------------------------------------------------------------
# Preferencia automatica: migra quem estava no blend simples para movimento.
# ---------------------------------------------------------------------------
settings = 'app/src/main/java/com/steadyvault/camera/processing/auto/AutoGapRepairSettings.kt'
replace_once(
    settings,
    '''    private const val KEY_AI_ASSISTED = "ai_assisted"\n\n    fun snapshot(context: Context): Snapshot {\n        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n        return Snapshot(\n            enabled = prefs.getBoolean(KEY_ENABLED, false),\n            mode = FrameRepairMode.from(\n                prefs.getString(KEY_MODE, FrameRepairMode.ADAPTIVE_BLEND.name)\n            ).takeIf {\n                it == FrameRepairMode.ADAPTIVE_BLEND ||\n                    it == FrameRepairMode.FILL_MISSING_FRAMES ||\n                    it == FrameRepairMode.SMOOTH_TIMELINE\n            } ?: FrameRepairMode.ADAPTIVE_BLEND,\n            maxInterpolatedFramesPerGap = prefs.getInt(KEY_MAX_FRAMES, 4).coerceIn(1, 16),\n            aiAssisted = prefs.getBoolean(KEY_AI_ASSISTED, false)\n        )\n    }''',
    '''    private const val KEY_AI_ASSISTED = "ai_assisted"\n    private const val KEY_SCHEMA = "schema"\n    private const val SCHEMA = 2\n\n    fun snapshot(context: Context): Snapshot {\n        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n        val stored = FrameRepairMode.from(\n            prefs.getString(KEY_MODE, FrameRepairMode.MOTION_COMPENSATED.name)\n        )\n        val migrated = if (prefs.getInt(KEY_SCHEMA, 0) < SCHEMA && stored == FrameRepairMode.ADAPTIVE_BLEND) {\n            FrameRepairMode.MOTION_COMPENSATED\n        } else stored\n        val safeMode = migrated.takeIf {\n            it == FrameRepairMode.MOTION_COMPENSATED ||\n                it == FrameRepairMode.ADAPTIVE_BLEND ||\n                it == FrameRepairMode.FILL_MISSING_FRAMES ||\n                it == FrameRepairMode.SMOOTH_TIMELINE\n        } ?: FrameRepairMode.MOTION_COMPENSATED\n        if (prefs.getInt(KEY_SCHEMA, 0) < SCHEMA || safeMode != stored) {\n            prefs.edit()\n                .putInt(KEY_SCHEMA, SCHEMA)\n                .putString(KEY_MODE, safeMode.name)\n                .apply()\n        }\n        return Snapshot(\n            enabled = prefs.getBoolean(KEY_ENABLED, false),\n            mode = safeMode,\n            maxInterpolatedFramesPerGap = prefs.getInt(KEY_MAX_FRAMES, 4).coerceIn(1, 16),\n            aiAssisted = prefs.getBoolean(KEY_AI_ASSISTED, false)\n        )\n    }'''
)
replace_once(
    settings,
    '''        val safe = mode.takeIf {\n            it == FrameRepairMode.ADAPTIVE_BLEND ||\n                it == FrameRepairMode.FILL_MISSING_FRAMES ||\n                it == FrameRepairMode.SMOOTH_TIMELINE\n        } ?: FrameRepairMode.ADAPTIVE_BLEND''',
    '''        val safe = mode.takeIf {\n            it == FrameRepairMode.MOTION_COMPENSATED ||\n                it == FrameRepairMode.ADAPTIVE_BLEND ||\n                it == FrameRepairMode.FILL_MISSING_FRAMES ||\n                it == FrameRepairMode.SMOOTH_TIMELINE\n        } ?: FrameRepairMode.MOTION_COMPENSATED'''
)

# ---------------------------------------------------------------------------
# Otimizador: reparo por movimento e analise avancada sem alterar cor/filtros
# no preset REPAIR_ONLY.
# ---------------------------------------------------------------------------
optimizer = 'app/src/main/java/com/steadyvault/camera/processing/engine/VideoOptimizer.kt'
replace_once(
    optimizer,
    '''            aiReport != null -> normalizedConfig.copy(\n                filters = normalizedConfig.filters.copy(''',
    '''            aiReport != null && normalizedConfig.preset != OptimizationPreset.REPAIR_ONLY -> normalizedConfig.copy(\n                filters = normalizedConfig.filters.copy('''
)
replace_once(
    optimizer,
    '''            ).normalized()\n            else -> normalizedConfig\n        }''',
    '''            ).normalized()\n            aiReport != null -> normalizedConfig\n            else -> normalizedConfig\n        }'''
)
replace_once(
    optimizer,
    '''        val canResample = config.frameRepair == FrameRepairMode.FILL_MISSING_FRAMES ||\n            config.frameRepair == FrameRepairMode.ADAPTIVE_BLEND''',
    '''        val canResample = config.frameRepair == FrameRepairMode.FILL_MISSING_FRAMES ||\n            config.frameRepair == FrameRepairMode.ADAPTIVE_BLEND ||\n            config.frameRepair == FrameRepairMode.MOTION_COMPENSATED'''
)
replace_once(
    optimizer,
    '''            throw IllegalArgumentException("Para converter o FPS real, use quadro próximo ou mistura temporal adaptativa")''',
    '''            throw IllegalArgumentException("Para converter o FPS real, use um modo de reparo com interpolação")'''
)
replace_once(
    optimizer,
    '''                maxInterpolatedFramesPerGap = config.maxInterpolatedFramesPerGap,\n                trimStartUs = config.trimStartUs(analysis.durationUs),''',
    '''                maxInterpolatedFramesPerGap = config.maxInterpolatedFramesPerGap,\n                highQualityMotion = config.aiAssisted,\n                trimStartUs = config.trimStartUs(analysis.durationUs),'''
)

# ---------------------------------------------------------------------------
# Transcoder: campo de movimento em baixa resolucao + warp bidirecional.
# ---------------------------------------------------------------------------
transcoder = 'app/src/main/java/com/steadyvault/camera/processing/transcode/HardwareVideoTranscoder.kt'
replace_once(
    transcoder,
    '''        val filters: VideoFilterConfig,\n        val maxInterpolatedFramesPerGap: Int,\n        val trimStartUs: Long = 0L,''',
    '''        val filters: VideoFilterConfig,\n        val maxInterpolatedFramesPerGap: Int,\n        val highQualityMotion: Boolean = false,\n        val trimStartUs: Long = 0L,'''
)
replace_once(
    transcoder,
    '''            outputSurface = DecoderOutputSurface(request.width, request.height)''',
    '''            outputSurface = DecoderOutputSurface(request.width, request.height, request.highQualityMotion)'''
)
replace_once(
    transcoder,
    '''            fun writeBlendedFrame(ptsUs: Long, alpha: Float) {\n                submitFrame(ptsUs) { outputSurface!!.drawBlend(alpha) }\n                if (alpha > 0.02f && alpha < 0.98f) blendedFrames++\n            }''',
    '''            fun writeBlendedFrame(ptsUs: Long, alpha: Float) {\n                submitFrame(ptsUs) { outputSurface!!.drawBlend(alpha) }\n                if (alpha > 0.02f && alpha < 0.98f) blendedFrames++\n            }\n            fun writeMotionFrame(ptsUs: Long, alpha: Float) {\n                submitFrame(ptsUs) { outputSurface!!.drawMotion(alpha) }\n                if (alpha > 0.02f && alpha < 0.98f) blendedFrames++\n            }'''
)
replace_once(
    transcoder,
    '''                                    FrameRepairMode.FILL_MISSING_FRAMES,\n                                    FrameRepairMode.ADAPTIVE_BLEND -> {''',
    '''                                    FrameRepairMode.FILL_MISSING_FRAMES,\n                                    FrameRepairMode.ADAPTIVE_BLEND,\n                                    FrameRepairMode.MOTION_COMPENSATED -> {'''
)
replace_once(
    transcoder,
    '''                                            val blendAllowed = request.frameRepair == FrameRepairMode.ADAPTIVE_BLEND &&\n                                                    outputFramesInGap <= request.maxInterpolatedFramesPerGap + 1\n                                            while (nextFillPtsUs < sourceRelative) {''',
    '''                                            val motionAllowed = request.frameRepair == FrameRepairMode.MOTION_COMPENSATED &&\n                                                    outputFramesInGap <= request.maxInterpolatedFramesPerGap + 1\n                                            val blendAllowed = request.frameRepair == FrameRepairMode.ADAPTIVE_BLEND &&\n                                                    outputFramesInGap <= request.maxInterpolatedFramesPerGap + 1\n                                            while (nextFillPtsUs < sourceRelative) {'''
)
replace_once(
    transcoder,
    '''                                                when {\n                                                    blendAllowed -> writeBlendedFrame(nextFillPtsUs, alpha)\n                                                    alpha < 0.5f -> writePreviousFrame(nextFillPtsUs)''',
    '''                                                when {\n                                                    motionAllowed -> writeMotionFrame(nextFillPtsUs, alpha)\n                                                    blendAllowed -> writeBlendedFrame(nextFillPtsUs, alpha)\n                                                    alpha < 0.5f -> writePreviousFrame(nextFillPtsUs)'''
)
replace_once(
    transcoder,
    '''                                    FrameRepairMode.ADAPTIVE_BLEND -> "Reconstruindo cadência com mistura temporal por GPU"\n                                    FrameRepairMode.FILL_MISSING_FRAMES -> "Preenchendo lacunas com o quadro mais próximo"''',
    '''                                    FrameRepairMode.MOTION_COMPENSATED -> "Reconstruindo movimento e cadência por GPU"\n                                    FrameRepairMode.ADAPTIVE_BLEND -> "Reconstruindo cadência com mistura temporal por GPU"\n                                    FrameRepairMode.FILL_MISSING_FRAMES -> "Preenchendo lacunas com o quadro mais próximo"'''
)
replace_once(
    transcoder,
    '''                    if ((request.frameRepair == FrameRepairMode.FILL_MISSING_FRAMES ||\n                                request.frameRepair == FrameRepairMode.ADAPTIVE_BLEND) && loadedFrame''',
    '''                    if ((request.frameRepair == FrameRepairMode.FILL_MISSING_FRAMES ||\n                                request.frameRepair == FrameRepairMode.ADAPTIVE_BLEND ||\n                                request.frameRepair == FrameRepairMode.MOTION_COMPENSATED) && loadedFrame'''
)
replace_once(
    transcoder,
    '''    private class DecoderOutputSurface(private val width: Int, private val height: Int) : SurfaceTexture.OnFrameAvailableListener {''',
    '''    private class DecoderOutputSurface(\n        private val width: Int,\n        private val height: Int,\n        highQualityMotion: Boolean\n    ) : SurfaceTexture.OnFrameAvailableListener {'''
)
replace_once(
    transcoder,
    '''        private val renderer = TextureRenderer(width, height)''',
    '''        private val renderer = TextureRenderer(width, height, highQualityMotion)'''
)
replace_once(
    transcoder,
    '''        fun drawPrevious() = renderer.drawPrevious()\n        fun drawBlend(alpha: Float) = renderer.drawBlend(alpha)''',
    '''        fun drawPrevious() = renderer.drawPrevious()\n        fun drawBlend(alpha: Float) = renderer.drawBlend(alpha)\n        fun drawMotion(alpha: Float) = renderer.drawMotion(alpha)'''
)
replace_once(
    transcoder,
    '''    private class TextureRenderer(private val width: Int, private val height: Int) {''',
    '''    private class TextureRenderer(\n        private val width: Int,\n        private val height: Int,\n        highQualityMotion: Boolean\n    ) {'''
)
replace_once(
    transcoder,
    '''        private var externalProgram = 0\n        private var blendProgram = 0\n        private val frameTextures = IntArray(2)''',
    '''        private var externalProgram = 0\n        private var blendProgram = 0\n        private var motionFieldProgram = 0\n        private var motionInterpolateProgram = 0\n        private val frameTextures = IntArray(2)'''
)
replace_once(
    transcoder,
    '''        private val framebuffers = IntArray(2)\n        private var currentIndex = -1''',
    '''        private val framebuffers = IntArray(2)\n        private val motionTexture = IntArray(1)\n        private val motionFramebuffer = IntArray(1)\n        private val motionWidth = if (highQualityMotion) (width / 12).coerceIn(128, 320) else (width / 16).coerceIn(96, 240)\n        private val motionHeight = ((motionWidth.toLong() * height.toLong()) / width.coerceAtLeast(1).toLong()).toInt().coerceIn(54, 180)\n        private var motionFieldDirty = true\n        private var currentIndex = -1'''
)
replace_once(
    transcoder,
    '''            externalProgram = createProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)\n            blendProgram = createProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)''',
    '''            externalProgram = createProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)\n            blendProgram = createProgram(VERTEX_SHADER, BLEND_FRAGMENT_SHADER)\n            motionFieldProgram = createProgram(VERTEX_SHADER, MOTION_FIELD_FRAGMENT_SHADER)\n            motionInterpolateProgram = createProgram(VERTEX_SHADER, MOTION_INTERPOLATE_FRAGMENT_SHADER)'''
)
replace_once(
    transcoder,
    '''            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)\n        }''',
    '''            GLES20.glGenTextures(1, motionTexture, 0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexture[0])\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)\n            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)\n            GLES20.glTexImage2D(\n                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, motionWidth, motionHeight, 0,\n                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null\n            )\n            GLES20.glGenFramebuffers(1, motionFramebuffer, 0)\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFramebuffer[0])\n            GLES20.glFramebufferTexture2D(\n                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, motionTexture[0], 0\n            )\n            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {\n                "Framebuffer do campo de movimento incompleto"\n            }\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)\n        }'''
)
replace_once(
    transcoder,
    '''            if (previousIndex < 0) previousIndex = currentIndex\n            checkGl("capturar quadro")''',
    '''            if (previousIndex < 0) previousIndex = currentIndex\n            motionFieldDirty = true\n            checkGl("capturar quadro")'''
)
replace_once(
    transcoder,
    '''        fun drawCurrent() = drawTextures(currentIndex, currentIndex, 1f)\n        fun drawPrevious() = drawTextures(previousIndex, previousIndex, 1f)\n        fun drawBlend(alpha: Float) = drawTextures(previousIndex, currentIndex, alpha.coerceIn(0f, 1f))\n\n        private fun drawTextures''',
    '''        fun drawCurrent() = drawTextures(currentIndex, currentIndex, 1f)\n        fun drawPrevious() = drawTextures(previousIndex, previousIndex, 1f)\n        fun drawBlend(alpha: Float) = drawTextures(previousIndex, currentIndex, alpha.coerceIn(0f, 1f))\n\n        fun drawMotion(alpha: Float) {\n            check(previousIndex >= 0 && currentIndex >= 0) { "Quadros insuficientes para interpolação de movimento" }\n            ensureMotionField()\n            val safeAlpha = alpha.coerceIn(0f, 1f)\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)\n            GLES20.glViewport(0, 0, width, height)\n            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)\n            GLES20.glUseProgram(motionInterpolateProgram)\n            bindGeometry(motionInterpolateProgram, identity)\n            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[previousIndex])\n            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uPrevious"), 0)\n            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[currentIndex])\n            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uCurrent"), 1)\n            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTexture[0])\n            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionInterpolateProgram, "uMotion"), 2)\n            GLES20.glUniform1f(GLES20.glGetUniformLocation(motionInterpolateProgram, "uAlpha"), safeAlpha)\n            GLES20.glUniform2f(\n                GLES20.glGetUniformLocation(motionInterpolateProgram, "uSearchStep"),\n                MOTION_SEARCH_STEP_PIXELS / width.toFloat(),\n                MOTION_SEARCH_STEP_PIXELS / height.toFloat()\n            )\n            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)\n            checkGl("interpolar movimento")\n        }\n\n        private fun ensureMotionField() {\n            if (!motionFieldDirty) return\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFramebuffer[0])\n            GLES20.glViewport(0, 0, motionWidth, motionHeight)\n            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)\n            GLES20.glUseProgram(motionFieldProgram)\n            bindGeometry(motionFieldProgram, identity)\n            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[previousIndex])\n            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionFieldProgram, "uPrevious"), 0)\n            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)\n            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[currentIndex])\n            GLES20.glUniform1i(GLES20.glGetUniformLocation(motionFieldProgram, "uCurrent"), 1)\n            GLES20.glUniform2f(\n                GLES20.glGetUniformLocation(motionFieldProgram, "uSearchStep"),\n                MOTION_SEARCH_STEP_PIXELS / width.toFloat(),\n                MOTION_SEARCH_STEP_PIXELS / height.toFloat()\n            )\n            GLES20.glUniform2f(\n                GLES20.glGetUniformLocation(motionFieldProgram, "uPatchStep"),\n                MOTION_PATCH_STEP_PIXELS / width.toFloat(),\n                MOTION_PATCH_STEP_PIXELS / height.toFloat()\n            )\n            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)\n            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)\n            motionFieldDirty = false\n            checkGl("calcular campo de movimento")\n        }\n\n        private fun drawTextures'''
)
replace_once(
    transcoder,
    '''            GLES20.glDeleteTextures(2, frameTextures, 0)\n            GLES20.glDeleteFramebuffers(2, framebuffers, 0)\n            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)\n            if (blendProgram != 0) GLES20.glDeleteProgram(blendProgram)''',
    '''            GLES20.glDeleteTextures(2, frameTextures, 0)\n            GLES20.glDeleteFramebuffers(2, framebuffers, 0)\n            GLES20.glDeleteTextures(1, motionTexture, 0)\n            GLES20.glDeleteFramebuffers(1, motionFramebuffer, 0)\n            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)\n            if (blendProgram != 0) GLES20.glDeleteProgram(blendProgram)\n            if (motionFieldProgram != 0) GLES20.glDeleteProgram(motionFieldProgram)\n            if (motionInterpolateProgram != 0) GLES20.glDeleteProgram(motionInterpolateProgram)'''
)
replace_once(
    transcoder,
    '''            externalProgram = 0\n            blendProgram = 0\n        }''',
    '''            externalProgram = 0\n            blendProgram = 0\n            motionFieldProgram = 0\n            motionInterpolateProgram = 0\n        }'''
)
replace_once(
    transcoder,
    '''            private const val BLEND_FRAGMENT_SHADER = """precision mediump float;\nvarying vec2 vTextureCoord;\nuniform sampler2D uPrevious;\nuniform sampler2D uCurrent;\nuniform float uAlpha;\nvoid main(){\n    float eased=uAlpha*uAlpha*(3.0-2.0*uAlpha);\n    gl_FragColor=mix(texture2D(uPrevious,vTextureCoord),texture2D(uCurrent,vTextureCoord),eased);\n}"""''',
    '''            private const val BLEND_FRAGMENT_SHADER = """precision mediump float;\nvarying vec2 vTextureCoord;\nuniform sampler2D uPrevious;\nuniform sampler2D uCurrent;\nuniform float uAlpha;\nvoid main(){\n    float eased=uAlpha*uAlpha*(3.0-2.0*uAlpha);\n    gl_FragColor=mix(texture2D(uPrevious,vTextureCoord),texture2D(uCurrent,vTextureCoord),eased);\n}"""\n            private const val MOTION_FIELD_FRAGMENT_SHADER = """precision highp float;\nvarying vec2 vTextureCoord;\nuniform sampler2D uPrevious;\nuniform sampler2D uCurrent;\nuniform vec2 uSearchStep;\nuniform vec2 uPatchStep;\nfloat lum(vec3 c){ return dot(c,vec3(0.2126,0.7152,0.0722)); }\nfloat errorFor(vec2 delta){\n    float e=0.0;\n    e+=abs(lum(texture2D(uPrevious,vTextureCoord).rgb)-lum(texture2D(uCurrent,vTextureCoord+delta).rgb));\n    e+=abs(lum(texture2D(uPrevious,vTextureCoord+vec2(uPatchStep.x,0.0)).rgb)-lum(texture2D(uCurrent,vTextureCoord+delta+vec2(uPatchStep.x,0.0)).rgb));\n    e+=abs(lum(texture2D(uPrevious,vTextureCoord-vec2(uPatchStep.x,0.0)).rgb)-lum(texture2D(uCurrent,vTextureCoord+delta-vec2(uPatchStep.x,0.0)).rgb));\n    e+=abs(lum(texture2D(uPrevious,vTextureCoord+vec2(0.0,uPatchStep.y)).rgb)-lum(texture2D(uCurrent,vTextureCoord+delta+vec2(0.0,uPatchStep.y)).rgb));\n    e+=abs(lum(texture2D(uPrevious,vTextureCoord-vec2(0.0,uPatchStep.y)).rgb)-lum(texture2D(uCurrent,vTextureCoord+delta-vec2(0.0,uPatchStep.y)).rgb));\n    return e*0.2;\n}\nvoid main(){\n    float best=10.0;\n    float secondBest=10.0;\n    vec2 bestCell=vec2(0.0);\n    for(int y=-5;y<=5;y++){\n        for(int x=-5;x<=5;x++){\n            vec2 cell=vec2(float(x),float(y));\n            vec2 delta=cell*uSearchStep;\n            float e=errorFor(delta)+length(cell)*0.00045;\n            if(e<best){ secondBest=best; best=e; bestCell=cell; }\n            else if(e<secondBest){ secondBest=e; }\n        }\n    }\n    float quality=clamp(1.0-best*4.5,0.0,1.0);\n    float unique=clamp((secondBest-best)/(secondBest+0.0001)*5.0,0.0,1.0);\n    float confidence=quality*(0.35+0.65*unique);\n    vec2 encoded=bestCell/10.0+0.5;\n    gl_FragColor=vec4(encoded,confidence,1.0);\n}"""\n            private const val MOTION_INTERPOLATE_FRAGMENT_SHADER = """precision highp float;\nvarying vec2 vTextureCoord;\nuniform sampler2D uPrevious;\nuniform sampler2D uCurrent;\nuniform sampler2D uMotion;\nuniform vec2 uSearchStep;\nuniform float uAlpha;\nvoid main(){\n    float a=clamp(uAlpha,0.0,1.0);\n    float eased=a*a*(3.0-2.0*a);\n    vec4 flow=texture2D(uMotion,vTextureCoord);\n    vec2 cells=(flow.rg-0.5)*10.0;\n    vec2 delta=cells*uSearchStep;\n    vec2 prevUv=clamp(vTextureCoord-delta*a,vec2(0.0),vec2(1.0));\n    vec2 currUv=clamp(vTextureCoord+delta*(1.0-a),vec2(0.0),vec2(1.0));\n    vec4 previous=texture2D(uPrevious,vTextureCoord);\n    vec4 current=texture2D(uCurrent,vTextureCoord);\n    vec4 warped=mix(texture2D(uPrevious,prevUv),texture2D(uCurrent,currUv),eased);\n    vec4 simple=mix(previous,current,eased);\n    vec4 nearest=a<0.5?previous:current;\n    float fallbackBlend=smoothstep(0.08,0.34,flow.b);\n    vec4 fallback=mix(nearest,simple,fallbackBlend);\n    float motionConfidence=smoothstep(0.18,0.72,flow.b);\n    gl_FragColor=mix(fallback,warped,motionConfidence);\n}"""'''
)
replace_once(
    transcoder,
    '''        private const val FRAME_TEXTURE_COUNT = 2L\n        private const val MAX_GPU_FRAME_BYTES = 160L * 1024L * 1024L''',
    '''        private const val FRAME_TEXTURE_COUNT = 2L\n        private const val MAX_GPU_FRAME_BYTES = 160L * 1024L * 1024L\n        private const val MOTION_SEARCH_STEP_PIXELS = 8f\n        private const val MOTION_PATCH_STEP_PIXELS = 4f'''
)

# ---------------------------------------------------------------------------
# Fila automatica: motion -> blend -> vizinho -> timeline.
# ---------------------------------------------------------------------------
auto_service = 'app/src/main/java/com/steadyvault/camera/processing/auto/AutoGapRepairService.kt'
replace_once(
    auto_service,
    '''            val modes = buildList {\n                add(requestedMode)\n                if (!analysis.hdrHlg10 && requestedMode != FrameRepairMode.FILL_MISSING_FRAMES) {\n                    add(FrameRepairMode.FILL_MISSING_FRAMES)\n                }\n                if (requestedMode != FrameRepairMode.SMOOTH_TIMELINE) add(FrameRepairMode.SMOOTH_TIMELINE)\n            }.distinct()''',
    '''            val modes = buildList {\n                add(requestedMode)\n                if (!analysis.hdrHlg10 && requestedMode == FrameRepairMode.MOTION_COMPENSATED) {\n                    add(FrameRepairMode.ADAPTIVE_BLEND)\n                    add(FrameRepairMode.FILL_MISSING_FRAMES)\n                } else if (!analysis.hdrHlg10 && requestedMode == FrameRepairMode.ADAPTIVE_BLEND) {\n                    add(FrameRepairMode.FILL_MISSING_FRAMES)\n                }\n                if (requestedMode != FrameRepairMode.SMOOTH_TIMELINE) add(FrameRepairMode.SMOOTH_TIMELINE)\n            }.distinct()'''
)
replace_once(
    auto_service,
    '''                val modeLabel = when (mode) {\n                    FrameRepairMode.ADAPTIVE_BLEND -> "mistura temporal por GPU"''',
    '''                val modeLabel = when (mode) {\n                    FrameRepairMode.MOTION_COMPENSATED -> "interpolação compensada por movimento"\n                    FrameRepairMode.ADAPTIVE_BLEND -> "mistura temporal por GPU"'''
)
replace_once(
    auto_service,
    '''                    aiAssisted = settings.aiAssisted && !analysis.hdrHlg10 && mode != FrameRepairMode.SMOOTH_TIMELINE''',
    '''                    aiAssisted = settings.aiAssisted && !analysis.hdrHlg10 && mode == FrameRepairMode.MOTION_COMPENSATED'''
)
replace_once(
    auto_service,
    '''                            if (result.blendedFrames > 0) append(" • ").append(result.blendedFrames).append(" quadro(s) misturado(s)")''',
    '''                            if (result.blendedFrames > 0) append(" • ").append(result.blendedFrames).append(" quadro(s) reconstruído(s)")'''
)

# ---------------------------------------------------------------------------
# UI: metodo por movimento, labels neutras e sem referencias a iPhone.
# ---------------------------------------------------------------------------
ui = 'app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt'
replace_once(
    ui,
    '''            "Equivalente ao Auto FPS do iPhone. Fica desligado por padrão. Em 30/60 FPS, se a câmera publicar uma faixa variável compatível, permite reduzir temporariamente o FPS para ganhar exposição em pouca luz. 120/240 permanecem fixos.",''',
    '''            "Fica desligado por padrão. Em 30/60 FPS, se a câmera publicar uma faixa variável compatível, permite reduzir temporariamente o FPS para ganhar exposição em pouca luz. 120/240 permanecem fixos.",'''
)
replace_once(
    ui,
    '''        addInfo("O bitrate funciona como a taxa média-alvo do AVFoundation: o valor escolhido fica salvo e é enviado diretamente ao encoder. No MediaRecorder o fabricante controla internamente CBR/VBR; o SteadyVault não troca o bitrate escolhido silenciosamente.")''',
    '''        addInfo("O valor escolhido de bitrate fica salvo e é enviado diretamente ao encoder. No MediaRecorder o fabricante controla internamente CBR/VBR; o SteadyVault não troca o bitrate escolhido silenciosamente.")'''
)
replace_once(
    ui,
    '''        addInfo("Pipeline direto Camera2 → Surface → MediaRecorder, equivalente ao caminho de captura simples do AVFoundation: uma única saída de vídeo, sem interpolação ou callbacks por quadro. HEVC/H.264 e bitrate-alvo são configurados quando suportados pelo hardware.")''',
    '''        addInfo("Pipeline direto Camera2 → Surface → MediaRecorder: uma única saída de vídeo durante a captura, sem interpolação ou callbacks por quadro. HEVC/H.264 e bitrate-alvo são configurados quando suportados pelo hardware.")'''
)
replace_once(
    ui,
    '''            FeatureOptionSpec(CaptureSettings.STABILIZATION_AUTO, "Automática (estilo iPhone)", "Escolhe Preview stabilization, EIS, OIS ou Off conforme o formato e as capacidades. Acima de 60 FPS prioriza cadência."),''',
    '''            FeatureOptionSpec(CaptureSettings.STABILIZATION_AUTO, "Automática", "Escolhe Preview stabilization, EIS, OIS ou Off conforme o formato e as capacidades. Acima de 60 FPS prioriza cadência."),'''
)
replace_once(
    ui,
    '''            listOf(\n                option(FrameRepairMode.ADAPTIVE_BLEND.name, "Mistura temporal por GPU (recomendado)", "Cria somente os quadros ausentes misturando os dois quadros reais vizinhos. Se o gap for grande demais, usa o quadro real mais próximo."),\n                option(FrameRepairMode.FILL_MISSING_FRAMES.name, "Quadro real mais próximo", "Preenche posições CFR usando um quadro vizinho real; não inventa movimento, mas pode deixar um instante repetido."),\n                option(FrameRepairMode.SMOOTH_TIMELINE.name, "Somente corrigir timestamps", "Não cria novos pixels. Regulariza a timeline e é o fallback seguro para HDR ou encoder incompatível.")\n            ),''',
    '''            listOf(\n                option(FrameRepairMode.MOTION_COMPENSATED.name, "Interpolação com movimento (recomendado)", "Calcula um campo de movimento entre os quadros reais, desloca cada lado até a posição intermediária e reconstrói somente as lacunas. Se não houver confiança suficiente, usa fallbacks seguros."),\n                option(FrameRepairMode.ADAPTIVE_BLEND.name, "Mistura temporal simples", "Mistura os dois quadros reais vizinhos sem estimar deslocamento. Serve como fallback quando a interpolação por movimento não for aceita."),\n                option(FrameRepairMode.FILL_MISSING_FRAMES.name, "Quadro real mais próximo", "Preenche posições CFR usando um quadro vizinho real; não inventa movimento, mas pode deixar um instante repetido."),\n                option(FrameRepairMode.SMOOTH_TIMELINE.name, "Somente corrigir timestamps", "Não cria novos pixels. Regulariza a timeline e é o fallback seguro para HDR ou encoder incompatível.")\n            ),'''
)
replace_once(
    ui,
    '''            "Máximo de quadros sintetizados por gap",''',
    '''            "Máximo de quadros reconstruídos por gap",'''
)
replace_once(
    ui,
    '''        autoGapRepairAi = addSwitch(\n            "Análise visual local assistida",\n            "Opcional. Usa o analisador local já existente para orientar filtros durante a cópia reparada. O preenchimento dos gaps continua determinístico por GPU; nenhum serviço externo recebe o vídeo.",''',
    '''        autoGapRepairAi = addSwitch(\n            "Análise avançada de movimento",\n            "Opcional. Aumenta o detalhamento do campo de movimento e executa análise visual local antes do reparo. Nenhum serviço externo recebe o vídeo e o original continua intacto.",'''
)

# Comentarios internos tambem ficam neutros.
capture = 'app/src/main/java/com/steadyvault/camera/capture/service/CaptureService.kt'
p = Path(capture)
text = p.read_text(encoding='utf-8')
text = text.replace('Equivalente Android do Auto FPS do iPhone:', 'Auto FPS em pouca luz:')
text = text.replace('como o comportamento do AVFoundation.', 'com AE contínuo.')
p.write_text(text, encoding='utf-8')

# Versao desta iteracao.
build = 'app/build.gradle.kts'
replace_once(build, 'versionCode = 1000144', 'versionCode = 1000145')
