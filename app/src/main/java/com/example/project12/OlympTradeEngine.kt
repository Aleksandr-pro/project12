package com.example.project12

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * v5.0 — Управление приложением OlympTrade через спецвозможности.
 *
 * Что делает:
 *  • находит и открывает установленный OlympTrade (com.ticno.olymptrade и совместимые);
 *  • проверяет, что открыт именно OlympTrade и что это демо/реальный счёт;
 *  • переключает актив (EUR/USD, золото, биткоин и т.д.);
 *  • выставляет время сделки 15 минут;
 *  • нажимает «Вверх» или «Вниз» по сигналу рыночного движка;
 *  • ведёт журнал сделок и соблюдает дневной лимит.
 *
 * ВАЖНО: приложение не гарантирует прибыль. Это автоматизация нажатий по техническому сигналу.
 * Торговля на реальные деньги — только осознанное решение пользователя.
 */
object OlympTradeEngine {

    @Volatile
    private var pendingSignal: MarketSignal? = null

    @Volatile
    private var pendingUntilMs: Long = 0L

    // ------------------------------------------------------------ установка

    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in NellyAccessibilityService.OLYMP_PACKAGES) {
            try {
                if (pm.getLaunchIntentForPackage(pkg) != null) return pkg
            } catch (_: Exception) {
            }
        }
        // Нечёткий поиск по названию приложения (на случай переименованного пакета)
        return try {
            @Suppress("DEPRECATION")
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.firstOrNull { info ->
                val pkg = info.packageName.lowercase(Locale.US)
                val label = try {
                    pm.getApplicationLabel(info).toString().lowercase(Locale("ru", "RU"))
                } catch (_: Exception) {
                    ""
                }
                pkg.contains("olymptrade") || pkg.contains("olymp.trade") ||
                    label.contains("olymptrade") || label.contains("olymp trade") ||
                    label.contains("олимп трейд") || label.contains("олимптрейд")
            }?.packageName
        } catch (_: Exception) {
            null
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    fun openApp(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launch)
            true
        } catch (_: Exception) {
            try {
                val market = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=${installedPackage(context) ?: "com.ticno.olymptrade"}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(market)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    // ------------------------------------------------------ подтверждение

    fun requestConfirmation(signal: MarketSignal): String {
        pendingSignal = signal
        pendingUntilMs = System.currentTimeMillis() + 180_000L
        val action = if (signal.direction == TradeDirection.UP) "ВВЕРХ" else "ВНИЗ"
        val expiry = TradingSettings.expiryMinutes
        val account = if (TradingSettings.demoOnly) "демо-счёт" else "счёт"
        return "Готова открыть сделку $action на $expiry минут на $account. " +
            "Сигнал ${signal.asset.titleRu}, уверенность ${signal.confidence}%. " +
            "Скажите «подтверждаю сделку» — и я нажму кнопку в OlympTrade. Или скажите «отмена»."
    }

    fun hasFreshPendingSignal(): Boolean {
        val s = pendingSignal ?: return false
        if (System.currentTimeMillis() > pendingUntilMs) {
            pendingSignal = null
            return false
        }
        return s.actionable
    }

    fun clearPendingSignal() {
        pendingSignal = null
        pendingUntilMs = 0L
    }

    fun confirmPending(context: Context, onResult: (Boolean, String) -> Unit): Boolean {
        val signal = pendingSignal ?: return false
        if (System.currentTimeMillis() > pendingUntilMs) {
            pendingSignal = null
            return false
        }
        pendingSignal = null
        executeTrade(context, signal, onResult)
        return true
    }

    // -------------------------------------------------------- исполнение

    fun executeTrade(context: Context, signal: MarketSignal, onResult: (Boolean, String) -> Unit) {
        TradingSettings.init(context.applicationContext)
        val appCtx = context.applicationContext

        val pkg = installedPackage(appCtx)
        if (pkg == null) {
            onResult(
                false,
                "Не нашла приложение OlympTrade на телефоне. Установите его из Play Маркета, " +
                    "войдите в аккаунт и скажите мне «открой Олимп Трейд»."
            )
            return
        }
        if (!NellyAccessibilityService.isEnabled(appCtx)) {
            onResult(
                false,
                "Чтобы я нажимала кнопки в OlympTrade, включите один раз «⚡ Управление экраном» " +
                    "на главном экране Нелли (Спецвозможности)."
            )
            return
        }

        val limitCheck = TradingSettings.canTradeNow()
        if (!limitCheck.first) {
            onResult(false, limitCheck.second)
            return
        }

        if (signal.direction == TradeDirection.NONE) {
            onResult(false, "Сейчас нет чёткого сигнала — входить в сделку не буду.")
            return
        }

        TradeFlow(appCtx, signal, onResult).start()
    }

    private class TradeFlow(
        private val context: Context,
        private val signal: MarketSignal,
        private val onResult: (Boolean, String) -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var assetAttempts = 0
        private var expiryAttempts = 0
        private var reopenAttempts = 0
        private var finished = false

        fun start() {
            if (NellyAccessibilityService.isOlympForeground()) {
                handler.postDelayed({ checkScreen() }, 700L)
            } else {
                if (!OlympTradeEngine.openApp(context)) {
                    finish(
                        false,
                        "Не удалось открыть OlympTrade. Откройте приложение вручную и повторите команду."
                    )
                    return
                }
                handler.postDelayed({ checkScreen() }, 2600L)
            }
        }

        private fun checkScreen() {
            if (finished) return
            val snapshot = NellyAccessibilityService.olympSnapshot(context)

            if (!snapshot.isForeground) {
                if (reopenAttempts < 1) {
                    reopenAttempts++
                    OlympTradeEngine.openApp(context)
                    handler.postDelayed({ checkScreen() }, 2600L)
                } else {
                    finish(false, "OlympTrade не открылся на экране. Проверьте, что приложение запускается.")
                }
                return
            }

            if (TradingSettings.demoOnly && snapshot.isRealAccount && !snapshot.isDemoAccount) {
                finish(
                    false,
                    "Сейчас в OlympTrade открыт РЕАЛЬНЫЙ счёт, а у меня включён демо-режим. " +
                        "Переключите счёт на демо или выключите демо-режим в карточке трейдинга."
                )
                return
            }

            val detected = snapshot.detectedAsset
            if (detected == null || assetMatches(detected)) {
                ensureExpiry()
            } else {
                ensureAsset()
            }
        }

        private fun ensureAsset() {
            if (finished) return
            if (assetAttempts >= 3) {
                finish(
                    false,
                    "Не смогла переключить актив на ${signal.asset.titleRu}. " +
                        "Откройте нужный актив в OlympTrade вручную, и я сразу нажму кнопку сделки."
                )
                return
            }
            assetAttempts++

            val query = signal.asset.olymptradeNames.first()
            if (NellyAccessibilityService.olympTypeAssetQuery(query)) {
                handler.postDelayed({ clickAssetResult() }, 1200L)
                return
            }

            if (NellyAccessibilityService.olympOpenAssetSelector()) {
                handler.postDelayed({
                    val typed = NellyAccessibilityService.olympTypeAssetQuery(query)
                    handler.postDelayed({ clickAssetResult() }, if (typed) 1200L else 400L)
                }, 1000L)
            } else {
                handler.postDelayed({ ensureAsset() }, 900L)
            }
        }

        private fun clickAssetResult() {
            if (finished) return
            if (NellyAccessibilityService.olympClickAssetResult(signal.asset)) {
                handler.postDelayed({ checkScreen() }, 1000L)
            } else {
                handler.postDelayed({ ensureAsset() }, 800L)
            }
        }

        private fun ensureExpiry() {
            if (finished) return
            val snapshot = NellyAccessibilityService.olympSnapshot(context)
            if (snapshot.hasExpiry15 && TradingSettings.expiryMinutes == 15) {
                pressButton()
                return
            }
            if (expiryAttempts >= 2) {
                // Не смогли выставить время — жмём то, что есть, но сообщаем об этом
                pressButton(extraNote = "Время сделки выставить не удалось — проверьте экспирацию вручную.")
                return
            }
            expiryAttempts++
            NellyAccessibilityService.olympSetExpiry(TradingSettings.expiryMinutes)
            handler.postDelayed({ ensureExpiry() }, 900L)
        }

        private fun pressButton(extraNote: String? = null) {
            if (finished) return
            val (ok, msg) = NellyAccessibilityService.olympPressTrade(signal.direction)
            if (!ok) {
                finish(false, msg)
                return
            }

            val demo = !(NellyAccessibilityService.olympSnapshot(context).isRealAccount &&
                !NellyAccessibilityService.olympSnapshot(context).isDemoAccount)
            val dirText = if (signal.direction == TradeDirection.UP) "ВВЕРХ" else "ВНИЗ"

            TradingSettings.addRecord(
                TradeRecord(
                    timeMs = System.currentTimeMillis(),
                    assetTitle = signal.asset.titleRu,
                    direction = if (signal.direction == TradeDirection.UP) "UP" else "DOWN",
                    confidence = signal.confidence,
                    stake = TradingSettings.stake,
                    demo = demo,
                    status = "ожидание"
                )
            )

            val account = if (demo) "демо-счёт" else "РЕАЛЬНЫЙ счёт"
            val extra = if (extraNote != null) " $extraNote" else ""
            finish(
                true,
                "$msg Сделка $dirText на ${TradingSettings.expiryMinutes} мин, $account. " +
                    "Уверенность сигнала ${signal.confidence}%.$extra"
            )
        }

        private fun finish(ok: Boolean, message: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            onResult(ok, message)
        }

        private fun assetMatches(detected: String): Boolean {
            val compact = detected.lowercase(Locale("ru", "RU"))
                .replace(Regex("[^a-zа-я0-9]"), "")
            return signal.asset.olymptradeNames.any { name ->
                val n = name.lowercase(Locale("ru", "RU")).replace(Regex("[^a-zа-я0-9]"), "")
                n.length >= 4 && (compact == n || compact.contains(n) || n.contains(compact))
            }
        }
    }

    // ------------------------------------------------------------ статус

    fun describeStatus(context: Context): String {
        TradingSettings.init(context.applicationContext)
        val pkg = installedPackage(context)
            ?: return "OlympTrade на телефоне не установлен. Установите приложение из Play Маркета и скажите «открой Олимп Трейд»."
        val enabled = NellyAccessibilityService.isEnabled(context)
        val snapshot = if (enabled) NellyAccessibilityService.olympSnapshot(context) else null
        val sb = StringBuilder()
        sb.append("OlympTrade установлен ($pkg). ")
        sb.append(if (enabled) "Управление экраном включено. " else "Управление экраном ВЫКЛЮЧЕНО — включите кнопкой на главном экране. ")
        sb.append("Актив для анализа: ").append(MarketAssets.byId(TradingSettings.assetId).titleRu).append(". ")
        sb.append("Демо-режим: ").append(if (TradingSettings.demoOnly) "да" else "НЕТ, реальные деньги").append(". ")
        sb.append("Автоторговля: ").append(if (TradingSettings.autoTrade) "включена" else "выключена").append(". ")
        sb.append("Сделок сегодня: ").append(TradingSettings.tradesToday())
            .append(" из ").append(TradingSettings.maxTradesPerDay).append(". ")
        if (snapshot != null) {
            if (!snapshot.isForeground) {
                sb.append("Сейчас OlympTrade не открыт на экране. ")
            } else {
                sb.append("Сейчас открыт OlympTrade")
                snapshot.detectedAsset?.let { sb.append(", актив ").append(it) }
                if (snapshot.isDemoAccount) sb.append(", демо-счёт")
                if (snapshot.isRealAccount && !snapshot.isDemoAccount) sb.append(", РЕАЛЬНЫЙ счёт")
                sb.append(". ")
            }
        }
        return sb.toString().trim()
    }

    fun readCurrentScreen(context: Context): String {
        val snapshot = NellyAccessibilityService.olympSnapshot(context)
        if (!snapshot.isForeground) return "OlympTrade сейчас не на экране."
        val texts = snapshot.visibleTexts.take(8)
        return "Экран OlympTrade. " +
            (snapshot.detectedAsset?.let { "Актив: $it. " } ?: "") +
            (if (snapshot.hasExpiry15) "Экспирация 15 минут выбрана. " else "") +
            "Кнопки сделки: ${snapshot.tradeButtonLabels.joinToString(", ").ifEmpty { "не распознаны" }}. " +
            "Надписи: ${texts.joinToString(", ")}."
    }
}

/**
 * Автоторговля: каждые ~30 секунд проверяет рынок на 15-минутном таймфрейме.
 * При включённой автоторговле входит в сделку (с подтверждением или без — по настройке).
 */
object TradingCoordinator {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var isRunning: Boolean = false
        private set

    private var appContext: Context? = null
    private var lastActedCandleSec: Long = 0L
    private var lastAnnouncedCandleSec: Long = 0L
    private var lastResultText: String = ""

    private val scanRunnable = Runnable {
        if (isRunning) performScan(announceQuietSignals = false)
    }

    fun start(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        TradingSettings.init(appCtx)
        isRunning = true
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed({ performScan(announceQuietSignals = true) }, 800L)
        NellyAnnouncer.say(
            appCtx,
            "Включаю автоторговлю. Актив ${MarketAssets.byId(TradingSettings.assetId).titleRu}, " +
                "таймфрейм ${TradingSettings.expiryMinutes} минут, " +
                if (TradingSettings.autoConfirm) "вход без подтверждения." else "вход после слова «подтверждаю»."
        )
        AssistantStateBus.updateTradingStatus("Автоторговля включена")
    }

    fun stop(context: Context) {
        isRunning = false
        handler.removeCallbacks(scanRunnable)
        AssistantStateBus.updateTradingStatus("Автоторговля выключена")
        NellyAnnouncer.say(context, "Автоторговля выключена.")
    }

    /** Разовый анализ (кнопка «Анализ сейчас» или голосовая команда). */
    fun analyzeOnce(context: Context, announce: Boolean = true) {
        val appCtx = context.applicationContext
        appContext = appCtx
        TradingSettings.init(appCtx)
        AssistantStateBus.updateTradingStatus("Анализирую рынок…")
        val asset = MarketAssets.byId(TradingSettings.assetId)
        MarketAnalysisEngine.analyzeAsync(asset, TradingSettings.expiryMinutes) { signal, error ->
            if (signal == null) {
                val text = error ?: "Не удалось получить данные рынка."
                AssistantStateBus.updateTradingStatus(text)
                if (announce) NellyAnnouncer.say(appCtx, text)
                return@analyzeAsync
            }
            val summary = signal.shortSummaryRu()
            AssistantStateBus.updateTradingStatus(summary, summary)
            lastResultText = summary
            if (announce) {
                NellyAnnouncer.say(appCtx, MarketAnalysisEngine.buildSpeechRu(signal))
            }
        }
    }

    fun lastSignalText(): String = lastResultText

    private fun scheduleNext(delayMs: Long) {
        if (!isRunning) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMs.coerceIn(8_000L, 90_000L))
    }

    private fun performScan(announceQuietSignals: Boolean) {
        if (!isRunning) return
        val ctx = appContext ?: return
        val asset = MarketAssets.byId(TradingSettings.assetId)
        MarketAnalysisEngine.analyzeAsync(asset, TradingSettings.expiryMinutes) { signal, error ->
            if (!isRunning) return@analyzeAsync
            if (signal == null) {
                AssistantStateBus.updateTradingStatus(error ?: "Нет данных рынка")
                scheduleNext(45_000L)
                return@analyzeAsync
            }

            val summary = signal.shortSummaryRu()
            AssistantStateBus.updateTradingStatus(summary, summary)
            lastResultText = summary

            if (signal.actionable && signal.confidence >= TradingSettings.minConfidence) {
                if (signal.candleOpenTimeSec != lastActedCandleSec) {
                    lastActedCandleSec = signal.candleOpenTimeSec
                    handleActionableSignal(ctx, signal)
                }
            } else if (announceQuietSignals || signal.candleOpenTimeSec != lastAnnouncedCandleSec) {
                lastAnnouncedCandleSec = signal.candleOpenTimeSec
                if (announceQuietSignals) {
                    NellyAnnouncer.say(ctx, MarketAnalysisEngine.buildSpeechRu(signal))
                }
            }

            val secToNext = signal.secondsToNextCandle
            val delay = when {
                secToNext in 1..25 -> 12_000L
                secToNext in 26..75 -> 20_000L
                else -> 30_000L
            }
            scheduleNext(delay)
        }
    }

    private fun handleActionableSignal(context: Context, signal: MarketSignal) {
        if (!TradingSettings.autoTrade) {
            NellyAnnouncer.say(
                context,
                "Появился сигнал: ${signal.asset.titleRu}, " +
                    if (signal.direction == TradeDirection.UP) "вверх" else "вниз" +
                        ", уверенность ${signal.confidence} процентов. " +
                    "Автоторговля выключена. Скажите «купи вверх» или «купи вниз», чтобы войти."
            )
            return
        }

        val limit = TradingSettings.canTradeNow()
        if (!limit.first) {
            NellyAnnouncer.say(context, limit.second)
            return
        }

        if (TradingSettings.autoConfirm) {
            OlympTradeEngine.executeTrade(context, signal) { ok, message ->
                NellyAnnouncer.say(context, message)
                if (!ok) AssistantStateBus.updateTradingStatus(message)
            }
        } else {
            val prompt = OlympTradeEngine.requestConfirmation(signal)
            NellyAnnouncer.say(context, prompt)
        }
    }
}
