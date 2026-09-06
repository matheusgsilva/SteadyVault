from pathlib import Path

# Auto repair: run only when UI is not interactive. Recording has even higher priority.
p = Path('app/src/main/java/com/steadyvault/camera/processing/auto/AutoGapRepairService.kt')
s = p.read_text()

s = s.replace(
'''            ACTION_PAUSE_CAPTURE, ACTION_PAUSE_USER -> {
                cancelCurrent.set(true)
                if (!workerRunning.get()) stopSelf(startId)
                else publish(currentProgress, "Pausando reparo para priorizar a gravação…", force = true)
            }
''',
'''            ACTION_PAUSE_CAPTURE -> {
                cancelCurrent.set(true)
                if (!workerRunning.get()) stopSelf(startId)
                else publish(currentProgress, "Pausando reparo para priorizar a gravação…", force = true)
            }
            ACTION_PAUSE_USER -> {
                userPauseRequested = true
                cancelCurrent.set(true)
                if (!workerRunning.get()) stopSelf(startId)
                else publish(currentProgress, "Reparo pausado pelo usuário…", force = true)
            }
''', 1)

s = s.replace(
'''        if (!AutoGapRepairSettings.snapshot(this).enabled || capturePriorityRequested || CaptureStateStore.isBusy(this)) {
''',
'''        if (
            !AutoGapRepairSettings.snapshot(this).enabled ||
            capturePriorityRequested ||
            interactivePriorityRequested ||
            userPauseRequested ||
            CaptureStateStore.isBusy(this)
        ) {
''', 1)

s = s.replace(
'''    private fun shouldContinueQueue(): Boolean =
        AutoGapRepairSettings.snapshot(this).enabled &&
            !capturePriorityRequested &&
            !CaptureStateStore.isBusy(this) &&
            !cancelCurrent.get()

    private fun cancelledForCaptureOrUser(): Boolean =
        cancelCurrent.get() ||
            capturePriorityRequested ||
            CaptureStateStore.isBusy(this) ||
            !AutoGapRepairSettings.snapshot(this).enabled ||
            Thread.currentThread().isInterrupted
''',
'''    private fun shouldContinueQueue(): Boolean =
        AutoGapRepairSettings.snapshot(this).enabled &&
            !capturePriorityRequested &&
            !interactivePriorityRequested &&
            !userPauseRequested &&
            !CaptureStateStore.isBusy(this) &&
            !cancelCurrent.get()

    private fun cancelledForCaptureOrUser(): Boolean =
        cancelCurrent.get() ||
            capturePriorityRequested ||
            interactivePriorityRequested ||
            userPauseRequested ||
            CaptureStateStore.isBusy(this) ||
            !AutoGapRepairSettings.snapshot(this).enabled ||
            Thread.currentThread().isInterrupted

    private fun pauseReason(): String = when {
        capturePriorityRequested || CaptureStateStore.isBusy(this) ->
            "Reparo pausado para priorizar uma nova gravação"
        interactivePriorityRequested ->
            "Reparo pausado enquanto o app está em uso; será retomado em segundo plano"
        userPauseRequested ->
            "Reparo pausado pelo usuário"
        else ->
            "Reparo interrompido; será retomado do original"
    }
''', 1)

# Make interruption messages match the real reason instead of always blaming capture.
s = s.replace('return pauseJob(job, "Reparo adiado para priorizar nova gravação")', 'return pauseJob(job, pauseReason())')
s = s.replace('return pauseJob(job, "Reparo pausado; original preservado na fila")', 'return pauseJob(job, pauseReason())')
s = s.replace('return pauseJob(job, "Reparo pausado; será retomado do original")', 'return pauseJob(job, pauseReason())')
s = s.replace('return pauseJob(job, "Reparo interrompido para priorizar a gravação")', 'return pauseJob(job, pauseReason())')

s = s.replace(
'''        @Volatile private var capturePriorityRequested = false
''',
'''        @Volatile private var capturePriorityRequested = false
        @Volatile private var interactivePriorityRequested = false
        @Volatile private var userPauseRequested = false
''', 1)

