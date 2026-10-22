package com.example.project12

import android.content.Context
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
import kotlin.math.roundToInt

enum class ForecastMode {
    NOW,
    TOMORROW,
    ADVICE
}

object WeatherClient {

    private const val PREFS_NAME = "assistant_prefs"
    private const val KEY_CITY = "weather_city"
    private const val DEFAULT_CITY = "Актобе"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val knownCases = mapOf(
        "москве" to "Москва",
        "санкт-петербурге" to "Санкт-Петербург",
        "петербурге" to "Санкт-Петербург",
        "питере" to "Санкт-Петербург",
        "алматы" to "Алматы",
        "алма-ате" to "Алматы",
        "актобе" to "Актобе",
        "астане" to "Астана",
        "караганде" to "Караганда",
        "шымкенте" to "Шымкент",
        "атырау" to "Атырау",
        "актау" to "Актау",
        "уральске" to "Уральск",
        "павлодаре" to "Павлодар",
        "костанае" to "Костанай",
        "ташкенте" to "Ташкент",
        "бишкеке" to "Бишкек",
        "минске" to "Минск",
        "новосибирске" to "Новосибирск",
        "екатеринбурге" to "Екатеринбург",
        "казани" to "Казань",
        "тюмени" to "Тюмень",
        "перми" to "Пермь",
        "самаре" to "Самара",
        "уфе" to "Уфа",
        "омске" to "Омск",
        "челябинске" to "Челябинск",
        "краснодаре" to "Краснодар",
        "сочи" to "Сочи",
        "дубае" to "Дубай",
        "стамбуле" to "Стамбул",
        "лондоне" to "Лондон",
        "париже" to "Париж",
        "токио" to "Токио"
    )

