package com.example.project12

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    var directMode: Boolean = true
        private set

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

    fun updateServiceState(running: Boolean, listening: Boolean, status: String) {
        isServiceRunning = running
        isListeningNow = listening
        statusText = status
        if (!listening) {
            partialSpeech = ""
        }
        notifyListeners()
    }

    fun updatePartialSpeech(partial: String) {
        partialSpeech = partial
        notifyListeners()
    }

    fun addDialogue(userText: String, assistantText: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        historyList.add(0, DialogueEntry(time, userText, assistantText))
        while (historyList.size > 30) {
            historyList.removeAt(historyList.size - 1)
        }
        partialSpeech = ""
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
