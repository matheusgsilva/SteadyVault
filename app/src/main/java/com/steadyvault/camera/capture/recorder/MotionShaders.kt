package com.steadyvault.camera.capture.recorder

/**
 * Shaders (GLSL ES 1.00) da interpolação com compensação de movimento em tempo real.
 *
 * Pipeline, executado só quando a câmera deixa de entregar um frame:
 *  1. DOWN_*      : frame -> luma em 1/8 da resolução (caixa 8x8 exata via 16 taps bilineares).
 *  2. PYRAMID     : luma 1/8 -> 1/16 -> 1/32.
 *  3. ESTIMATE    : block matching hierárquico (1/32 -> 1/16 -> 1/8). O fluxo é guardado em
 *                   textura half-float (rg = deslocamento em UV, b = custo SAD médio).
 *  4. WARP        : para o instante t entre os dois frames reais, amostra o anterior em
 *                   (x - t*F) e o atual em (x + (1-t)*F) e mistura. Onde o fluxo não é
 *                   confiável (custo alto ou os dois lados discordam), volta ao crossfade.
 *
 * Os mesmos textos são carregados pelo teste headless (WebGL1) em tools/motion_test, por
 * isso não usam nada além de GLSL ES 1.00. Mantenha este arquivo sem o caractere de dólar.
 */
internal object MotionShaders {

    const val VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vUv;
void main() {
    gl_Position = aPosition;
    vUv = aTexCoord.xy;
}
"""

    /**
     * Luma em 1/8. Com SOURCE_OES definido lê o frame atual da câmera (aplicando rotação e
     * matriz do SurfaceTexture); sem ele lê o frame anterior, que já está no espaço de saída.
     */
    const val DOWN_FRAGMENT = """
#ifdef SOURCE_OES
#extension GL_OES_EGL_image_external : require
#endif
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform vec2 uTexel;
#ifdef SOURCE_OES
uniform samplerExternalOES sSrc;
uniform mat4 uTextureMatrix;
uniform float uRotationDegrees;
vec2 rotateUv(vec2 uv) {
    if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);
    if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);
    return uv;
}
vec3 fetchColor(vec2 uv) {
    return texture2D(sSrc, (uTextureMatrix * vec4(rotateUv(uv), 0.0, 1.0)).xy).rgb;
}
#else
uniform sampler2D sSrc;
vec3 fetchColor(vec2 uv) {
    return texture2D(sSrc, uv).rgb;
}
#endif
void main() {
    float acc = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) - 1.5) * 2.0 * uTexel;
            acc += dot(fetchColor(vUv + o), vec3(0.299, 0.587, 0.114));
        }
    }
    gl_FragColor = vec4(acc / 16.0, 0.0, 0.0, 1.0);
}
"""

    /** Luma 1/8 -> 1/16 -> 1/32 (caixa 2x2). */
    const val PYRAMID_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D sSrc;
uniform vec2 uSrcTexel;
float tap(vec2 o) {
    return texture2D(sSrc, vUv + o * uSrcTexel).r;
}
void main() {
    float v = 0.25 * (tap(vec2(-0.5, -0.5)) + tap(vec2(0.5, -0.5)) +
                      tap(vec2(-0.5, 0.5)) + tap(vec2(0.5, 0.5)));
    gl_FragColor = vec4(v, 0.0, 0.0, 1.0);
}
"""

    /**
     * Block matching de um nível. Antes deste texto o código Kotlin define:
     *   R       raio da busca (em passos)         STEP   passo em texels (pode ser fracionário)
     *   WIN     meia janela do SAD (texels)       NORM   (2*WIN+1)^2 como float
     *   LAMBDA  penalidade por texel de afastamento do palpite do nível mais grosso
     */
    const val ESTIMATE_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D sP;
uniform sampler2D sC;
uniform sampler2D sFlow;
uniform vec2 uTexel;
uniform float uHasPrior;
void main() {
    vec2 prior = vec2(0.0);
    if (uHasPrior > 0.5) {
        prior = texture2D(sFlow, vUv).rg;
    }
    vec2 center = prior / uTexel;
    float best = 1.0e9;
    float bestSad = 0.0;
    vec2 bestD = center;
    for (int j = -R; j <= R; j++) {
        for (int i = -R; i <= R; i++) {
            vec2 stepOffset = vec2(float(i), float(j)) * STEP;
            vec2 dt = center + stepOffset;
            vec2 d = dt * uTexel;
            float sad = 0.0;
            for (int v = -WIN; v <= WIN; v++) {
                for (int u = -WIN; u <= WIN; u++) {
                    vec2 o = vec2(float(u), float(v)) * uTexel;
                    sad += abs(texture2D(sP, vUv + o).r - texture2D(sC, vUv + o + d).r);
                }
            }
            sad /= NORM;
            float score = sad + LAMBDA * length(stepOffset);
            if (score < best) {
                best = score;
                bestSad = sad;
                bestD = dt;
            }
        }
    }
    gl_FragColor = vec4(bestD * uTexel, bestSad, 1.0);
}
"""

    /**
     * Frame intermediário no instante t (uAlpha = peso do frame atual). Com SOURCE_OES o
     * frame atual vem direto da câmera; sem ele (teste) vem de uma textura 2D.
     */
    const val WARP_FRAGMENT = """
