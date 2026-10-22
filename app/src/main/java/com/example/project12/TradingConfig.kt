package com.example.project12

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.0 — Настройки и журнал трейдинга Нелли.
 *
 * Безопасные значения по умолчанию:
 *  • демо-счёт — ВКЛ (реальными деньгами торгуем только после явного включения в настройках);
 *  • автоторговля — ВЫКЛ (сначала пользователь просит анализ);
 *  • подтверждение голосом — ВКЛ;
 *  • не больше 5 сделок в день.
 */

data class TradeRecord(
    val timeMs: Long,
    val assetTitle: String,
    val direction: String,
    val confidence: Int,
    val stake: Double,
    val demo: Boolean,
    val status: String
) {
    fun timeText(): String =
        SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU")).format(Date(timeMs))

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", timeMs)
        put("a", assetTitle)
        put("d", direction)
        put("c", confidence)
        put("s", stake)
        put("demo", demo)
        put("st", status)
    }

    companion object {
        fun fromJson(o: JSONObject): TradeRecord = TradeRecord(
            timeMs = o.optLong("t", 0L),
            assetTitle = o.optString("a", "—"),
            direction = o.optString("d", "—"),
            confidence = o.optInt("c", 0),
            stake = o.optDouble("s", 0.0),
            demo = o.optBoolean("demo", true),
            status = o.optString("st", "ожидание")
        )
    }
}

object TradingSettings {

    private const val PREFS = "nelly_trading"
    private const val KEY_JOURNAL = "journal"
    private const val MAX_JOURNAL = 40

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences? = prefs

    var assetId: String
        get() = p()?.getString("asset_id", "eurusd") ?: "eurusd"
        set(value) {
            p()?.edit()?.putString("asset_id", value)?.apply()
        }

    var expiryMinutes: Int
        get() = p()?.getInt("expiry_minutes", 15) ?: 15
        set(value) {
            p()?.edit()?.putInt("expiry_minutes", value.coerceIn(1, 60))?.apply()
        }

    var autoTrade: Boolean
        get() = p()?.getBoolean("auto_trade", false) ?: false
        set(value) {
            p()?.edit()?.putBoolean("auto_trade", value)?.apply()
        }

    var autoConfirm: Boolean
        get() = p()?.getBoolean("auto_confirm", false) ?: false
        set(value) {
            p()?.edit()?.putBoolean("auto_confirm", value)?.apply()
        }

    var demoOnly: Boolean
        get() = p()?.getBoolean("demo_only", true) ?: true
        set(value) {
            p()?.edit()?.putBoolean("demo_only", value)?.apply()
        }

    var stake: Double
        get() = p()?.getFloat("stake", 10f)?.toDouble() ?: 10.0
        set(value) {
            p()?.edit()?.putFloat("stake", value.toFloat().coerceIn(1f, 100_000f))?.apply()
        }

    var minConfidence: Int
        get() = p()?.getInt("min_confidence", 68) ?: 68
        set(value) {
            p()?.edit()?.putInt("min_confidence", value.coerceIn(50, 95))?.apply()
        }

    var maxTradesPerDay: Int
        get() = p()?.getInt("max_trades_day", 5) ?: 5
        set(value) {
            p()?.edit()?.putInt("max_trades_day", value.coerceIn(1, 50))?.apply()
        }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun tradesToday(): Int {
        val prefsLocal = p() ?: return 0
        val storedDay = prefsLocal.getString("trades_day", "") ?: ""
        if (storedDay != todayKey()) return 0
        return prefsLocal.getInt("trades_count", 0)
    }

    private fun registerTradeToday() {
        val prefsLocal = p() ?: return
        val day = todayKey()
        val current = if ((prefsLocal.getString("trades_day", "") ?: "") == day) {
            prefsLocal.getInt("trades_count", 0)
        } else {
            0
        }
        prefsLocal.edit()
            .putString("trades_day", day)
            .putInt("trades_count", current + 1)
            .apply()
    }

    fun canTradeNow(): Pair<Boolean, String> {
        val today = tradesToday()
        if (today >= maxTradesPerDay) {
            return false to "Дневной лимит сделок исчерпан ($today из $maxTradesPerDay). Отдыхаем до завтра."
        }
        return true to "Можно торговать."
    }

    // ------------------------------------------------------------- журнал

    fun journal(): List<TradeRecord> {
        val raw = p()?.getString(KEY_JOURNAL, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(TradeRecord.fromJson(it)) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun journalText(): String {
        val list = journal()
        if (list.isEmpty()) return "Журнал пуст — сделок ещё не было."
        return list.take(10).joinToString("\n") { r ->
            val dir = if (r.direction == "UP") "вверх ↑" else "вниз ↓"
            val acc = if (r.demo) "демо" else "реальный"
            "${r.timeText()} • ${r.assetTitle} • $dir • ${r.confidence}% • $acc • ${r.status}"
        }
    }

    fun addRecord(record: TradeRecord) {
        val list = ArrayList(journal())
        list.add(0, record)
        while (list.size > MAX_JOURNAL) list.removeAt(list.size - 1)
        saveJournal(list)
        registerTradeToday()
    }

    fun markLastOutcome(outcome: String) {
        val list = ArrayList(journal())
        if (list.isEmpty()) return
        val first = list[0]
        list[0] = first.copy(status = outcome)
        saveJournal(list)
    }

    fun clearJournal() {
        p()?.edit()?.remove(KEY_JOURNAL)?.apply()
    }

    private fun saveJournal(list: List<TradeRecord>) {
        val arr = JSONArray()
        for (r in list) arr.put(r.toJson())
        p()?.edit()?.putString(KEY_JOURNAL, arr.toString())?.apply()
    }
}

/** Озвучка событий трейдинга: через запущенную Нелли, либо уведомлением. */
object NellyAnnouncer {

    fun say(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        AssistantStateBus.addDialogue("📈 Трейдинг", clean)
        AssistantStateBus.updateTradingStatus(clean)

        if (AssistantStateBus.isServiceRunning) {
            try {
                val intent = android.content.Intent(context, VoiceAssistantService::class.java).apply {
                    action = VoiceAssistantService.ACTION_SPEAK_TEXT
                    putExtra(VoiceAssistantService.EXTRA_COMMAND_TEXT, clean)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                return
            } catch (_: Exception) {
            }
        }
        notify(context, clean)
    }

    private fun notify(context: Context, text: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "nelly_trading_alerts",
                    "Сигналы Нелли",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Сигналы рынка и автоторговля" }
                manager.createNotificationChannel(channel)
            }
            val openIntent = android.content.Intent(context, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = android.app.PendingIntent.getActivity(
                context,
                42,
                openIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, "nelly_trading_alerts")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Нелли • рынок")
                .setContentText(text.take(140))
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(4242, notification)
        } catch (_: Exception) {
        }
    }
}