old_enqueue = '''        fun enqueue(context: Context, source: File, targetFps: Int) {
            if (!AutoGapRepairSettings.snapshot(context).enabled || !source.isFile) return
            AutoGapRepairQueueStore.enqueue(context, source, targetFps)
            startSelf(context, ACTION_ENQUEUE) {
                putExtra(EXTRA_SOURCE_PATH, source.absolutePath)
                putExtra(EXTRA_TARGET_FPS, targetFps)
            }
        }
'''
new_enqueue = '''        fun enqueue(context: Context, source: File, targetFps: Int) {
            if (!AutoGapRepairSettings.snapshot(context).enabled || !source.isFile) return
            // Persist first. Starting a foreground transcoder is intentionally deferred
            // while the camera or an interactive SteadyVault screen owns resources.
            AutoGapRepairQueueStore.enqueue(context, source, targetFps)
            if (
                capturePriorityRequested ||
                interactivePriorityRequested ||
                userPauseRequested ||
                CaptureStateStore.isBusy(context)
            ) return
            startSelf(context, ACTION_RESUME)
        }
'''
assert old_enqueue in s
s = s.replace(old_enqueue, new_enqueue, 1)

old_pause_user = '''        fun pauseByUser(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AutoGapRepairService::class.java).setAction(ACTION_PAUSE_USER)
                )
            }
        }

        fun resumeIfEnabled(context: Context) {
            if (!AutoGapRepairSettings.snapshot(context).enabled) return
            AutoGapRepairQueueStore.recoverInterrupted(context)
            if (!AutoGapRepairQueueStore.hasPending(context)) return
            startSelf(context, ACTION_RESUME)
        }

        fun retryFailed(context: Context) {
            AutoGapRepairQueueStore.retryFailed(context)
            if (AutoGapRepairSettings.snapshot(context).enabled) {
                startSelf(context, ACTION_RETRY_FAILED)
            }
        }
'''
new_pause_user = '''        fun pauseForInteractiveUse() {
            // Same-process flag: a running transcoder observes this in its cancellation
            // callback without starting another Android service just to stop work.
            interactivePriorityRequested = true
        }

        fun resumeForBackground(context: Context) {
            interactivePriorityRequested = false
            resumeIfEnabled(context)
        }

        fun pauseByUser(context: Context) {
            userPauseRequested = true
            runCatching {
                context.startService(
                    Intent(context, AutoGapRepairService::class.java).setAction(ACTION_PAUSE_USER)
                )
            }
        }

        fun resumeByUser(context: Context) {
            userPauseRequested = false
            resumeIfEnabled(context)
        }

        fun resumeIfEnabled(context: Context) {
            if (!AutoGapRepairSettings.snapshot(context).enabled) return
            if (
                capturePriorityRequested ||
                interactivePriorityRequested ||
                userPauseRequested ||
                CaptureStateStore.isBusy(context)
            ) return
            AutoGapRepairQueueStore.recoverInterrupted(context)
            if (!AutoGapRepairQueueStore.hasPending(context)) return
            startSelf(context, ACTION_RESUME)
        }

        fun retryFailed(context: Context) {
            userPauseRequested = false
            AutoGapRepairQueueStore.retryFailed(context)
            resumeIfEnabled(context)
        }
'''
assert old_pause_user in s
s = s.replace(old_pause_user, new_pause_user, 1)
p.write_text(s)

# Application lifecycle owns the interactive/background priority for AUTO repair.
p = Path('app/src/main/java/com/steadyvault/camera/SteadyVaultApplication.kt')
s = p.read_text()

old_startup = '''        protect("APP_STARTUP", "retomar reparo automatico") {
            AutoGapRepairService.resumeIfEnabled(this)
        }
'''
assert old_startup in s
s = s.replace(old_startup, '', 1)

old_screen = '''            protect("APP_LIFECYCLE", "bloqueio ao apagar a tela") {
                if (VaultSecuritySettings.lockOnScreenOff(this@SteadyVaultApplication)) {
                    PrimaryVaultLock.lock()
                    SecondaryVaultLock.lock()
                    TertiaryVaultLock.lock()
                }
            }
'''
new_screen = '''            protect("APP_LIFECYCLE", "bloqueio ao apagar a tela") {
                if (VaultSecuritySettings.lockOnScreenOff(this@SteadyVaultApplication)) {
                    PrimaryVaultLock.lock()
                    SecondaryVaultLock.lock()
                    TertiaryVaultLock.lock()
                }
            }
            // Screen-off is the ideal time for automatic repair: no player/UI is
            // competing for decoder/GPU and the foreground service owns a wake lock.
            protect("APP_LIFECYCLE", "retomar reparo com tela apagada") {
                AutoGapRepairService.resumeForBackground(this@SteadyVaultApplication)
            }
'''
assert old_screen in s
s = s.replace(old_screen, new_screen, 1)

