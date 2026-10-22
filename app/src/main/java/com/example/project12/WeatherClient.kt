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
        "усть-каменогорске" to "Усть-Каменогорск",
        "семее" to "Семей",
        "ташкенте" to "Ташкент",
        "бишкеке" to "Бишкек",
        "минске" to "Минск",
        "новосибирске" to "Новосибирск",
        "екатеринбурге" to "Екатеринбург",
        "казани" to "Казань",
        "тюмени" to "Тюмень",
        "перми" to "Пермь",
        "твери" to "Тверь",
        "самаре" to "Самара",
        "уфе" to "Уфа",
        "омске" to "Омск",
        "челябинске" to "Челябинск",
        "ростове" to "Ростов-на-Дону",
        "краснодаре" to "Краснодар",
        "сочи" to "Сочи",
        "дубае" to "Дубай",
        "стамбуле" to "Стамбул"
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
        return normalized
    }

    fun normalizeCityCandidate(raw: String): String {
        val clean = raw.trim().trim(' ', ',', '.', '?', '!', '«', '»', '"')
            .replace(Regex("^(?:городе|город)\\s+", RegexOption.IGNORE_CASE), "")
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

    fun fetchWeatherAsync(context: Context, cityQuery: String?, onResult: (String) -> Unit) {
        val appCtx = context.applicationContext
        val targetCity = if (cityQuery.isNullOrBlank()) getSavedCity(appCtx) else cityQuery.trim()
        executor.execute {
            val message = try {
                loadWeatherBlocking(appCtx, targetCity)
            } catch (_: Exception) {
                "Не удалось получить погоду для города $targetCity. Проверьте подключение к интернету."
            }
            mainHandler.post { onResult(message) }
        }
    }

    private fun loadWeatherBlocking(context: Context, rawCity: String): String {
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
            ?: return "Не удалось найти город $rawCity. Назовите город точнее, например: погода в Алматы."

        // Save resolved city so future "какая погода" uses it
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CITY, cityName)
            .apply()

        val forecastUrl =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&wind_speed_unit=ms"
        val weatherJson = httpGet(forecastUrl)
            ?: return "Сервис погоды временно недоступен. Попробуйте чуть позже."

        val current = JSONObject(weatherJson).getJSONObject("current")
        val temp = current.getDouble("temperature_2m").roundToInt()
        val feels = current.getDouble("apparent_temperature").roundToInt()
        val code = current.optInt("weather_code", 0)
        val wind = current.optDouble("wind_speed_10m", 0.0).roundToInt()

        val tempText = formatSignedTemp(temp)
        val feelsText = formatSignedTemp(feels)
        val condition = describeWeatherCode(code)

        return "Сейчас в городе $cityName $tempText градусов, ощущается как $feelsText. $condition, ветер $wind м/с."
    }

    private fun formatSignedTemp(t: Int): String = when {
        t > 0 -> "+$t"
        else -> t.toString()
    }

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
