package com.example.project12

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var commandProcessor: CommandProcessor
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListening = false
    private var isSpeaking = false
    private var isDestroyed = false
    private var awakeUntilMillis = 0L
    private var consecutiveBusyErrors = 0

    private val restartRunnable = Runnable {
        if (!isDestroyed && !isSpeaking && !isListening) {
            startDirectListening()
        }
    }

    private val ttsFallbackRunnable = Runnable {
        if (!isDestroyed && isSpeaking) {
            isSpeaking = false
            scheduleRestartListening(250L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AssistantStateBus.initPrefs(this)
        createNotificationChannel()

        commandProcessor = CommandProcessor(this) { userText, asyncReply ->
            if (!isDestroyed) {
                AssistantStateBus.addDialogue(userText, asyncReply)
                speak(asyncReply)
            }
        }

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            AssistantStateBus.updateServiceState(
                running = true,
                listening = false,
                status = getString(R.string.error_no_recognizer)
            )
            Toast.makeText(this, R.string.error_no_recognizer, Toast.LENGTH_LONG).show()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)?.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    consecutiveBusyErrors = 0
                    val modeHint = if (AssistantStateBus.directMode) {
                        "Слушаю напрямую — говорите команду…"
                    } else {
                        "Жду команду со словом «Помощник»…"
                    }
                    AssistantStateBus.updateServiceState(running = true, listening = true, status = modeHint)
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsDb: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        AssistantStateBus.updatePartialSpeech(partial)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    consecutiveBusyErrors = 0
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    handleRecognizedCandidates(matches)
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (isDestroyed || isSpeaking) return

                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            consecutiveBusyErrors = 0
                            scheduleRestartListening(250L)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT -> {
                            consecutiveBusyErrors++
                            if (consecutiveBusyErrors >= 2) {
                                consecutiveBusyErrors = 0
                                initSpeechRecognizer()
                            }
                            scheduleRestartListening(900L)
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            AssistantStateBus.updateServiceState(
                                running = true,
                                listening = false,
                                status = getString(R.string.error_permissions)
                            )
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> {
                            AssistantStateBus.updateServiceState(
                                running = true,
                                listening = false,
                                status = "Ожидание сети для распознавания…"
                            )
                            scheduleRestartListening(2500L)
                        }
                        else -> {
                            scheduleRestartListening(800L)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_EXECUTE_TEXT -> {
                val cmdText = intent.getStringExtra(EXTRA_COMMAND_TEXT).orEmpty().trim()
                if (cmdText.isNotEmpty()) {
                    executeResolvedCommand(cmdText, cmdText)
                } else {
                    scheduleRestartListening(200L)
                }
            }
            ACTION_FORCE_LISTEN -> {
                stopSpeakingInternal()
                awakeUntilMillis = System.currentTimeMillis() + 15_000L
                try {
                    speechRecognizer?.cancel()
                } catch (_: Exception) {
                }
                isListening = false
                scheduleRestartListening(150L)
            }
            ACTION_STOP_SPEAKING -> {
                stopSpeakingInternal()
                scheduleRestartListening(200L)
            }
            else -> {
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Запуск прямого распознавания…"
                )
                scheduleRestartListening(200L)
            }
        }

        return START_STICKY
    }

    private fun handleRecognizedCandidates(matches: List<String>) {
        val primary = matches.firstOrNull()?.trim().orEmpty()
        if (primary.isEmpty()) {
            scheduleRestartListening(250L)
            return
        }

        val (hadWakeWord, stripped) = commandProcessor.stripWakeWordPrefix(primary)

        if (!AssistantStateBus.directMode) {
            val withinAwakeWindow = System.currentTimeMillis() < awakeUntilMillis
            if (!hadWakeWord && !withinAwakeWindow) {
                scheduleRestartListening(250L)
                return
            }
            if (hadWakeWord && stripped.isEmpty()) {
                awakeUntilMillis = System.currentTimeMillis() + 15_000L
                val reply = "Слушаю вас, говорите команду."
                AssistantStateBus.addDialogue(primary, reply)
                speak(reply)
                return
            }
            val cmd = if (hadWakeWord) stripped else primary
            awakeUntilMillis = System.currentTimeMillis() + 8_000L
            executeResolvedCommand(primary, cmd)
            return
        }

        // Direct mode (default): recognize and execute immediately!
        if (hadWakeWord && stripped.isEmpty()) {
            val reply = "Я слушаю напрямую — можете сразу называть любую команду!"
            AssistantStateBus.addDialogue(primary, reply)
            speak(reply)
            return
        }

        val firstCmd = if (hadWakeWord && stripped.isNotEmpty()) stripped else primary
        var result = commandProcessor.process(firstCmd)
        var chosenDisplay = primary

        // Check alternative STT hypotheses if #1 didn't match a command
        if (!result.matched && matches.size > 1) {
            for (alt in matches.drop(1)) {
                val altClean = alt.trim()
                if (altClean.isEmpty()) continue
                val (altWake, altStripped) = commandProcessor.stripWakeWordPrefix(altClean)
                val altCmd = if (altWake && altStripped.isNotEmpty()) altStripped else altClean
                val altRes = commandProcessor.process(altCmd)
                if (altRes.matched) {
                    result = altRes
                    chosenDisplay = altClean
                    break
                }
            }
        }

        applyCommandResult(chosenDisplay, result)
    }

    private fun executeResolvedCommand(displayUserText: String, commandText: String) {
        val result = commandProcessor.process(commandText)
        applyCommandResult(displayUserText, result)
    }

    private fun applyCommandResult(displayUserText: String, result: CommandResult) {
        if (result.stopSpeakingOnly) {
            stopSpeakingInternal()
            AssistantStateBus.addDialogue(displayUserText, "Озвучка остановлена.")
            scheduleRestartListening(250L)
            return
        }

        result.speechRate?.let { rate ->
            tts?.setSpeechRate(rate)
        }

        val reply = result.spokenResponse
        if (!reply.isNullOrBlank()) {
            AssistantStateBus.addDialogue(displayUserText, reply)
            speak(reply, stopAfter = result.shouldStopService)
        } else if (result.shouldStopService) {
            stopSelf()
        } else {
            scheduleRestartListening(250L)
        }
    }

    private fun startDirectListening() {
        if (isDestroyed || isSpeaking || isListening) return
        val recognizer = speechRecognizer
        if (recognizer == null) {
            initSpeechRecognizer()
            if (speechRecognizer == null) return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        try {
            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            scheduleRestartListening(1000L)
        }
    }

    private fun scheduleRestartListening(delayMs: Long) {
        if (isDestroyed) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun stopSpeakingInternal() {
        mainHandler.removeCallbacks(ttsFallbackRunnable)
        isSpeaking = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    private fun speak(text: String, stopAfter: Boolean = false) {
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(ttsFallbackRunnable)

        if (isListening) {
            isListening = false
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
        }

        AssistantStateBus.updateServiceState(
            running = true,
            listening = false,
            status = "Говорю: $text"
        )

        if (!ttsReady || tts == null) {
            if (stopAfter) {
                mainHandler.postDelayed({ stopSelf() }, 400L)
            } else {
                scheduleRestartListening(400L)
            }
            return
        }

        isSpeaking = true
        val utteranceId = if (stopAfter) "utt_stop" else "utt_${System.currentTimeMillis()}"
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res == TextToSpeech.ERROR) {
            isSpeaking = false
            if (stopAfter) stopSelf() else scheduleRestartListening(350L)
            return
        }

        val timeoutMs = (text.length * 95L).coerceIn(3000L, 18_000L)
        mainHandler.postDelayed({
            if (!isDestroyed && isSpeaking) {
                isSpeaking = false
                if (stopAfter) stopSelf() else scheduleRestartListening(250L)
            }
        }, timeoutMs)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        mainHandler.removeCallbacks(ttsFallbackRunnable)
                        isSpeaking = false
                        if (utteranceId == "utt_stop") {
                            stopSelf()
                        } else if (!isDestroyed) {
                            scheduleRestartListening(250L)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        isSpeaking = false
                        if (utteranceId == "utt_stop") stopSelf() else scheduleRestartListening(300L)
                    }
                }
            })
            ttsReady = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VoiceAssistantService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        AssistantStateBus.updateServiceState(
            running = false,
            listening = false,
            status = getString(R.string.status_stopped)
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_assistant_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_FORCE_LISTEN = "com.example.project12.ACTION_FORCE_LISTEN"
        const val ACTION_EXECUTE_TEXT = "com.example.project12.ACTION_EXECUTE_TEXT"
        const val ACTION_STOP_SPEAKING = "com.example.project12.ACTION_STOP_SPEAKING"
        const val ACTION_STOP_SERVICE = "com.example.project12.ACTION_STOP_SERVICE"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
    }
}