old_started = '''    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && !changingConfiguration) {
            handler.removeCallbacks(delayedLock)
'''
new_started = '''    override fun onActivityStarted(activity: Activity) {
        if (startedActivities == 0 && !changingConfiguration) {
            // Automatic transcode yields while the user is navigating, opening the
            // vault/settings or playing the original. The job stays persisted.
            AutoGapRepairService.pauseForInteractiveUse()
            handler.removeCallbacks(delayedLock)
'''
assert old_started in s
s = s.replace(old_started, new_started, 1)

old_stopped_tail = '''                when {
                    timeout == VaultSecuritySettings.TIMEOUT_IMMEDIATE -> delayedLock.run()
                    timeout > 0L -> handler.postDelayed(delayedLock, timeout)
                }
            }
        }
    }
'''
new_stopped_tail = '''                when {
                    timeout == VaultSecuritySettings.TIMEOUT_IMMEDIATE -> delayedLock.run()
                    timeout > 0L -> handler.postDelayed(delayedLock, timeout)
                }
            }
            protect("APP_LIFECYCLE", "retomar reparo em segundo plano") {
                AutoGapRepairService.resumeForBackground(this)
            }
        }
    }
'''
assert old_stopped_tail in s
s = s.replace(old_stopped_tail, new_stopped_tail, 1)

old_resumed = '''    override fun onActivityResumed(activity: Activity) {
        protect("APP_APPEARANCE", "reaplicar aparencia em ${activity.javaClass.simpleName}") {
'''
new_resumed = '''    override fun onActivityResumed(activity: Activity) {
        AutoGapRepairService.pauseForInteractiveUse()
        protect("APP_APPEARANCE", "reaplicar aparencia em ${activity.javaClass.simpleName}") {
'''
assert old_resumed in s
s = s.replace(old_resumed, new_resumed, 1)
p.write_text(s)

# Explain the actual behavior in Settings and make manual resume meaningful.
p = Path('app/src/main/java/com/steadyvault/camera/ui/settings/SettingsActivity.kt')
s = p.read_text()
s = s.replace('''        addSmallButton("Retomar fila de reparo agora") {
            AutoGapRepairService.resumeIfEnabled(this)
            refreshAutoGapRepairQueueCard()
            Toast.makeText(this, "Fila de reparo retomada", Toast.LENGTH_SHORT).show()
        }
        addInfo("O reparo roda com prioridade baixa e nunca bloqueia a câmera. Cada tentativa começa novamente do MP4 original; temporários incompletos são descartados. A ferramenta Otimizar vídeo no Cofre continua disponível para reprocessar manualmente qualquer arquivo.")
''', '''        addSmallButton("Liberar fila para reparo em segundo plano") {
            AutoGapRepairService.resumeByUser(this)
            refreshAutoGapRepairQueueCard()
            Toast.makeText(this, "Fila liberada; o reparo começa ao sair do app ou apagar a tela", Toast.LENGTH_LONG).show()
        }
        addInfo("Para evitar travamentos e disputa de GPU/decoder, o reparo automático fica pausado enquanto você navega no SteadyVault e retoma quando o app vai para segundo plano ou a tela é apagada. Uma nova gravação sempre tem prioridade e interrompe qualquer processamento. Cada tentativa recomeça do MP4 original; temporários incompletos são descartados.")
''', 1)
p.write_text(s)

# Version bump for the validated lifecycle/capture stability build.
p = Path('app/build.gradle.kts')
s = p.read_text()
assert 'versionCode = 1000150' in s
s = s.replace('versionCode = 1000150', 'versionCode = 1000151', 1)
p.write_text(s)

print('background-only automatic repair lifecycle applied')
