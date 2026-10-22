package com.example.project12

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v5.0 — Рыночный аналитический движок Нелли.
 *
 * Берёт РЕАЛЬНЫЕ свечи (15 минут) с публичных источников (Yahoo Finance, при сбое — Binance),
 * считает индикаторы (EMA, RSI, MACD, Bollinger, Stochastic, ATR, уровни, свечные паттерны,
 * эффективность тренда) и выдаёт взвешенный сигнал ВВЕРХ / ВНИЗ / НЕТ ВХОДА.
 *
 * ВАЖНО: это техническая оценка сходимости индикаторов, а не гарантия прибыли.
 * Ни один индикатор не предсказывает рынок на 100%. Торгуйте только теми деньгами,
 * потерю которых можете себе позволить.
 */

enum class TradeDirection { UP, DOWN, NONE }

data class Candle(
    val timeSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
) {
    val body: Double get() = close - open
    val range: Double get() = max(high - low, 1e-9)
    val upperWick: Double get() = high - max(open, close)
    val lowerWick: Double get() = min(open, close) - low
    val isBull: Boolean get() = close >= open
    val bodyRatio: Double get() = abs(body) / range
}

data class MarketAsset(
    val id: String,
    val titleRu: String,
    val yahooSymbol: String,
    val binanceSymbol: String?,
    val olymptradeNames: List<String>,
    val digits: Int,
    val kind: String
)

object MarketAssets {

    val ALL: List<MarketAsset> = listOf(
        MarketAsset(
            "eurusd", "EUR/USD (евро/доллар)", "EURUSD=X", null,
            listOf("EUR/USD", "EURUSD", "евро доллар", "евро/доллар"), 5, "Валюта"
        ),
        MarketAsset(
            "gbpusd", "GBP/USD (фунт/доллар)", "GBPUSD=X", null,
            listOf("GBP/USD", "GBPUSD", "фунт доллар", "фунт/доллар"), 5, "Валюта"
        ),
        MarketAsset(
            "usdjpy", "USD/JPY (доллар/иена)", "USDJPY=X", null,
            listOf("USD/JPY", "USDJPY", "доллар иена", "доллар/иена"), 3, "Валюта"
        ),
        MarketAsset(
            "audusd", "AUD/USD (австралийский доллар)", "AUDUSD=X", null,
            listOf("AUD/USD", "AUDUSD", "австралийский доллар"), 5, "Валюта"
        ),
        MarketAsset(
            "usdcad", "USD/CAD (доллар/канадский)", "USDCAD=X", null,
            listOf("USD/CAD", "USDCAD", "канадский доллар"), 5, "Валюта"
        ),
        MarketAsset(
            "usdchf", "USD/CHF (доллар/франк)", "USDCHF=X", null,
            listOf("USD/CHF", "USDCHF", "швейцарский франк"), 5, "Валюта"
        ),
        MarketAsset(
            "eurjpy", "EUR/JPY (евро/иена)", "EURJPY=X", null,
            listOf("EUR/JPY", "EURJPY", "евро иена"), 3, "Валюта"
        ),
        MarketAsset(
            "xauusd", "Золото (Gold / XAUUSD)", "GC=F", null,
            listOf("XAU/USD", "XAUUSD", "GOLD", "золото"), 2, "Металлы"
        ),
        MarketAsset(
            "xagusd", "Серебро (Silver)", "SI=F", null,
            listOf("XAG/USD", "SILVER", "серебро"), 3, "Металлы"
        ),
        MarketAsset(
            "btcusd", "Bitcoin (BTC/USD)", "BTC-USD", "BTCUSDT",
            listOf("BTC/USD", "BTCUSD", "BITCOIN", "биткоин", "биткойн"), 1, "Криптовалюта"
        ),
        MarketAsset(
            "ethusd", "Ethereum (ETH/USD)", "ETH-USD", "ETHUSDT",
            listOf("ETH/USD", "ETHUSD", "ETHEREUM", "эфириум", "эфир"), 2, "Криптовалюта"
        ),
        MarketAsset(
            "usoil", "Нефть Brent (Oil)", "BZ=F", null,
            listOf("BRENT", "OIL", "нефть", "брент"), 2, "Товары"
        ),
        MarketAsset(
            "sp500", "S&P 500 (индекс)", "^GSPC", null,
            listOf("S&P 500", "SP500", "S&P500", "индекс S&P"), 2, "Индексы"
        )
    )