    fun getSavedCity(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CITY, DEFAULT_CITY) ?: DEFAULT_CITY
    }

    fun saveCity(context: Context, rawCity: String): String {
        val normalized = normalizeCityCandidate(rawCity)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CITY, normalized)
            .apply()
        ConversationContext.lastWeatherCity = normalized
        return normalized
    }

    fun normalizeCityCandidate(raw: String): String {
        val clean = raw.trim().trim(' ', ',', '.', '?', '!', '«', '»', '"')
            .replace(Regex("^(?:городе|город)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:сегодня|сейчас|завтра|на завтра|пожалуйста)$", RegexOption.IGNORE_CASE), "")
            .trim()
        val lower = clean.lowercase(Locale("ru", "RU"))
        knownCases[lower]?.let { return it }
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("ru", "RU")) else it.toString() }
    }

    private fun candidateQueries(rawCity: String): List<String> {
        val primary = normalizeCityCandidate(rawCity)
        val lower = primary.lowercase(Locale("ru", "RU"))
        val list = mutableListOf(primary)
        if (lower.length >= 4 && lower.endsWith("е")) {
            val stem = primary.dropLast(1)
            list.add(stem)
            list.add("${stem}а")
        } else if (lower.length >= 4 && lower.endsWith("и")) {
            val stem = primary.dropLast(1)
            list.add("${stem}ь")
            list.add("${stem}я")
        }
        return list.distinct()
    }

    fun fetchWeatherAsync(
        context: Context,
        cityQuery: String?,
        mode: ForecastMode = ForecastMode.NOW,
        onResult: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        val targetCity = when {
            !cityQuery.isNullOrBlank() -> cityQuery.trim()
            !ConversationContext.lastWeatherCity.isNullOrBlank() -> ConversationContext.lastWeatherCity!!
            else -> getSavedCity(appCtx)
        }
        executor.execute {
            val message = try {
                loadWeatherBlocking(appCtx, targetCity, mode)
            } catch (_: Exception) {
                "Не удалось получить погоду для города $targetCity. Проверьте интернет."
            }
            mainHandler.post { onResult(message) }
        }
    }

    private fun loadWeatherBlocking(context: Context, rawCity: String, mode: ForecastMode): String {
        var resolvedName: String? = null
        var lat = 0.0
        var lon = 0.0

        for (candidate in candidateQueries(rawCity)) {
            val encoded = URLEncoder.encode(candidate, "UTF-8")
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=ru&format=json"
            val geoJson = httpGet(geoUrl) ?: continue
            val root = JSONObject(geoJson)
            val results = root.optJSONArray("results")
            if (results != null && results.length() > 0) {
                val first = results.getJSONObject(0)
                resolvedName = first.optString("name", candidate)
                lat = first.getDouble("latitude")
                lon = first.getDouble("longitude")
                break
            }
        }

        val cityName = resolvedName
            ?: return "Не удалось найти город $rawCity. Назовите город точнее."

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CITY, cityName)
            .apply()
        ConversationContext.lastWeatherCity = cityName

        val forecastUrl =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=2"

        val weatherJson = httpGet(forecastUrl)
            ?: return "Сервис погоды временно недоступен."

        val root = JSONObject(weatherJson)
        val current = root.getJSONObject("current")
        val daily = root.optJSONObject("daily")

        if (mode == ForecastMode.TOMORROW && daily != null) {
            val maxArr = daily.optJSONArray("temperature_2m_max")
            val minArr = daily.optJSONArray("temperature_2m_min")
            val codeArr = daily.optJSONArray("weather_code")
            val probArr = daily.optJSONArray("precipitation_probability_max")
            if (maxArr != null && maxArr.length() >= 2 && minArr != null && minArr.length() >= 2) {
                val tMax = maxArr.getDouble(1).roundToInt()
                val tMin = minArr.getDouble(1).roundToInt()
                val wCode = codeArr?.optInt(1, 0) ?: 0
                val precipProb = probArr?.optInt(1, 0) ?: 0
                val cond = describeWeatherCode(wCode)
                val rainHint = if (precipProb >= 40) " Вероятность осадков $precipProb%, лучше взять зонт." else ""
                return "Завтра в городе $cityName днём до ${formatSignedTemp(tMax)}°, ночью ${formatSignedTemp(tMin)}°. $cond.$rainHint"
            }
        }

        val temp = current.getDouble("temperature_2m").roundToInt()
        val feels = current.getDouble("apparent_temperature").roundToInt()
        val code = current.optInt("weather_code", 0)
        val wind = current.optDouble("wind_speed_10m", 0.0).roundToInt()
        val todayRainProb = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
        val cond = describeWeatherCode(code)

        if (mode == ForecastMode.ADVICE) {
            val clothes = when {
                feels <= -15 -> "Очень морозно — надевайте тёплый пуховик, шапку и перчатки."
                feels <= 0 -> "На улице минус — нужна зимняя куртка и шапка."
                feels <= 10 -> "Довольно прохладно — наденьте куртку или пальто."
                feels <= 18 -> "Свежо — подойдёт кофта, ветровка или лёгкая куртка."
                feels <= 25 -> "Комфортная тёплая погода — можно идти налегке."
                else -> "Жарко — одевайтесь максимально легко и берите воду."
            }
            val isWetCode = code in 51..99
            val umbrella = when {
                isWetCode -> "Сейчас идут осадки — обязательно берите зонт!"
                todayRainProb >= 45 -> "Вероятность осадков сегодня $todayRainProb% — лучше захватить зонт."
                else -> "Осадков не ожидается, зонт не нужен."
            }
            return "В городе $cityName сейчас ${formatSignedTemp(temp)}° (ощущается как ${formatSignedTemp(feels)}°), ${cond.lowercase(Locale("ru", "RU"))}. $clothes $umbrella"
        }

        return "Сейчас в городе $cityName ${formatSignedTemp(temp)} градусов, ощущается как ${formatSignedTemp(feels)}°. $cond, ветер $wind м/с."
    }

    private fun formatSignedTemp(t: Int): String = if (t > 0) "+$t" else t.toString()

    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "Ясно"
        1 -> "Преимущественно ясно"
        2 -> "Переменная облачность"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Ледяная морось"
        61, 63, 65 -> "Дождь"
        66, 67 -> "Ледяной дождь"
        71, 73, 75, 77 -> "Снег"
        80, 81, 82 -> "Ливень"
        85, 86 -> "Снегопад"
        95, 96, 99 -> "Гроза"
        else -> "Без существенных осадков"
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json")
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
