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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechEngine: SpeechEngine? = null
    private lateinit var commandProcessor: CommandProcessor
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isSpeaking = false
    private var isDestroyed = false
    private var pendingStopAfterSpeak = false
    private var pausedForExternalDialog = false
    private var awakeUntilMillis = 0L

    private val ttsFallbackRunnable = Runnable {
        if (!isDestroyed && isSpeaking) {
            isSpeaking = false
            val shouldStop = pendingStopAfterSpeak
            pendingStopAfterSpeak = false
            if (shouldStop) {
                stopSelf()
            } else if (!pausedForExternalDialog) {
                speechEngine?.start()
            }
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
    }

    private fun ensureSpeechEngine(): SpeechEngine {
        var engine = speechEngine
        if (engine == null) {
            engine = SpeechEngine(this) { candidates ->
                handleRecognizedCandidates(candidates)
            }
            speechEngine = engine
        }
        return engine
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must enter foreground with FOREGROUND_SERVICE_TYPE_MICROPHONE BEFORE binding SpeechRecognizer
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AssistantStateBus.updateServiceState(
                running = false,
                listening = false,
                status = "Не удалось запустить фоновый микрофон",
                diagnostic = "Откройте приложение на экране и нажмите Запустить (${e.javaClass.simpleName})."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val engine = ensureSpeechEngine()

        when (intent?.action) {
            ACTION_PAUSE_FOR_DIALOG -> {
                pausedForExternalDialog = true
                stopSpeakingInternal()
                engine.pause()
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Ожидание системного окна голосового ввода…"
                )
            }

            ACTION_RESUME_LISTENING -> {
                pausedForExternalDialog = false
                if (!isSpeaking) {
                    engine.start()
                }
            }

            ACTION_ACTIVITY_PAUSED -> {
                engine.onActivityBackgrounded()
            }

            ACTION_EXECUTE_TEXT -> {
                pausedForExternalDialog = false
                val candidates = intent.getStringArrayListExtra(EXTRA_CANDIDATES_LIST)
                val single = intent.getStringExtra(EXTRA_COMMAND_TEXT).orEmpty().trim()
                val list = when {
                    !candidates.isNullOrEmpty() -> candidates.map { it.trim() }.filter { it.isNotEmpty() }
                    single.isNotEmpty() -> listOf(single)
                    else -> emptyList()
                }
                if (list.isNotEmpty()) {
                    // Direct command submitted from dialog or text box always executes regardless of wake word
                    executeCandidateListDirectly(list)
                } else if (!isSpeaking) {
                    engine.start()
                }
            }

            ACTION_FORCE_LISTEN -> {
                pausedForExternalDialog = false
                stopSpeakingInternal()
                awakeUntilMillis = System.currentTimeMillis() + 15_000L
                engine.forceRestartImmediate()
            }

            ACTION_STOP_SPEAKING -> {
                stopSpeakingInternal()
                if (!pausedForExternalDialog) {
                    engine.start()
                }
            }

            else -> {
                pausedForExternalDialog = false
                AssistantStateBus.updateServiceState(
                    running = true,
                    listening = false,
                    status = "Подключение микрофона…"
                )
                engine.start()
            }
        }

        return START_STICKY
    }

    private fun handleRecognizedCandidates(matches: List<String>) {
        val primary = matches.firstOrNull()?.trim().orEmpty()
        if (primary.isEmpty()) {
            if (!pausedForExternalDialog && !isSpeaking) {
                speechEngine?.start()
            }
            return
        }

        val wakeChecks = matches.map { commandProcessor.stripWakeWordPrefix(it) }
        val wakeIndex = wakeChecks.indexOfFirst { it.first }
        val hadWakeWord = wakeIndex >= 0
        val stripped = if (wakeIndex >= 0) wakeChecks[wakeIndex].second else primary

        if (!AssistantStateBus.directMode) {
            val interactiveDialogueActive = ConversationContext.peekPendingSlot() != null ||
                ConversationContext.activeRiddle != null ||
                ConversationContext.citiesGameActive
            val withinAwakeWindow = System.currentTimeMillis() < awakeUntilMillis || interactiveDialogueActive
            if (!hadWakeWord && !withinAwakeWindow) {
                if (!pausedForExternalDialog && !isSpeaking) {
                    speechEngine?.start()
                }
                return
            }
            if (hadWakeWord && stripped.isEmpty()) {
                awakeUntilMillis = System.currentTimeMillis() + 15_000L
                val reply = "Да, я здесь! Слушаю вас."
                AssistantStateBus.addDialogue(primary, reply)
                speak(reply)
                return
            }
            awakeUntilMillis = System.currentTimeMillis() + 12_000L
            executeCandidateListDirectly(matches)
            return
        }

        // Direct mode (default): execute immediately
        if (hadWakeWord && stripped.isEmpty()) {
            val reply = "Да, я Нелли! Слушаю вас напрямую — говорите, что сделать."
            AssistantStateBus.addDialogue(primary, reply)
            speak(reply)
            return
        }

        executeCandidateListDirectly(matches)
    }

    private fun executeCandidateListDirectly(matches: List<String>) {
        val (chosenDisplay, result) = commandProcessor.processCandidates(matches)
        if (chosenDisplay.isEmpty()) return
        applyCommandResult(chosenDisplay, result)
    }

    private fun applyCommandResult(displayUserText: String, result: CommandResult) {
        if (result.stopSpeakingOnly) {
            stopSpeakingInternal()
            AssistantStateBus.addDialogue(displayUserText, "Озвучка остановлена.")
            if (!pausedForExternalDialog) {
                speechEngine?.start()
            }
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
        } else if (!pausedForExternalDialog && !isSpeaking) {
            speechEngine?.start()
        }
    }

    private fun stopSpeakingInternal() {
        mainHandler.removeCallbacks(ttsFallbackRunnable)
        isSpeaking = false
        pendingStopAfterSpeak = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    private fun speak(text: String, stopAfter: Boolean = false) {
        mainHandler.removeCallbacks(ttsFallbackRunnable)
        speechEngine?.pause()

        AssistantStateBus.updateServiceState(
            running = true,
            listening = false,
            status = "🔊 Нелли: $text"
        )

        if (!ttsReady || tts == null) {
            if (stopAfter) {
                mainHandler.postDelayed({ stopSelf() }, 400L)
            } else if (!pausedForExternalDialog) {
                mainHandler.postDelayed({
                    if (!isDestroyed && !pausedForExternalDialog) {
                        speechEngine?.start()
                    }
                }, 500L)
            }
            return
        }

        isSpeaking = true
        pendingStopAfterSpeak = stopAfter
        val utteranceId = if (stopAfter) "utt_stop" else "utt_${System.currentTimeMillis()}"
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res == TextToSpeech.ERROR) {
            isSpeaking = false
            pendingStopAfterSpeak = false
            if (stopAfter) {
                stopSelf()
            } else if (!pausedForExternalDialog) {
                speechEngine?.start()
            }
            return
        }

        val timeoutMs = (text.length * 95L).coerceIn(3500L, 20_000L)
        mainHandler.postDelayed(ttsFallbackRunnable, timeoutMs)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                engine.language = Locale("ru", "RU")
                selectFemaleRussianVoice(engine)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        mainHandler.removeCallbacks(ttsFallbackRunnable)
                        isSpeaking = false
                        val shouldStop = pendingStopAfterSpeak || utteranceId == "utt_stop"
                        pendingStopAfterSpeak = false
                        if (shouldStop) {
                            stopSelf()
                        } else if (!isDestroyed && !pausedForExternalDialog) {
                            speechEngine?.start()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        mainHandler.removeCallbacks(ttsFallbackRunnable)
                        isSpeaking = false
                        val shouldStop = pendingStopAfterSpeak || utteranceId == "utt_stop"
                        pendingStopAfterSpeak = false
                        if (shouldStop) {
                            stopSelf()
                        } else if (!isDestroyed && !pausedForExternalDialog) {
                            speechEngine?.start()
                        }
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
        speechEngine?.destroy()
        speechEngine = null
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
        const val ACTION_PAUSE_FOR_DIALOG = "com.example.project12.ACTION_PAUSE_FOR_DIALOG"
        const val ACTION_RESUME_LISTENING = "com.example.project12.ACTION_RESUME_LISTENING"
        const val ACTION_ACTIVITY_PAUSED = "com.example.project12.ACTION_ACTIVITY_PAUSED"
        const val ACTION_EXECUTE_TEXT = "com.example.project12.ACTION_EXECUTE_TEXT"
        const val ACTION_STOP_SPEAKING = "com.example.project12.ACTION_STOP_SPEAKING"
        const val ACTION_STOP_SERVICE = "com.example.project12.ACTION_STOP_SERVICE"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
        const val EXTRA_CANDIDATES_LIST = "extra_candidates_list"

        fun selectFemaleRussianVoice(engine: TextToSpeech) {
            try {
                val voices = engine.voices ?: return
                val ruVoices = voices.filter { v ->
                    v.locale.language.equals("ru", ignoreCase = true)
                }
                if (ruVoices.isEmpty()) return

                val preferredFemale = ruVoices.firstOrNull { v ->
                    val n = v.name.lowercase(Locale.US)
                    !v.isNetworkConnectionRequired &&
                        (n.contains("female") || n.contains("-ruf") || n.contains("-dfc")) &&
                        !n.contains("-rud") && !n.contains("-rjs")
                } ?: ruVoices.firstOrNull { v ->
                    val n = v.name.lowercase(Locale.US)
                    (n.contains("female") || n.contains("-ruf") || n.contains("-dfc")) &&
                        !n.contains("-rud") && !n.contains("-rjs")
                } ?: ruVoices.firstOrNull { v ->
                    val n = v.name.lowercase(Locale.US)
                    !v.isNetworkConnectionRequired && !n.contains("male") && !n.contains("-rud") && !n.contains("-rjs")
                }

                if (preferredFemale != null) {
                    engine.voice = preferredFemale
                }
            } catch (_: Exception) {
            }
        }
    }
}
