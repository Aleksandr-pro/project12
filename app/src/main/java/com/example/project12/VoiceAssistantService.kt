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
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeMonitor: WakeWordMonitor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListening = false
    private var isDestroyed = false

    private val CHANNEL_ID = "voice_assistant_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        tts = TextToSpeech(this, this)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)?.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsDb: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        isListening = false
                        if (!isDestroyed) {
                            startWakeMonitoring()
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            handleCommand(text)
                        }
                        if (!isDestroyed) {
                            mainHandler.postDelayed({
                                if (!isDestroyed && !isListening) {
                                    startWakeMonitoring()
                                }
                            }, 1500)
                        }
                    }
                    override fun onPartialResults(results: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            Toast.makeText(this, R.string.error_no_recognizer, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        startWakeMonitoring()
        return START_STICKY
    }

    private fun startWakeMonitoring() {
        if (isDestroyed || isListening) return
        stopWakeMonitoring()
        wakeMonitor = WakeWordMonitor(
            onWake = {
                mainHandler.post {
                    if (!isDestroyed && !isListening) {
                        isListening = true
                        stopWakeMonitoring()
                        speak("Да, я слушаю")
                        mainHandler.postDelayed({
                            if (!isDestroyed) {
                                startListening()
                            }
                        }, 700)
                    }
                }
            }
        ).also { it.start() }
    }

    private fun stopWakeMonitoring() {
        wakeMonitor?.stopMonitoring()
        wakeMonitor = null
    }

    private fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            isListening = false
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите команду")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        recognizer.startListening(intent)
    }

    private fun handleCommand(text: String) {
        val lower = text.lowercase(Locale("ru", "RU")).trim()
        val response = when {
            lower.contains("привет") || lower.contains("здравствуй") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "У меня всё хорошо, спасибо. А у вас?"
            lower.contains("время") || lower.contains("час") -> {
                val now = Date()
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                "Сейчас $time"
            }
            lower.contains("дата") || lower.contains("число") -> {
                val now = Date()
                val date = SimpleDateFormat("dd MMMM yyyy", Locale("ru")).format(now)
                "Сегодня $date"
            }
            lower.contains("погода") -> "Я пока не подключён к службе погоды. Скажите, в каком городе вы находитесь?"
            lower.contains("открой настройки") || lower.contains("настройки") -> {
                openSettings()
                "Открываю настройки"
            }
            lower.contains("стоп") || lower.contains("выключись") || lower.contains("заверши") -> {
                speak("До свидания!")
                mainHandler.postDelayed({ stopSelf() }, 1000)
                return
            }
            else -> getString(R.string.cmd_unknown)
        }
        speak(response)
    }

    private fun openSettings() {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_utterance")
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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopWakeMonitoring()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ENERGY_THRESHOLD = 1200.0
    }
}
