package com.example.project12

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToLong

object KnowledgeAndCurrencyClient {

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedUsdRates: Map<String, Double>? = null

    @Volatile
    private var cachedRatesTimestamp: Long = 0L

    private val currencyNamesGenitive = mapOf(
        "USD" to "долларов",
        "EUR" to "евро",
        "KZT" to "тенге",
        "RUB" to "рублей",
        "CNY" to "юаней",
        "TRY" to "турецких лир",
        "AED" to "дирхамов",
        "GBP" to "фунтов",
        "KGS" to "сомов",
        "UZS" to "сумов"
    )

    fun detectCurrencyCode(word: String): String? {
        val w = word.lowercase(Locale("ru", "RU"))
        return when {
            w.contains("доллар") || w.contains("бакс") || w == "usd" || w == "$" -> "USD"
            w.contains("евро") || w == "eur" || w == "€" -> "EUR"
            w.contains("тенге") || w.contains("тг") || w == "kzt" || w == "₸" -> "KZT"
            w.contains("рубл") || w == "rub" || w == "₽" -> "RUB"
            w.contains("юан") || w == "cny" -> "CNY"
            w.contains("лир") || w == "try" -> "TRY"
            w.contains("дирхам") || w == "aed" -> "AED"
            w.contains("фунт") || w == "gbp" -> "GBP"
            w.contains("сом") || w == "kgs" -> "KGS"
            w.contains("сум") || w == "uzs" -> "UZS"
            else -> null
        }
    }

    fun fetchCurrencyAsync(
        amount: Double,
        fromCode: String,
        toCode: String?,
        onResult: (String) -> Unit
    ) {
        executor.execute {
            val reply = try {
                val rates = loadUsdRatesBlocking()
                if (rates == null) {
                    "Не удалось загрузить свежий курс валют. Проверьте интернет."
                } else {
                    val fromRate = rates[fromCode]
                    if (fromRate == null || fromRate <= 0.0) {
                        "Курс для валюты $fromCode не найден."
                    } else if (toCode == null) {
                        // Show rate in KZT and RUB (or USD if asking about KZT/RUB)
                        if (fromCode == "KZT" || fromCode == "RUB") {
                            val inUsd = (amount / fromRate)
                            "По текущему курсу ${formatAmount(amount)} ${currencyNamesGenitive[fromCode]} — это ${formatAmount(inUsd)} долларов."
                        } else {
                            val kztRate = rates["KZT"] ?: 0.0
                            val rubRate = rates["RUB"] ?: 0.0
                            val inKzt = (amount / fromRate) * kztRate
                            val inRub = (amount / fromRate) * rubRate
                            val unitLabel = if (amount == 1.0) "1 $fromCode" else "${formatAmount(amount)} $fromCode"
                            "Курс сейчас: $unitLabel — это ${formatAmount(inKzt)} тенге (или ${formatAmount(inRub)} рублей)."
                        }
                    } else {
                        val toRate = rates[toCode] ?: 0.0
                        val converted = (amount / fromRate) * toRate
                        ConversationContext.lastMathValue = converted
                        "${formatAmount(amount)} ${currencyNamesGenitive[fromCode] ?: fromCode} — это примерно ${formatAmount(converted)} ${currencyNamesGenitive[toCode] ?: toCode}."
                    }
                }
            } catch (_: Exception) {
                "Не удалось получить курс валют."
            }
            mainHandler.post { onResult(reply) }
        }
    }

    fun fetchWikipediaSummaryAsync(topic: String, onResult: (String) -> Unit) {
        val cleanTopic = topic.trim()
        executor.execute {
            val reply = try {
                loadWikipediaBlocking(cleanTopic)
                    ?: "Не нашёл краткой справки в Википедии по теме «$cleanTopic»."
            } catch (_: Exception) {
                "Не удалось связаться с энциклопедией. Проверьте подключение к интернету."
            }
            mainHandler.post { onResult(reply) }
        }
    }

    fun answerFreeformQuestionAsync(userQuestion: String, onResult: (String) -> Unit) {
        executor.execute {
            val reply = try {
                askCloudAssistantBlocking(userQuestion)
                    ?: loadWikipediaBlocking(extractKeyTopic(userQuestion))
                    ?: "Я пока не уверен, как выполнить «$userQuestion». Попробуйте сказать чуть иначе или скажите «загугли $userQuestion»."
            } catch (_: Exception) {
                "Не совсем понял запрос «$userQuestion». Скажите «найди $userQuestion» для поиска в Google."
            }
            mainHandler.post { onResult(reply) }
        }
    }

    private fun loadUsdRatesBlocking(): Map<String, Double>? {
        val now = System.currentTimeMillis()
        val cached = cachedUsdRates
        if (cached != null && now - cachedRatesTimestamp < 30 * 60_000L) {
            return cached
        }
        val json = httpGet("https://open.er-api.com/v6/latest/USD", timeoutMs = 6000) ?: return cached
        val root = JSONObject(json)
        val ratesObj = root.optJSONObject("rates") ?: return cached
        val map = mutableMapOf<String, Double>()
        val keys = ratesObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = ratesObj.optDouble(k, 0.0)
        }
        if (map.isNotEmpty()) {
            cachedUsdRates = map
            cachedRatesTimestamp = now
        }
        return map
    }

    private fun loadWikipediaBlocking(query: String): String? {
        if (query.length < 2) return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://ru.wikipedia.org/w/api.php?action=query&format=json" +
            "&prop=extracts&exintro=1&explaintext=1&exsentences=2&redirects=1" +
            "&generator=search&gsrsearch=$encoded&gsrlimit=1"
        val json = httpGet(url, timeoutMs = 6000) ?: return null
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        if (!keys.hasNext()) return null
        val page = pages.getJSONObject(keys.next())
        val extract = page.optString("extract", "")
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (extract.length >= 15) extract else null
    }

    private fun askCloudAssistantBlocking(question: String): String? {
        val recent = ConversationContext.getRecentContextSummary()
        val systemInstruction = "Ты голосовой помощник на Android. Ответь пользователю на русском языке коротко (1-2 предложения), простыми словами для озвучки вслух, без символов форматирования и звёздочек."
        val fullPrompt = if (recent.isNotEmpty()) {
            "$systemInstruction\nКонтекст:\n$recent\nЗапрос: $question"
        } else {
            "$systemInstruction\nЗапрос: $question"
        }
        val encoded = URLEncoder.encode(fullPrompt, "UTF-8")
        val raw = httpGet("https://text.pollinations.ai/$encoded", timeoutMs = 5500) ?: return null
        val cleaned = raw
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length !in 3..450 || cleaned.startsWith("<!DOCTYPE", ignoreCase = true) || cleaned.startsWith("{")) {
            return null
        }
        return cleaned
    }

    private fun extractKeyTopic(text: String): String {
        return text.replace(
            Regex("^(?:расскажи мне про|расскажи про|расскажи о|объясни что такое|что такое|кто такой|кто такая|где находится)\\s+", RegexOption.IGNORE_CASE),
            ""
        ).trim(' ', '?', '.', '!')
    }

    private fun formatAmount(v: Double): String {
        val rounded = if (v >= 100.0) {
            v.roundToLong().toDouble()
        } else {
            (v * 100.0).roundToLong() / 100.0
        }
        val asLong = rounded.toLong()
        return if (rounded == asLong.toDouble()) {
            asLong.toString()
        } else {
            rounded.toString().replace('.', ',')
        }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Project12VoiceAssistant/3.0 (Android; ru)")
        }
        return try {
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