    fun byId(id: String?): MarketAsset =
        ALL.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL.first()

    fun resolve(raw: String): MarketAsset? {
        val q = raw.lowercase(Locale("ru", "RU")).replace('ё', 'е').trim()
        if (q.isEmpty()) return null
        ALL.firstOrNull { a ->
            a.id == q ||
                a.titleRu.lowercase(Locale("ru", "RU")).contains(q) ||
                a.yahooSymbol.lowercase(Locale.US).contains(q) ||
                a.olymptradeNames.any { n ->
                    val nl = n.lowercase(Locale("ru", "RU")).replace('ё', 'е')
                    nl == q || q.contains(nl) || nl.contains(q)
                }
        }?.let { return it }

        // «евро», «фунт», «золото», «биткоин», «нефть» — нечёткое узнавание
        return ALL.minByOrNull { a ->
            val best = a.olymptradeNames.maxOf { n ->
                PhoneticSimilarity.wordSimilarity(q, n.lowercase(Locale("ru", "RU")))
            }
            1.0 - best
        }?.takeIf { a ->
            a.olymptradeNames.any { n ->
                PhoneticSimilarity.wordSimilarity(q, n.lowercase(Locale("ru", "RU"))) >= 0.62
            }
        }
    }
}

data class MarketSignal(
    val asset: MarketAsset,
    val direction: TradeDirection,
    val confidence: Int,
    val price: Double,
    val priceText: String,
    val reasons: List<String>,
    val warnings: List<String>,
    val rsi: Double,
    val atr: Double,
    val atrPercent: Double,
    val trendText: String,
    val candleOpenTimeSec: Long,
    val secondsToNextCandle: Long,
    val timeframeMinutes: Int,
    val candlesAnalyzed: Int,
    val bullScore: Double,
    val bearScore: Double
) {
    val actionable: Boolean get() = direction != TradeDirection.NONE

    fun directionWordRu(): String = when (direction) {
        TradeDirection.UP -> "ВВЕРХ"
        TradeDirection.DOWN -> "ВНИЗ"
        TradeDirection.NONE -> "НЕТ ВХОДА"
    }

    fun shortSummaryRu(): String {
        val dir = when (direction) {
            TradeDirection.UP -> "вверх ↑"
            TradeDirection.DOWN -> "вниз ↓"
            TradeDirection.NONE -> "без входа"
        }
        return "${asset.titleRu}: $dir, уверенность $confidence%, цена $priceText, таймфрейм ${timeframeMinutes} мин"
    }
}

object MarketAnalysisEngine {

    private val ruLocale = Locale("ru", "RU")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val candleCache = HashMap<String, Pair<Long, List<Candle>>>()
    private val cacheLock = Any()

    @Volatile
    var lastSignal: MarketSignal? = null
        private set

    /**
     * Синхронный анализ (вызывать только из фонового потока!).
     * Возвращает null, если не удалось получить свечи с рынка.
     */
    fun analyzeBlocking(asset: MarketAsset, timeframeMinutes: Int = 15): MarketSignal? {
        val candles = fetchCandles(asset, timeframeMinutes)
        if (candles.size < 55) return null
        val signal = buildSignal(asset, candles, timeframeMinutes)
        lastSignal = signal
        return signal
    }

    fun analyzeAsync(
        asset: MarketAsset,
        timeframeMinutes: Int = 15,
        onResult: (MarketSignal?, String?) -> Unit
    ) {
        executor.execute {
            var signal: MarketSignal? = null
            var error: String? = null
            try {
                signal = analyzeBlocking(asset, timeframeMinutes)
                if (signal == null) {
                    error = "Не удалось загрузить свечи ${asset.titleRu}. Проверьте интернет."
                }
            } catch (e: Exception) {
                error = "Ошибка анализа рынка: ${e.javaClass.simpleName}"
            }
            mainHandler.post { onResult(signal, error) }
        }
    }

