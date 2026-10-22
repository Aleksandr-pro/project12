package com.example.project12

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ResolvedPlace(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val straightMeters: Double
)

data class ShortestRouteStats(
    val distanceKm: Double,
    val durationMinutes: Int
)

object LocationAnd2GisEngine {

    private val ruLocale = Locale("ru", "RU")
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissionIfPossible() {
        val act = AssistantStateBus.getActiveActivity() ?: return
        mainHandler.post {
            ActivityCompat.requestPermissions(
                act,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1006
            )
        }
    }

    /**
     * Obtains best available GPS/Network coordinates, requesting a quick live fix if cached fix is missing or stale.
     */
    fun getBestLocationBlocking(context: Context, maxWaitMs: Long = 3200L): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestCached: Location? = null
        val now = System.currentTimeMillis()
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (bestCached == null || isBetterLocation(loc, bestCached, now)) {
                    bestCached = loc
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        // If we have a recent fix (< 2 minutes old), return immediately for snappy response!
        if (bestCached != null && (now - bestCached.time) < 120_000L) {
            return bestCached
        }

        // Otherwise request a quick fresh fix on main looper
        val latch = CountDownLatch(1)
        var freshLoc: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                freshLoc = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        mainHandler.post {
            try {
                val activeProvider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (activeProvider != null) {
                    lm.requestLocationUpdates(activeProvider, 0L, 0f, listener, Looper.getMainLooper())
                } else {
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        try {
            latch.await(maxWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            mainHandler.post {
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
        }

        return freshLoc ?: bestCached
    }

    fun speakMyCoordinatesAndAddressAsync(context: Context, coordsOnly: Boolean, onResult: (String) -> Unit) {
        val appCtx = context.applicationContext
        if (!hasLocationPermission(appCtx)) {
            requestLocationPermissionIfPossible()
            onResult("Чтобы я определила ваши точные координаты, разрешите доступ к Геолокации на экране и повторите запрос!")
            return
        }

        executor.execute {
            val loc = getBestLocationBlocking(appCtx, maxWaitMs = 4000L)
            if (loc == null) {
                mainHandler.post {
                    onResult("Не удалось получить сигнал GPS. Проверьте, включена ли Геолокация в шторке телефона.")
                }
                return@execute
            }

            val latStr = String.format(Locale.US, "%.5f", loc.latitude)
            val lonStr = String.format(Locale.US, "%.5f", loc.longitude)
            val spokenLat = String.format(ruLocale, "%.4f", loc.latitude)
            val spokenLon = String.format(ruLocale, "%.4f", loc.longitude)
            val accuracyM = loc.accuracy.roundToInt()

            val address = reverseGeocodeBlocking(loc.latitude, loc.longitude)
            val shareText = buildString {
                if (!address.isNullOrBlank()) append("Моё местоположение: $address. ")
                append("Координаты: $latStr, $lonStr — https://maps.google.com/?q=$latStr,$lonStr")
            }

            mainHandler.post {
                copyToClipboard(appCtx, shareText)
                val reply = if (coordsOnly) {
                    val addrPart = if (!address.isNullOrBlank()) " Ближайший адрес: $address." else ""
                    "Ваши координаты: широта $spokenLat, долгота $spokenLon (точность около $accuracyM м).$addrPart Координаты скопированы в буфер обмена."
                } else {
                    if (!address.isNullOrBlank()) {
                        "Вы находитесь по адресу: $address. Координаты ($spokenLat, $spokenLon) скопированы в буфер обмена."
                    } else {
                        "Ваши текущие координаты: широта $spokenLat, долгота $spokenLon (точность $accuracyM м)."
                    }
                }
                onResult(reply)
            }
        }
    }

    fun buildMyLocationShareTextBlocking(context: Context): String? {
        val loc = getBestLocationBlocking(context, maxWaitMs = 3500L) ?: return null
        val latStr = String.format(Locale.US, "%.5f", loc.latitude)
        val lonStr = String.format(Locale.US, "%.5f", loc.longitude)
        val addr = reverseGeocodeBlocking(loc.latitude, loc.longitude)
        return if (!addr.isNullOrBlank()) {
            "Я здесь: $addr ($latStr, $lonStr) https://maps.google.com/?q=$latStr,$lonStr"
        } else {
            "Мои координаты: $latStr, $lonStr https://maps.google.com/?q=$latStr,$lonStr"
        }
    }

    /**
     * Resolves a place/address/category, calculates the shortest path from current GPS,
     * opens 2ГИС directly with route built, and optionally auto-starts turn-by-turn navigation!
     */
    fun buildShortestRouteIn2GisAsync(
        context: Context,
        rawPlaceQuery: String,
        transportMode: String = "car", // "car", "pedestrian", "ctx", "bicycle", "taxi"
        autoStartNavigation: Boolean = true,
        onResult: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        val cleanQuery = rawPlaceQuery
            .replace(Regex("\\s+(?:в 2гис|в два гис|в дубльгис|через 2гис|по 2гис|пожалуйста|быстренько)$", RegexOption.IGNORE_CASE), "")
            .trim(' ', '.', ',', '!', '?')

        if (!hasLocationPermission(appCtx)) {
            requestLocationPermissionIfPossible()
        }

        executor.execute {
            val myLoc = getBestLocationBlocking(appCtx, maxWaitMs = 2800L)
            val fallbackCity = ConversationContext.lastWeatherCity ?: WeatherClient.getSavedCity(appCtx)

            val resolved = resolveNearestPlaceBlocking(
                query = cleanQuery,
                userLat = myLoc?.latitude,
                userLon = myLoc?.longitude,
                cityHint = fallbackCity
            )

            if (resolved != null) {
                ConversationContext.lastRouteDestLat = resolved.lat
                ConversationContext.lastRouteDestLon = resolved.lon
                ConversationContext.lastRouteDestName = resolved.title
                ConversationContext.lastRouteTransport = transportMode
                ConversationContext.lastAction = "ROUTE_2GIS"

                val stats = if (myLoc != null) {
                    computeShortestRouteBlocking(
                        fromLat = myLoc.latitude,
                        fromLon = myLoc.longitude,
                        toLat = resolved.lat,
                        toLon = resolved.lon,
                        transportMode = transportMode
                    )
                } else null

                mainHandler.post {
                    val launched2Gis = open2GisRouteUri(
                        context = appCtx,
                        fromLat = myLoc?.latitude,
                        fromLon = myLoc?.longitude,
                        toLat = resolved.lat,
                        toLon = resolved.lon,
                        rsType = transportMode
                    )
                    if (launched2Gis && autoStartNavigation) {
                        NellyAccessibilityService.scheduleAutoStart2GisNavigation()
                    }

                    val distText = if (stats != null) {
                        val dStr = String.format(ruLocale, "%.1f", stats.distanceKm)
                        " Кратчайший путь: $dStr км (около ${stats.durationMinutes} мин)."
                    } else if (resolved.straightMeters > 0) {
                        val km = resolved.straightMeters / 1000.0
                        " Расстояние по прямой: ${String.format(ruLocale, "%.1f", km)} км."
                    } else ""

                    val modeWord = when (transportMode) {
                        "pedestrian" -> "пеший маршрут"
                        "ctx" -> "маршрут на транспорте"
                        "taxi" -> "маршрут на такси"
                        "bicycle" -> "веломаршрут"
                        else -> "маршрут"
                    }

                    val addrNote = if (resolved.subtitle.isNotBlank()) " (${resolved.subtitle})" else ""
                    val navAppLabel = if (launched2Gis) "в 2ГИС" else "на карте"
                    onResult("Определила место: ${resolved.title}$addrNote.$distText Прокладываю $modeWord $navAppLabel!")
                }
            } else {
                // Place is likely a hyper-local 2GIS organization name -> open directly in 2GIS search and auto-click Route!
                mainHandler.post {
                    val opened = open2GisSearchUri(appCtx, cleanQuery)
                    if (opened && autoStartNavigation) {
                        NellyAccessibilityService.scheduleAutoStart2GisNavigation()
                    }
                    val msg = if (opened) {
                        "Ищу «$cleanQuery» прямо в справочнике 2ГИС и строю маршрут!"
                    } else {
                        openFallbackGeoSearch(appCtx, cleanQuery)
                        "Открываю поиск места «$cleanQuery» на карте."
                    }
                    onResult(msg)
                }
            }
        }
    }

    /**
     * Determines a place's address, coordinates, and distance without necessarily starting turn-by-turn driving.
     */
    fun determinePlaceDetailsAsync(
        context: Context,
        rawPlaceQuery: String,
        openIn2GisCard: Boolean,
        onResult: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        executor.execute {
            val myLoc = getBestLocationBlocking(appCtx, maxWaitMs = 2500L)
            val city = ConversationContext.lastWeatherCity ?: WeatherClient.getSavedCity(appCtx)
            val resolved = resolveNearestPlaceBlocking(rawPlaceQuery, myLoc?.latitude, myLoc?.longitude, city)

            if (resolved == null) {
                mainHandler.post {
                    if (openIn2GisCard && open2GisSearchUri(appCtx, rawPlaceQuery)) {
                        onResult("Открываю «$rawPlaceQuery» в 2ГИС.")
                    } else {
                        onResult("Не удалось точно определить координаты места «$rawPlaceQuery». Скажите «найди в 2ГИС $rawPlaceQuery».")
                    }
                }
                return@execute
            }

            ConversationContext.lastRouteDestLat = resolved.lat
            ConversationContext.lastRouteDestLon = resolved.lon
            ConversationContext.lastRouteDestName = resolved.title
            ConversationContext.lastAction = "PLACE_INFO"

            val stats = if (myLoc != null) {
                computeShortestRouteBlocking(myLoc.latitude, myLoc.longitude, resolved.lat, resolved.lon, "car")
            } else null

            mainHandler.post {
                if (openIn2GisCard) {
                    open2GisSearchUri(appCtx, "${resolved.title} ${resolved.subtitle}".trim())
                }
                val distInfo = if (stats != null) {
                    " Кратчайший путь от вас — ${String.format(ruLocale, "%.1f", stats.distanceKm)} км (около ${stats.durationMinutes} мин на авто)."
                } else ""
                val addrInfo = if (resolved.subtitle.isNotBlank()) ", адрес: ${resolved.subtitle}" else ""
                onResult("${resolved.title}$addrInfo.$distInfo Скажите «поехали», чтобы сразу проложить маршрут в 2ГИС!")
            }
        }
    }

    fun open2GisSearchUri(context: Context, query: String): Boolean {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // Official 2GIS URI scheme: dgis://2gis.ru/search/<query>
        val uri = Uri.parse("dgis://2gis.ru/search/$encoded")
        val dgisIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("ru.dublgis.dgismobile")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (safeStart(context, dgisIntent)) return true

        val genericDgis = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return safeStart(context, genericDgis)
    }

    fun open2GisRouteUri(
        context: Context,
        fromLat: Double?,
        fromLon: Double?,
        toLat: Double,
        toLon: Double,
        rsType: String = "car"
    ): Boolean {
        val toLonStr = String.format(Locale.US, "%.6f", toLon)
        val toLatStr = String.format(Locale.US, "%.6f", toLat)

        // Official 2GIS URI uses LON,LAT order!
        val dgisUriStr = if (fromLat != null && fromLon != null) {
            val fromLonStr = String.format(Locale.US, "%.6f", fromLon)
            val fromLatStr = String.format(Locale.US, "%.6f", fromLat)
            "dgis://2gis.ru/routeSearch/rsType/$rsType/from/$fromLonStr,$fromLatStr/to/$toLonStr,$toLatStr"
        } else {
            "dgis://2gis.ru/routeSearch/rsType/$rsType/to/$toLonStr,$toLatStr"
        }

        val dgisIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dgisUriStr)).apply {
            setPackage("ru.dublgis.dgismobile")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (safeStart(context, dgisIntent)) return true

        val genericDgis = Intent(Intent.ACTION_VIEW, Uri.parse(dgisUriStr)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (safeStart(context, genericDgis)) return true

        // Fallback if 2GIS app is not installed: Google Navigation / Geo intent
        val googleMode = if (rsType == "pedestrian") "w" else "d"
        val gNav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$toLatStr,$toLonStr&mode=$googleMode")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (safeStart(context, gNav)) return false

        openFallbackGeoSearch(context, "$toLatStr,$toLonStr")
        return false
    }

    private fun resolveNearestPlaceBlocking(
        query: String,
        userLat: Double?,
        userLon: Double?,
        cityHint: String
    ): ResolvedPlace? {
        val candidates = mutableListOf<ResolvedPlace>()

        // 1. If we know user's GPS coordinates, search bounded ±0.30 deg (~30 km) around user first!
        if (userLat != null && userLon != null) {
            val left = String.format(Locale.US, "%.4f", userLon - 0.30)
            val top = String.format(Locale.US, "%.4f", userLat + 0.30)
            val right = String.format(Locale.US, "%.4f", userLon + 0.30)
            val bottom = String.format(Locale.US, "%.4f", userLat - 0.30)
            val encoded = URLEncoder.encode(query, "UTF-8")
            val boundedUrl = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=6&accept-language=ru" +
                "&viewbox=$left,$top,$right,$bottom&bounded=1&q=$encoded"
            parseNominatimCandidates(httpGet(boundedUrl), userLat, userLon, candidates)
        }

        // 2. Fallback: search "<query>, <cityHint>" if bounded GPS search returned empty
        if (candidates.isEmpty()) {
            val qWithCity = if (query.lowercase(ruLocale).contains(cityHint.lowercase(ruLocale))) {
                query
            } else {
                "$query, $cityHint"
            }
            val encodedCity = URLEncoder.encode(qWithCity, "UTF-8")
            val cityUrl = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=5&accept-language=ru&q=$encodedCity"
            parseNominatimCandidates(httpGet(cityUrl), userLat, userLon, candidates)
        }

        // 3. Global query fallback (e.g. intercity route "Астана", "Алматы", "Оренбург")
        if (candidates.isEmpty()) {
            val encodedGlobal = URLEncoder.encode(query, "UTF-8")
            val globalUrl = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=3&accept-language=ru&q=$encodedGlobal"
            parseNominatimCandidates(httpGet(globalUrl), userLat, userLon, candidates)
        }

        if (candidates.isEmpty()) return null

        // Sort by shortest straight-line distance from user's real GPS (when GPS is available)!
        return if (userLat != null && userLon != null) {
            candidates.minByOrNull { it.straightMeters }
        } else {
            candidates.first()
        }
    }

    private fun parseNominatimCandidates(
        jsonRaw: String?,
        userLat: Double?,
        userLon: Double?,
        out: MutableList<ResolvedPlace>
    ) {
        if (jsonRaw.isNullOrBlank()) return
        try {
            val arr = JSONArray(jsonRaw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                val name = obj.optString("name", "").trim()
                val displayName = obj.optString("display_name", "").trim()
                val addr = obj.optJSONObject("address")

                val road = addr?.optString("road", "").orEmpty()
                val house = addr?.optString("house_number", "").orEmpty()
                val city = addr?.optString("city", "")?.ifEmpty { addr.optString("town", "") }.orEmpty()

                val title = when {
                    name.isNotEmpty() -> name
                    road.isNotEmpty() -> "$road $house".trim()
                    else -> displayName.substringBefore(',').trim()
                }
                val subtitle = buildString {
                    if (road.isNotEmpty() && !title.contains(road, ignoreCase = true)) {
                        append(road)
                        if (house.isNotEmpty()) append(", $house")
                    }
                    if (city.isNotEmpty() && !title.contains(city, ignoreCase = true)) {
                        if (isNotEmpty()) append(", ")
                        append(city)
                    }
                }

                val dist = if (userLat != null && userLon != null) {
                    haversineMeters(userLat, userLon, lat, lon)
                } else 0.0

                out.add(ResolvedPlace(title, subtitle, lat, lon, dist))
            }
        } catch (_: Exception) {
        }
    }

    private fun computeShortestRouteBlocking(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        transportMode: String
    ): ShortestRouteStats? {
        val profile = if (transportMode == "pedestrian") "foot" else "driving"
        val coords = String.format(
            Locale.US,
            "%.5f,%.5f;%.5f,%.5f",
            fromLon, fromLat, toLon, toLat
        )
        val url = "https://router.project-osrm.org/route/v1/$profile/$coords?overview=false&alternatives=true"
        val json = httpGet(url) ?: return null
        return try {
            val root = JSONObject(json)
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null

            // Pick the shortest-distance route among alternatives!
            var minMeters = Double.MAX_VALUE
            var bestSeconds = 0.0
            for (i in 0 until routes.length()) {
                val r = routes.getJSONObject(i)
                val distM = r.optDouble("distance", Double.MAX_VALUE)
                val durS = r.optDouble("duration", 0.0)
                if (distM < minMeters) {
                    minMeters = distM
                    bestSeconds = durS
                }
            }
            if (minMeters == Double.MAX_VALUE) return null
            val km = (minMeters / 1000.0).coerceAtLeast(0.1)
            val mins = if (transportMode == "pedestrian") {
                ((minMeters / 80.0).roundToInt()).coerceAtLeast(1) // ~4.8 km/h walking
            } else {
                ((bestSeconds / 60.0).roundToInt()).coerceAtLeast(1)
            }
            ShortestRouteStats(km, mins)
        } catch (_: Exception) {
            null
        }
    }

    private fun reverseGeocodeBlocking(lat: Double, lon: Double): String? {
        val latS = String.format(Locale.US, "%.6f", lat)
        val lonS = String.format(Locale.US, "%.6f", lon)
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$latS&lon=$lonS&accept-language=ru"
        val json = httpGet(url) ?: return null
        return try {
            val root = JSONObject(json)
            val addr = root.optJSONObject("address")
            if (addr != null) {
                val road = addr.optString("road", "").ifEmpty { addr.optString("pedestrian", "") }
                val house = addr.optString("house_number", "")
                val suburb = addr.optString("suburb", "").ifEmpty { addr.optString("neighbourhood", "") }
                val city = addr.optString("city", "").ifEmpty { addr.optString("town", "").ifEmpty { addr.optString("village", "") } }
                val parts = mutableListOf<String>()
                if (road.isNotEmpty()) parts.add(if (house.isNotEmpty()) "$road, $house" else road)
                if (suburb.isNotEmpty() && parts.isEmpty()) parts.add(suburb)
                if (city.isNotEmpty()) parts.add(city)
                if (parts.isNotEmpty()) return parts.joinToString(", ")
            }
            root.optString("display_name", "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun isBetterLocation(candidate: Location, currentBest: Location, nowMs: Long): Boolean {
        val candAge = nowMs - candidate.time
        val bestAge = nowMs - currentBest.time
        if (candAge < bestAge - 60_000L) return true
        if (candAge > bestAge + 60_000L) return false
        return candidate.accuracy <= currentBest.accuracy
    }

    private fun openFallbackGeoSearch(context: Context, query: String) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!safeStart(context, geo)) {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            safeStart(context, web)
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Координаты Нелли", text))
        } catch (_: Exception) {
        }
    }

    private fun safeStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5500
            readTimeout = 5500
            setRequestProperty("User-Agent", "NellyVoiceAssistant/4.0 (Android; ru)")
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
