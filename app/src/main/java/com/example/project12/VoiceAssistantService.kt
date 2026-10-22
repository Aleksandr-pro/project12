package com.example.project12

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.*
import kotlin.math.sqrt

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var wakeMonitor: WakeWordMonitor

    private var isListening = false

    private val CHANNEL_ID = "voice_assistant_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.getInstance(this)
        createNotificationChannel()

        tts = TextToSpeech(this, this)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsDb: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    isListening = false
                    speak("Произошла ошибка распознавания. Продолжаю слушать.")
                    startWakeMonitoring()
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    handleCommand(text)
                    startWakeMonitoring()
                }
                override fun onPartialResults(results: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            Toast.makeText(this, R.string.error_no_recognizer, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startWakeMonitoring()
        return START_STICKY
    }

    private fun startWakeMonitoring() {
        wakeMonitor = WakeWordMonitor(
            onWake = {
                isListening = true
                stopWakeMonitoring()
                speak("Да, я слушаю")
                startListening()
            }
        )
        wakeMonitor.start()
    }

    private fun stopWakeMonitoring() {
        wakeMonitor?.stopMonitoring()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите команду")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        speechRecognizer.startListening(intent)
    }

    private fun handleCommand(text: String) {
        val lower = text.lowercase().trim()
        val response = when {
            lower.contains("привет") || lower.contains("здравствуй") -> "Привет! Чем могу помочь?"
            lower.contains("как дела") -> "У меня всё хорошо, спасибо. А у вас?"
            lower.contains("время") -> {
                val now = Date()
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                "Сейчас $time"
            }
            lower.contains("дата") -> {
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
                stopSelf()
                "До свидания!"
            }
            else -> getString(R.string.cmd_unknown)
        }
        speak(response)
    }

    private fun openSettings() {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_utterance")
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
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale("ru", "RU"))
        }
    }

    override fun onDestroy() {
        stopWakeMonitoring()
        speechRecognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Порог энергии звука для срабатывания пробуждения (подбирается экспериментально)
        const val ENERGY_THRESHOLD = 1200.0
    }
}