    fun nextCandleAtMillis(timeframeMinutes: Int = 15): Long {
        val tfMs = timeframeMinutes * 60_000L
        val now = System.currentTimeMillis()
        return ((now / tfMs) + 1) * tfMs
    }

    fun formatPrice(asset: MarketAsset, price: Double): String =
        String.format(Locale.US, "%.${asset.digits}f", price)

    // ------------------------------------------------------------------ сеть

    private fun fetchCandles(asset: MarketAsset, timeframeMinutes: Int): List<Candle> {
        val cacheKey = "${asset.id}_$timeframeMinutes"
        synchronized(cacheLock) {
            val cached = candleCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.first < 45_000L) {
                return cached.second
            }
        }

        var candles = fetchYahoo(asset, timeframeMinutes)
        if (candles.size < 55 && asset.binanceSymbol != null) {
            val binance = fetchBinance(asset, timeframeMinutes)
            if (binance.size > candles.size) candles = binance
        }
        if (candles.size >= 55) {
            synchronized(cacheLock) { candleCache[cacheKey] = System.currentTimeMillis() to candles }
        }
        return candles
    }

    private fun fetchYahoo(asset: MarketAsset, timeframeMinutes: Int): List<Candle> {
        val tf = if (timeframeMinutes <= 15) 15 else timeframeMinutes
        val encoded = URLEncoder.encode(asset.yahooSymbol, "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded" +
            "?interval=${tf}m&range=5d&includePrePost=false"
        val body = httpGet(url) ?: return emptyList()
        return try {
            val root = JSONObject(body)
            val chart = root.optJSONObject("chart") ?: return emptyList()
            val results = chart.optJSONArray("result") ?: return emptyList()
            if (results.length() == 0) return emptyList()
            val result = results.getJSONObject(0)
            val ts = result.optJSONArray("timestamp") ?: return emptyList()
            val quoteArr = result.optJSONObject("indicators")
                ?.optJSONArray("quote") ?: return emptyList()
            if (quoteArr.length() == 0) return emptyList()
            val quote = quoteArr.getJSONObject(0)
            val opens = quote.optJSONArray("open") ?: return emptyList()
            val highs = quote.optJSONArray("high") ?: return emptyList()
            val lows = quote.optJSONArray("low") ?: return emptyList()
            val closes = quote.optJSONArray("close") ?: return emptyList()
            val volumes = quote.optJSONArray("volume")

            val out = ArrayList<Candle>(ts.length())
            for (i in 0 until ts.length()) {
                if (closes.isNull(i) || opens.isNull(i) || highs.isNull(i) || lows.isNull(i)) continue
                val o = opens.optDouble(i, Double.NaN)
                val h = highs.optDouble(i, Double.NaN)
                val l = lows.optDouble(i, Double.NaN)
                val c = closes.optDouble(i, Double.NaN)
                if (o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN()) continue
                if (o <= 0.0 || c <= 0.0) continue
                val v = volumes?.optDouble(i, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
                out.add(Candle(ts.optLong(i), o, h, l, c, v))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchBinance(asset: MarketAsset, timeframeMinutes: Int): List<Candle> {
        val symbol = asset.binanceSymbol ?: return emptyList()
        val tf = if (timeframeMinutes <= 15) "15m" else "${timeframeMinutes}m"
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$tf&limit=200"
        val body = httpGet(url) ?: return emptyList()
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<Candle>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
                out.add(
                    Candle(
                        timeSec = row.optLong(0) / 1000L,
                        open = row.optString(1).toDoubleOrNull() ?: continue,
                        high = row.optString(2).toDoubleOrNull() ?: continue,
                        low = row.optString(3).toDoubleOrNull() ?: continue,
                        close = row.optString(4).toDoubleOrNull() ?: continue,
                        volume = row.optString(5).toDoubleOrNull() ?: 0.0
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9_000
                readTimeout = 9_000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; NellyAI/5.0) AppleWebKit/537.36"
                )
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------- построение сигнала

    private fun buildSignal(
        asset: MarketAsset,
        candlesAll: List<Candle>,
        timeframeMinutes: Int
    ): MarketSignal {
        val candles = candlesAll.takeLast(180)
        // Последняя свеча может быть незакрытой — для анализа берём только закрытые.
        val nowSec = System.currentTimeMillis() / 1000L
        val tfSec = timeframeMinutes * 60L
        val closed = if (candles.size > 60 && nowSec - candles.last().timeSec < tfSec - 5) {
            candles.dropLast(1)
        } else {
            candles
        }
        val work = if (closed.size >= 55) closed else candles

        val closes = work.map { it.close }.toDoubleArray()
        val last = work.last()
        val price = last.close

        val ema9 = ema(closes, 9)
        val ema21 = ema(closes, 21)
        val ema50 = ema(closes, 50)
        val e9 = ema9.last()
        val e21 = ema21.last()
        val e50 = ema50.last()

        val rsi = rsi(closes, 14)

        val macdLine = DoubleArray(closes.size) { i ->
            emaOfPrefix(closes, i, 12) - emaOfPrefix(closes, i, 26)
        }
        val macdNow = macdLine.last()
        val macdSignal = ema(macdLine.copyOfRange(max(0, macdLine.size - 60), macdLine.size), 9).last()
        val macdHist = macdNow - macdSignal
        val macdHistPrev = if (macdLine.size >= 2) {
            val prevLine = macdLine[macdLine.size - 2]
            val prevSig = ema(
                macdLine.copyOfRange(max(0, macdLine.size - 61), macdLine.size - 1), 9
            ).last()
            prevLine - prevSig
        } else {
            macdHist
        }

        val bb = bollinger(closes, 20, 2.0)
        val bbMid = bb.first
        val bbUpper = bb.second
        val bbLower = bb.third
        val bbPercentB = if (bbUpper - bbLower > 1e-9) {
            (price - bbLower) / (bbUpper - bbLower)
        } else {
            0.5
        }

        val stoch = stochastic(work, 14, 3)
        val stochK = stoch.first
        val stochD = stoch.second

        val atr = atr(work, 14).coerceAtLeast(1e-9)
        val atrPercent = atr / price * 100.0

        val efficiency = efficiencyRatio(closes, 20)

        val recentHigh = work.takeLast(20).maxOf { it.high }
        val recentLow = work.takeLast(20).minOf { it.low }
        val distanceToHigh = (recentHigh - price) / atr
        val distanceToLow = (price - recentLow) / atr

        var bull = 0.0
        var bear = 0.0
        var maxWeight = 0.0
        val bullReasons = ArrayList<Pair<Double, String>>()
        val bearReasons = ArrayList<Pair<Double, String>>()
        val warnings = ArrayList<String>()

        fun factor(weight: Double, dir: Int, reason: String) {
            maxWeight += weight
            if (dir > 0) {
                bull += weight
                if (reason.isNotEmpty()) bullReasons.add(weight to reason)
            } else if (dir < 0) {
                bear += weight
                if (reason.isNotEmpty()) bearReasons.add(weight to reason)
            }
        }

        // 1. Быстрые EMA
        when {
            price > e9 && e9 > e21 -> factor(18.0, 1, "Цена выше EMA 9 и EMA 21 — восходящая структура")
            price < e9 && e9 < e21 -> factor(18.0, -1, "Цена ниже EMA 9 и EMA 21 — нисходящая структура")
            else -> factor(18.0, 0, "")
        }

        // 2. Наклон EMA 21
        val e21Prev = ema21[max(0, ema21.size - 5)]
        val slopePct = (e21 - e21Prev) / price * 100.0
        when {
            slopePct > 0.004 -> factor(10.0, 1, "Средняя EMA 21 смотрит вверх")
            slopePct < -0.004 -> factor(10.0, -1, "Средняя EMA 21 смотрит вниз")
            else -> factor(10.0, 0, "")
        }

        // 3. Долгая EMA 50
        when {
            price > e50 * 1.0002 -> factor(8.0, 1, "Цена держится выше EMA 50")
            price < e50 * 0.9998 -> factor(8.0, -1, "Цена держится ниже EMA 50")
            else -> factor(8.0, 0, "")
        }

        // 4. MACD
        when {
            macdHist > 0 && macdHist >= macdHistPrev -> factor(14.0, 1, "MACD растёт выше сигнальной линии")
            macdHist < 0 && macdHist <= macdHistPrev -> factor(14.0, -1, "MACD падает ниже сигнальной линии")
            macdHist > 0 -> factor(14.0, 1, "MACD в плюсовой зоне")
            macdHist < 0 -> factor(14.0, -1, "MACD в минусовой зоне")
            else -> factor(14.0, 0, "")
        }

        // 5. MACD линия vs сигнал
        when {
            macdNow > macdSignal -> factor(6.0, 1, "")
            macdNow < macdSignal -> factor(6.0, -1, "")
            else -> factor(6.0, 0, "")
        }

        // 6. RSI
        when {
            rsi in 52.0..70.0 -> factor(12.0, 1, "RSI ${rsi.roundToInt()} — умеренная сила покупателей")
            rsi in 30.0..48.0 -> factor(12.0, -1, "RSI ${rsi.roundToInt()} — давление продавцов")
            rsi > 74.0 -> {
                factor(12.0, -1, "")
                warnings.add("RSI ${rsi.roundToInt()} — перекупленность, возможен откат")
            }
            rsi < 26.0 -> {
                factor(12.0, 1, "")
                warnings.add("RSI ${rsi.roundToInt()} — перепроданность, возможен отскок")
            }
            else -> factor(12.0, 0, "")
        }

        // 7. Stochastic
        when {
            stochK > stochD && stochK < 80.0 -> factor(10.0, 1, "Stochastic ${stochK.roundToInt()} разворачивается вверх")
            stochK < stochD && stochK > 20.0 -> factor(10.0, -1, "Stochastic ${stochK.roundToInt()} разворачивается вниз")
            stochK >= 85.0 -> {
                factor(10.0, -1, "")
                warnings.add("Stochastic ${stochK.roundToInt()} — зона перекупленности")
            }
            stochK <= 15.0 -> {
                factor(10.0, 1, "")
                warnings.add("Stochastic ${stochK.roundToInt()} — зона перепроданности")
            }
            else -> factor(10.0, 0, "")
        }

        // 8. Bollinger
        when {
            price > bbMid && bbMid > bollinger(closes.copyOfRange(0, closes.size - 5), 20, 2.0).first ->
                factor(10.0, 1, "Цена выше середины Bollinger и полосы расширяются вверх")
            price < bbMid && bbMid < bollinger(closes.copyOfRange(0, closes.size - 5), 20, 2.0).first ->
                factor(10.0, -1, "Цена ниже середины Bollinger и полосы направлены вниз")
            else -> factor(10.0, 0, "")
        }

        // 9. Свечные паттерны (последние 2 свечи)
        var patternDir = 0
        var patternReason = ""
        val prev = work[work.size - 2]
        when {
            last.isBull && !prev.isBull && last.close > prev.open && last.open <= prev.close -> {
                patternDir = 1
                patternReason = "Паттерн «бычье поглощение» на последней свече"
            }
            !last.isBull && prev.isBull && last.close < prev.open && last.open >= prev.close -> {
                patternDir = -1
                patternReason = "Паттерн «медвежье поглощение» на последней свече"
            }
            last.lowerWick > abs(last.body) * 1.6 && last.lowerWick > last.upperWick * 1.4 -> {
                patternDir = 1
                patternReason = "Длинная нижняя тень — покупатели откупают цену"
            }
            last.upperWick > abs(last.body) * 1.6 && last.upperWick > last.lowerWick * 1.4 -> {
                patternDir = -1
                patternReason = "Длинная верхняя тень — продавцы давят цену"
            }
        }
        factor(12.0, patternDir, patternReason)

        // 10. Импульс последних 3 свечей
        val last3 = work.takeLast(3)
        val greenCount = last3.count { it.isBull }
        when {
            greenCount == 3 -> factor(8.0, 1, "Три бычьи свечи подряд")
            greenCount == 0 -> factor(8.0, -1, "Три медвежьи свечи подряд")
            else -> factor(8.0, 0, "")
        }

        // 11. Близость к уровням
        when {
            distanceToLow <= 0.45 -> factor(9.0, 1, "Цена у локальной поддержки — хорошая точка для входа вверх")
            distanceToHigh <= 0.45 -> factor(9.0, -1, "Цена у локального сопротивления — давление вниз")
            else -> factor(9.0, 0, "")
        }

        // ---- Защитные фильтры
        if (atrPercent < 0.012) {
            warnings.add("Волатильность очень низкая — рынок «стоит», вход рискован")
        }
        if (atrPercent > 1.6) {
            warnings.add("Волатильность повышенная — возможны резкие свечи")
        }
        if (efficiency < 0.16) {
            warnings.add("Рынок в боковике — сигналы ненадёжны")
        }
        if (rsi > 74.0 || rsi < 26.0) {
            warnings.add("Индикаторы в крайних зонах — не догоняйте движение")
        }

        val totalWeighted = bull + bear
        val coverage = totalWeighted / max(1.0, maxWeight)
        val dominance = if (totalWeighted > 0.001) abs(bull - bear) / totalWeighted else 0.0
        val chopFactor = 0.55 + 0.45 * efficiency.coerceIn(0.0, 1.0)
        val volFactor = when {
            atrPercent < 0.012 -> 0.72
            atrPercent > 1.6 -> 0.82
            else -> 1.0
        }

        var confidence = ((coverage * 0.45 + dominance * 0.55) * 100.0 * chopFactor * volFactor)
            .coerceIn(0.0, 99.0)

        // В самом конце 15-минутной свечи сигнал часто «перерисовывается» — слегка снижаем уверенность
        val nowMs = System.currentTimeMillis()
        val lastCandleCloseMs = (work.last().timeSec + tfSec) * 1000L
        val secondsToNext = ((lastCandleCloseMs - nowMs) / 1000L).coerceIn(0L, tfSec)
        if (secondsToNext in 1..45) {
            confidence *= 0.9
            warnings.add("Свеча закрывается менее чем через минуту — дождитесь нового бара")
        }

        val direction = when {
            bull > bear && (bull - bear) >= 12.0 && confidence >= 55.0 -> TradeDirection.UP
            bear > bull && (bear - bull) >= 12.0 && confidence >= 55.0 -> TradeDirection.DOWN
            else -> TradeDirection.NONE
        }

        val trendText = when {
            price > e21 && e21 > e50 -> "восходящий"
            price < e21 && e21 < e50 -> "нисходящий"
            else -> "боковой"
        }

        val reasonsForDirection = when (direction) {
            TradeDirection.UP -> bullReasons.sortedByDescending { it.first }
            TradeDirection.DOWN -> bearReasons.sortedByDescending { it.first }
            TradeDirection.NONE -> {
                if (bull >= bear) bullReasons.sortedByDescending { it.first }
                else bearReasons.sortedByDescending { it.first }
            }
        }.map { it.second }.distinct().take(4)

        if (direction == TradeDirection.NONE && reasonsForDirection.isEmpty()) {
            warnings.add("Индикаторы разнонаправлены — сигнала нет")
        }

        return MarketSignal(
            asset = asset,
            direction = direction,
            confidence = confidence.roundToInt(),
            price = price,
            priceText = formatPrice(asset, price),
            reasons = reasonsForDirection,
            warnings = warnings.distinct().take(4),
            rsi = rsi,
            atr = atr,
            atrPercent = atrPercent,
            trendText = trendText,
            candleOpenTimeSec = work.last().timeSec,
            secondsToNextCandle = secondsToNext,
            timeframeMinutes = timeframeMinutes,
            candlesAnalyzed = work.size,
            bullScore = bull,
            bearScore = bear
        )
    }

    // ------------------------------------------------------------- индикаторы

    private fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size)
        if (values.isEmpty()) return out
        val k = 2.0 / (period + 1.0)
        val seedLen = min(period, values.size)
        var prev = values.take(seedLen).average()
        for (i in values.indices) {
            if (i < seedLen) {
                out[i] = prev
            } else {
                prev = values[i] * k + prev * (1.0 - k)
                out[i] = prev
            }
        }
        return out
    }

    private fun emaOfPrefix(values: DoubleArray, endInclusive: Int, period: Int): Double {
        val start = max(0, endInclusive - period * 3)
        val slice = values.copyOfRange(start, endInclusive + 1)
        return if (slice.isEmpty()) values[endInclusive] else ema(slice, period).last()
    }

    private fun rsi(values: DoubleArray, period: Int = 14): Double {
        if (values.size <= period + 1) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + max(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + max(-d, 0.0)) / period
        }
        if (avgLoss <= 1e-12) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun atr(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size <= period) return 0.0
        val trs = ArrayList<Double>(candles.size)
        trs.add(candles[0].high - candles[0].low)
        for (i in 1 until candles.size) {
            val c = candles[i]
            val p = candles[i - 1]
            trs.add(max(c.high, p.close) - min(c.low, p.close))
        }
        var prev = trs.take(period).average()
        for (i in period until trs.size) {
            prev = (prev * (period - 1) + trs[i]) / period
        }
        return prev
    }

    private fun bollinger(values: DoubleArray, period: Int = 20, mult: Double = 2.0): Triple<Double, Double, Double> {
        val n = min(period, values.size)
        if (n == 0) return Triple(0.0, 0.0, 0.0)
        val slice = values.copyOfRange(values.size - n, values.size)
        val mean = slice.average()
        var variance = 0.0
        for (v in slice) variance += (v - mean) * (v - mean)
        val sd = sqrt(variance / n)
        return Triple(mean, mean + mult * sd, mean - mult * sd)
    }

    private fun stochastic(candles: List<Candle>, kPeriod: Int = 14, dPeriod: Int = 3): Pair<Double, Double> {
        if (candles.size < kPeriod + dPeriod) return 50.0 to 50.0
        val kSeries = ArrayList<Double>(candles.size)
        for (i in kPeriod - 1 until candles.size) {
            val window = candles.subList(i - kPeriod + 1, i + 1)
            val hh = window.maxOf { it.high }
            val ll = window.minOf { it.low }
            val k = if (hh - ll > 1e-12) (candles[i].close - ll) / (hh - ll) * 100.0 else 50.0
            kSeries.add(k)
        }
        val d = kSeries.takeLast(dPeriod).average()
        return kSeries.last() to d
    }

    private fun efficiencyRatio(values: DoubleArray, period: Int = 20): Double {
        if (values.size <= period + 1) return 0.5
        val start = values.size - period - 1
        val change = abs(values.last() - values[start])
        var path = 0.0
        for (i in start + 1 until values.size) {
            path += abs(values[i] - values[i - 1])
        }
        if (path <= 1e-12) return 0.0
        return (change / path).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------ тексты

    fun buildSpeechRu(signal: MarketSignal, includeDisclaimer: Boolean = true): String {
        val sb = StringBuilder()
        val dir = when (signal.direction) {
            TradeDirection.UP -> "вверх"
            TradeDirection.DOWN -> "вниз"
            TradeDirection.NONE -> "без входа"
        }
        sb.append(signal.asset.titleRu)
            .append(", таймфрейм ").append(signal.timeframeMinutes).append(" минут. ")
        sb.append("Направление: ").append(dir)
        if (signal.direction != TradeDirection.NONE) {
            sb.append(", уверенность ").append(signal.confidence).append(" процентов")
        }
        sb.append(". Цена ").append(signal.priceText).append(". ")
        sb.append("Тренд ").append(signal.trendText).append(". ")
        if (signal.reasons.isNotEmpty()) {
            sb.append("Причины: ").append(signal.reasons.joinToString("; ")).append(". ")
        }
        if (signal.warnings.isNotEmpty()) {
            sb.append("Осторожно: ").append(signal.warnings.joinToString("; ")).append(". ")
        }
        if (signal.direction == TradeDirection.NONE) {
            sb.append("Лучше подождать — сигнал слабый. ")
        } else {
            val sec = signal.secondsToNextCandle
            val mins = sec / 60
            if (mins >= 1) {
                sb.append("До закрытия текущей свечи ").append(mins)
                    .append(" мин. Вход лучше открывать сразу после закрытия. ")
            } else {
                sb.append("Свеча закрывается через ").append(sec).append(" секунд. ")
            }
        }
        if (includeDisclaimer) {
            sb.append("Это технический анализ, а не гарантия прибыли.")
        }
        return sb.toString()
    }

    fun candleCloseTimeText(signal: MarketSignal): String {
        val closeMs = (signal.candleOpenTimeSec + signal.timeframeMinutes * 60L) * 1000L
        return SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date(closeMs))
    }
}
