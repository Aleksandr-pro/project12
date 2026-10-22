package com.example.project12

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class DialogueEntry(
    val time: String,
    val userText: String,
    val assistantText: String
)

object AssistantStateBus {

    private const val PREFS_NAME = "assistant_prefs"
    private const val KEY_DIRECT_MODE = "direct_mode"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var isServiceRunning: Boolean = false
        private set

    @Volatile
    var isListeningNow: Boolean = false
        private set

    @Volatile
    var statusText: String = ""
        private set

    @Volatile
    var partialSpeech: String = ""
        private set

    @Volatile
    var micLevelPercent: Int = 0
        private set

    @Volatile
    var diagnosticInfo: String = ""
        private set

    @Volatile
    var directMode: Boolean = true
        private set

    @Volatile
    var tradingStatusText: String = ""
        private set

    @Volatile
    var lastSignalText: String = ""
        private set

    @Volatile
    private var activeActivityRef: WeakReference<Activity>? = null

    fun setActiveActivity(activity: Activity?) {
        activeActivityRef = if (activity != null) WeakReference(activity) else null
    }

    fun getActiveActivity(): Activity? = activeActivityRef?.get()

    private var lastRmsNotifyMs: Long = 0L
    private val historyList = CopyOnWriteArrayList<DialogueEntry>()

    val history: List<DialogueEntry>
        get() = historyList.toList()

    fun initPrefs(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        directMode = prefs.getBoolean(KEY_DIRECT_MODE, true)
    }

    fun setDirectMode(context: Context, enabled: Boolean) {
        directMode = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DIRECT_MODE, enabled)
            .apply()
        notifyListeners()
    }

    fun updateServiceState(
        running: Boolean,
        listening: Boolean,
        status: String,
        diagnostic: String? = null
    ) {
        isServiceRunning = running
        isListeningNow = listening
        statusText = status
        if (diagnostic != null) {
            diagnosticInfo = diagnostic
        }
        if (!listening) {
            partialSpeech = ""
            micLevelPercent = 0
        }
        notifyListeners()
    }

    fun updateDiagnostic(diagnostic: String) {
        diagnosticInfo = diagnostic
        notifyListeners()
    }

    fun updatePartialSpeech(partial: String) {
        partialSpeech = partial
        notifyListeners()
    }

    fun updateTradingStatus(status: String, signalText: String? = null) {
        tradingStatusText = status
        if (signalText != null) {
            lastSignalText = signalText
        }
        notifyListeners()
    }

    fun updateMicRms(rmsDb: Float) {
        // Android SpeechRecognizer rmsDb typically ranges from -2f to +12f
        val normalized = (((rmsDb + 2f) / 12f) * 100f).toInt().coerceIn(5, 100)
        micLevelPercent = normalized
        val now = System.currentTimeMillis()
        if (now - lastRmsNotifyMs >= 90L) {
            lastRmsNotifyMs = now
            notifyListeners()
        }
    }

    fun addDialogue(userText: String, assistantText: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        historyList.add(0, DialogueEntry(time, userText, assistantText))
        while (historyList.size > 30) {
            historyList.removeAt(historyList.size - 1)
        }
        partialSpeech = ""
        micLevelPercent = 0
        notifyListeners()
    }

    fun clearHistory() {
        historyList.clear()
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            for (listener in listeners) {
                listener.invoke()
            }
        }
    }
}
