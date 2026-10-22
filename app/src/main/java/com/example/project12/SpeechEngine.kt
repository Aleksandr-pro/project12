package com.example.project12

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechEngine(
    private val serviceContext: Context,
    private val onRecognized: (List<String>) -> Unit
) {

    private enum class State {
        IDLE,
        STARTING,
        LISTENING,
        PROCESSING,
        PAUSED
    }

    private data class ProviderSpec(
        val label: String,
        val component: ComponentName?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerUsedActivityContext = false

    private var state: State = State.IDLE
    private var providers: List<ProviderSpec> = emptyList()
    private var providerIndex = 0
    private var langMode = 0 // 0 = "ru-RU", 1 = "ru", 2 = system default
    private var silenceStreak = 0
    private var errorStreakOnCurrentProvider = 0
    private var totalPermDenials = 0
    private var sessionId = 0

    private val scheduledStartRunnable = Runnable {
        if (state == State.IDLE) {
            startListeningNow()
        }
    }

    private val startupWatchdogRunnable = Runnable {
        if (state == State.STARTING) {
            AssistantStateBus.updateDiagnostic("Переподключение микрофона (таймаут запуска)…")
            rotateProviderIfNeeded(forceRecreate = true)
            state = State.IDLE
            scheduleStart(600L)
        }
    }

    private val listenWatchdogRunnable = Runnable {
        if (state == State.LISTENING) {
            destroyRecognizer()
            state = State.IDLE
            scheduleStart(600L)
        }
    }

    private val processingWatchdogRunnable = Runnable {
        if (state == State.PROCESSING) {
            destroyRecognizer()
            state = State.IDLE
            scheduleStart(700L)
        }
    }

    init {
        providers = discoverProviders()
    }

    @Suppress("DEPRECATION")
    private fun discoverProviders(): List<ProviderSpec> {
        val result = mutableListOf<ProviderSpec>()
        val pm = serviceContext.packageManager

        if (SpeechRecognizer.isRecognitionAvailable(serviceContext)) {
            result.add(ProviderSpec("Системный распознаватель", null))
        }

        try {
            val queryIntent = Intent(RecognitionService.SERVICE_INTERFACE)
            val services = pm.queryIntentServices(queryIntent, 0)

            // Prioritize Speech Services by Google (com.google.android.tts), then Google App, then others
            val sorted = services.sortedBy { info ->
                val pkg = info.serviceInfo.packageName
                when {
                    pkg == "com.google.android.tts" -> 0
                    pkg == "com.google.android.googlequicksearchbox" -> 1
                    pkg.contains("google") -> 2
                    else -> 3
                }
            }

            for (info in sorted) {
                val si = info.serviceInfo ?: continue
                val cn = ComponentName(si.packageName, si.name)
                val friendly = when (si.packageName) {
                    "com.google.android.tts" -> "Речевые сервисы Google"
                    "com.google.android.googlequicksearchbox" -> "Приложение Google"
                    else -> info.loadLabel(pm).toString().ifBlank { si.packageName }
                }
                result.add(ProviderSpec(friendly, cn))
            }
        } catch (_: Exception) {
        }

        return result.distinctBy { it.component?.flattenToShortString() ?: "default" }
    }

    fun start() {
        mainHandler.post {
            totalPermDenials = 0
            if (providers.isEmpty()) {
                providers = discoverProviders()
            }
            if (providers.isEmpty()) {
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "На телефоне не найдена служба распознавания речи",
                    diagnostic = "Установите или включите «Речевые сервисы от Google» в Play Маркете, либо используйте кнопку 🎤 ниже."
                )
                return@post
            }
            if (state == State.PAUSED) {
                state = State.IDLE
            }
            if (state == State.IDLE) {
                scheduleStart(150L)
            }
        }
    }

    fun forceRestartImmediate() {
        mainHandler.post {
            clearWatchdogs()
            destroyRecognizer()
            silenceStreak = 0
            state = State.IDLE
            scheduleStart(200L)
        }
    }

    fun pause() {
        mainHandler.post {
            clearWatchdogs()
            mainHandler.removeCallbacks(scheduledStartRunnable)
            state = State.PAUSED
            destroyRecognizer()
        }
    }

    fun onActivityBackgrounded() {
        mainHandler.post {
            if (recognizerUsedActivityContext && state != State.PAUSED) {
                clearWatchdogs()
                destroyRecognizer()
                state = State.IDLE
                scheduleStart(350L)
            }
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        state = State.PAUSED
        destroyRecognizer()
    }

    private fun scheduleStart(delayMs: Long) {
        if (state == State.PAUSED) return
        mainHandler.removeCallbacks(scheduledStartRunnable)
        mainHandler.postDelayed(scheduledStartRunnable, delayMs)
    }

    private fun clearWatchdogs() {
        mainHandler.removeCallbacks(startupWatchdogRunnable)
        mainHandler.removeCallbacks(listenWatchdogRunnable)
        mainHandler.removeCallbacks(processingWatchdogRunnable)
    }

    private fun destroyRecognizer() {
        val r = recognizer
        recognizer = null
        recognizerUsedActivityContext = false
        if (r != null) {
            try {
                r.cancel()
            } catch (_: Exception) {
            }
            try {
                r.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        val activeAct = AssistantStateBus.getActiveActivity()
        if (recognizer != null && recognizerUsedActivityContext && activeAct == null) {
            destroyRecognizer()
        }
        if (recognizer != null) return recognizer

        if (providers.isEmpty()) {
            providers = discoverProviders()
            if (providers.isEmpty()) return null
        }

        val spec = providers[providerIndex % providers.size]
        val bindCtx: Context = activeAct ?: serviceContext
        recognizerUsedActivityContext = (activeAct != null)

        val currentSession = ++sessionId
        val created = try {
            if (spec.component == null) {
                SpeechRecognizer.createSpeechRecognizer(bindCtx)
            } else {
                SpeechRecognizer.createSpeechRecognizer(bindCtx, spec.component)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (currentSession != sessionId || state == State.PAUSED) return
                clearWatchdogs()
                state = State.LISTENING
                errorStreakOnCurrentProvider = 0
                totalPermDenials = 0
                mainHandler.postDelayed(listenWatchdogRunnable, 25_000L)

                val modeHint = if (AssistantStateBus.directMode) {
                    "🟢 Нелли слушает — говорите своими словами"
                } else {
                    "🟢 Нелли ждёт обращения — скажите «Нелли, …»"
                }
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = true,
                    status = modeHint,
                    diagnostic = "Движок речи: ${spec.label}"
                )
            }

            override fun onBeginningOfSpeech() {
                if (currentSession != sessionId || state == State.PAUSED) return
                silenceStreak = 0
            }

            override fun onRmsChanged(rmsDb: Float) {
                if (currentSession != sessionId || state != State.LISTENING) return
                AssistantStateBus.updateMicRms(rmsDb)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (currentSession != sessionId || state == State.PAUSED) return
                clearWatchdogs()
                state = State.PROCESSING
                mainHandler.postDelayed(processingWatchdogRunnable, 7_000L)
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Распознаю сказанное…"
                )
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (currentSession != sessionId || state == State.PAUSED) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    silenceStreak = 0
                    AssistantStateBus.updatePartialSpeech(partial)
                }
            }

            override fun onResults(results: Bundle?) {
                if (currentSession != sessionId || state == State.PAUSED) return
                clearWatchdogs()
                state = State.IDLE
                silenceStreak = 0
                errorStreakOnCurrentProvider = 0

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (matches.isEmpty()) {
                    scheduleStart(700L)
                } else {
                    onRecognized(matches)
                }
            }

            override fun onError(error: Int) {
                if (currentSession != sessionId || state == State.PAUSED) return
                clearWatchdogs()
                state = State.IDLE
                handleRecognitionError(error, spec)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer = created
        return created
    }

    private fun startListeningNow() {
        if (state != State.IDLE) return

        val rec = ensureRecognizer()
        if (rec == null) {
            rotateProviderIfNeeded(forceRecreate = true)
            scheduleStart(1200L)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, serviceContext.packageName)
            when (langMode) {
                0 -> {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                }
                1 -> {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru")
                }
                else -> {
                    // System default locale
                }
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            state = State.STARTING
            mainHandler.postDelayed(startupWatchdogRunnable, 5_000L)
            rec.startListening(intent)
        } catch (e: Exception) {
            clearWatchdogs()
            state = State.IDLE
            AssistantStateBus.updateDiagnostic("Ошибка запуска микрофона: ${e.javaClass.simpleName}")
            rotateProviderIfNeeded(forceRecreate = true)
            scheduleStart(1200L)
        }
    }

    private fun handleRecognitionError(error: Int, spec: ProviderSpec) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // Normal silence or indistinct noise — reuse bound recognizer with polite pacing
                silenceStreak = (silenceStreak + 1).coerceAtMost(10)
                val delay = if (silenceStreak >= 4) 1300L else 750L
                scheduleStart(delay)
            }

            12, 13 -> {
                // ERROR_LANGUAGE_NOT_SUPPORTED (12) / ERROR_LANGUAGE_UNAVAILABLE (13)
                langMode = (langMode + 1) % 3
                AssistantStateBus.updateDiagnostic("Переключаю языковой профиль распознавания (#$langMode)…")
                destroyRecognizer()
                scheduleStart(800L)
            }

            10 -> {
                // ERROR_TOO_MANY_REQUESTS (Android 12+)
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Пауза микрофона (защита от частых запросов)…",
                    diagnostic = "Автовозобновление через 3 сек."
                )
                destroyRecognizer()
                scheduleStart(3200L)
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            11 -> {
                // Busy (8) or Server disconnected (11)
                destroyRecognizer()
                scheduleStart(1200L)
            }

            SpeechRecognizer.ERROR_CLIENT -> {
                // Error 5: often happens when OEM default recognizer rejects 3rd-party binding
                rotateProviderIfNeeded(forceRecreate = true)
                AssistantStateBus.updateDiagnostic("Перезапуск движка речи (код 5)…")
                scheduleStart(1000L)
            }

            SpeechRecognizer.ERROR_AUDIO -> {
                // Error 3: mic temporarily busy or blocked in background
                destroyRecognizer()
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Микрофон временно занят — повтор…",
                    diagnostic = "Код 3 (аудиопоток). Проверьте, не использует ли микрофон другое приложение."
                )
                scheduleStart(1800L)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                // Error 9: either RECORD_AUDIO missing OR selected OEM service denies external binding
                totalPermDenials++
                if (providers.size > 1 && totalPermDenials < providers.size) {
                    rotateProviderIfNeeded(forceRecreate = true)
                    AssistantStateBus.updateDiagnostic("Пробую сервис речи: ${providers[providerIndex % providers.size].label}…")
                    scheduleStart(900L)
                } else {
                    destroyRecognizer()
                    AssistantStateBus.updateServiceState(
                        running = true,
                        listening = false,
                        status = "Служба речи (${spec.label}) отклонила фоновый микрофон",
                        diagnostic = "Используйте кнопку «🎤 Сказать команду» ниже или дайте разрешение микрофона приложению «Google»."
                    )
                }
            }

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Нет ответа от сервера распознавания…",
                    diagnostic = "Код $error — проверьте интернет (Wi-Fi / мобильные данные)."
                )
                scheduleStart(2500L)
            }

            else -> {
                rotateProviderIfNeeded(forceRecreate = true)
                AssistantStateBus.updateDiagnostic("Код состояния речи: $error — переподключение…")
                scheduleStart(1200L)
            }
        }
    }

    private fun rotateProviderIfNeeded(forceRecreate: Boolean) {
        if (forceRecreate) {
            destroyRecognizer()
        }
        errorStreakOnCurrentProvider++
        if (providers.size > 1 && errorStreakOnCurrentProvider >= 1) {
            providerIndex = (providerIndex + 1) % providers.size
            errorStreakOnCurrentProvider = 0
        }
    }
}