#ifdef SOURCE_OES
#extension GL_OES_EGL_image_external : require
#endif
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D sP;
uniform sampler2D sFlow;
uniform float uAlpha;
uniform float uCostLow;
uniform float uCostHigh;
uniform float uDiffLow;
uniform float uDiffHigh;
uniform float uLook;
uniform vec2 uFlowTexel;
LOOK_GLSL
#ifdef SOURCE_OES
uniform samplerExternalOES sC;
uniform mat4 uTextureMatrix;
uniform float uRotationDegrees;
vec2 rotateUv(vec2 uv) {
    if (uRotationDegrees > 225.0) return vec2(uv.y, 1.0 - uv.x);
    if (uRotationDegrees > 135.0) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (uRotationDegrees > 45.0) return vec2(1.0 - uv.y, uv.x);
    return uv;
}
vec3 currentColor(vec2 uv) {
    return texture2D(sC, (uTextureMatrix * vec4(rotateUv(uv), 0.0, 1.0)).xy).rgb;
}
#else
uniform sampler2D sC;
vec3 currentColor(vec2 uv) {
    return texture2D(sC, uv).rgb;
}
#endif
vec3 previousColor(vec2 uv) {
    return texture2D(sP, uv).rgb;
}
vec2 gFlow;
float gCostConf;
float gDiffConf;
vec3 composite(vec2 uv) {
    float t = uAlpha;
    vec3 p = previousColor(uv - t * gFlow);
    vec3 c = currentColor(uv + (1.0 - t) * gFlow);
    vec3 plainNear = previousColor(uv);
    vec3 near = p;
    if (t >= 0.5) {
        plainNear = currentColor(uv);
        near = c;
    }
    // Os dois lados concordam: media (reduz ruido). Discordam: usa so o mais proximo no
    // tempo, nitido, em vez de uma mistura borrada. Fluxo nao confiavel: frame mais proximo.
    vec3 warped = mix(near, mix(p, c, t), gDiffConf);
    return mix(plainNear, warped, gCostConf);
}
void main() {
    float t = uAlpha;
    // Selecao de vetor: testa o fluxo do bloco e o dos 4 vizinhos e fica com o que melhor
    // casa os dois frames neste pixel. Corrige as bordas de objetos (riscos) onde o fluxo
    // bilinear misturaria vetores de fundo e de objeto.
    vec2 base = (floor(vUv / uFlowTexel) + 0.5) * uFlowTexel;
    vec4 bestFlow = vec4(0.0);
    vec3 bestP = vec3(0.0);
    vec3 bestC = vec3(0.0);
    float bestDiff = 1.0e9;
    for (int i = 0; i < 5; i++) {
        vec2 o = vec2(0.0);
        if (i == 1) o = vec2(1.0, 0.0);
        if (i == 2) o = vec2(-1.0, 0.0);
        if (i == 3) o = vec2(0.0, 1.0);
        if (i == 4) o = vec2(0.0, -1.0);
        vec4 f = texture2D(sFlow, base + o * uFlowTexel);
        vec3 p = previousColor(vUv - t * f.rg);
        vec3 c = currentColor(vUv + (1.0 - t) * f.rg);
        float d = length(p - c) + (i == 0 ? 0.0 : 0.08);
        if (d < bestDiff) {
            bestDiff = d;
            bestFlow = f;
            bestP = p;
            bestC = c;
        }
    }
    gFlow = bestFlow.rg;
    gCostConf = 1.0 - smoothstep(uCostLow, uCostHigh, bestFlow.b);
    gDiffConf = 1.0 - smoothstep(uDiffLow, uDiffHigh, length(bestP - bestC));
    vec3 result = composite(vUv);
    if (uLook > 0.5) {
        vec2 dx = vec2(uLookTexel.x, 0.0);
        vec2 dy = vec2(0.0, uLookTexel.y);
        vec3 blur = 0.25 * (composite(vUv + dx) + composite(vUv - dx) +
                            composite(vUv + dy) + composite(vUv - dy));
        result = lookGrade(result, blur);
    }
    gl_FragColor = vec4(result, 1.0);
}
"""

    /**
     * Detector de frame duplicado. Compara a luma 1/8 (8 bits) de dois frames e, para cada
     * pixel de saida em 1/32, grava a fração dos 16 texels 1/8 que mudaram (qualquer diferença
     * de 1 nivel ja conta). Dois frames reais sempre diferem por ruido; um frame que a HAL
     * reentrega identico da zero em tudo.
     */
    const val DIFF_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D sP;
uniform sampler2D sC;
uniform vec2 uTexel;
void main() {
    float flags = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) - 1.5) * uTexel;
            float d = abs(texture2D(sP, vUv + o).r - texture2D(sC, vUv + o).r);
            flags += step(0.0019, d);
        }
    }
    gl_FragColor = vec4(flags / 16.0, 0.0, 0.0, 1.0);
}
"""
}
