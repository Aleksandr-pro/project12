package com.example.project12

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.1 — Глобальный «чёрный ящик» Нелли.
 *
 * Если приложение когда-нибудь всё же упадёт, стек-трейс сохраняется в файл и в настройки.
 * При следующем открытии Нелли покажет ошибку прямо на экране с кнопкой «Скопировать» —
 * так проблему можно описать словами или прислать разработчику.
 */
class NellyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(thread, throwable)
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) {
            "?"
        }

        val report = buildString {
            append("Время: ")
            append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            append("\nПоток: ").append(thread.name)
            append("\nВерсия Нелли: ").append(versionName)
            append("\nМодель: ").append(android.os.Build.MANUFACTURER)
            append(" ").append(android.os.Build.MODEL)
            append("\nAndroid: ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")")
            append("\n\n")
            append(throwable.stackTraceToString())
        }

        runCatching { File(filesDir, "last_crash.txt").writeText(report) }

        getSharedPreferences("assistant_prefs", MODE_PRIVATE)
            .edit()
            .putString("last_crash", report)
            .apply()
    }
}
