package com.example.project12

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class CommandResult(
    val spokenResponse: String? = null,
    val shouldStopService: Boolean = false,
    val stopSpeakingOnly: Boolean = false,
    val speechRate: Float? = null,
    val asyncPending: Boolean = false,
    val matched: Boolean = true
)

class CommandProcessor(
    private val context: Context,
    private val onAsyncReply: (userText: String, reply: String) -> Unit
) {

    private val ruLocale = Locale("ru", "RU")
    private val prefs = context.applicationContext.getSharedPreferences("assistant_data", Context.MODE_PRIVATE)

    fun stripWakeWordPrefix(raw: String): Pair<Boolean, String> {
        val trimmed = raw.trim()
        val regex = Regex(
            "^(?:эй\\s+|привет\\s+|слушай\\s+)?(?:помощник|ассистент|джарвис)[,\\s:!-]*(.*)$",
            RegexOption.IGNORE_CASE
        )
        val m = regex.find(trimmed) ?: return false to trimmed
        return true to m.groupValues[1].trim()
    }

    fun process(rawInput: String): CommandResult {
        val original = rawInput.trim()
        if (original.isEmpty()) {
            return CommandResult(spokenResponse = null, matched = false)
        }

        val lower = original.lowercase(ruLocale)
            .replace('ё', 'е')
            .trim(' ', '.', ',', '!', '?')

        // 1. Stop speaking / Shut up
        if (lower in listOf("замолчи", "тихо", "хватит", "стоп речь", "хватит говорить", "заткнись")) {
            return CommandResult(spokenResponse = null, stopSpeakingOnly = true)
        }

        // 2. Turn off assistant service
        if (lower in listOf("стоп", "выключись", "отключись", "заверши работу") ||
            lower.contains("останови помощник") ||
            lower.contains("выключи помощник") ||
            lower.contains("отключи помощник")
        ) {
            return CommandResult(
                spokenResponse = "Выключаюсь. До встречи!",
                shouldStopService = true
            )
        }

        // 3. Switch recognition mode by voice
        if (lower.contains("прямой режим") || lower.contains("слушай напрямую") || lower.contains("без ключевого слова")) {
            AssistantStateBus.setDirectMode(context, true)
            return CommandResult("Включён прямой режим распознавания. Говорите команды сразу.")
        }
        if (lower.contains("режим ключевого слова") || lower.contains("только по слову помощник")) {
            AssistantStateBus.setDirectMode(context, false)
            return CommandResult("Включён режим по слову «Помощник». Начинайте команды со слова Помощник.")
        }

        // 4. Speech rate control
        if (lower.contains("говори быстрее") || lower.contains("быстрее говори") || lower.contains("ускорь речь")) {
            return CommandResult(spokenResponse = "Хорошо, буду говорить быстрее.", speechRate = 1.35f)
        }
        if (lower.contains("говори медленнее") || lower.contains("медленнее говори") || lower.contains("замедли речь")) {
            return CommandResult(spokenResponse = "Хорошо, буду говорить медленнее.", speechRate = 0.8f)
        }
        if (lower.contains("обычная скорость") || lower.contains("нормальная скорость речи")) {
            return CommandResult(spokenResponse = "Возвращаю обычную скорость речи.", speechRate = 1.0f)
        }

        // 5. Flashlight (torch)
        if (lower.contains("фонарик") || lower.contains("вспышк") ||
            lower in listOf("включи свет", "выключи свет", "посвети", "погаси свет")
        ) {
            val turnOff = lower.contains("выключ") || lower.contains("отключ") || lower.contains("погаси")
            return CommandResult(toggleFlashlight(!turnOff))
        }

        // 6. Volume control
        handleVolumeCommand(lower, original)?.let { return CommandResult(it) }

        // 7. Battery level
        if (lower.contains("заряд") || lower.contains("батаре") || lower.contains("аккумулятор")) {
            if (!lower.contains("настройк")) {
                return CommandResult(getBatteryStatus())
            }
        }

        // 8. Network / Internet status
        if (lower.contains("есть ли интернет") || lower.contains("статус сети") ||
            lower.contains("проверь интернет") || lower.contains("подключение к интернету")
        ) {
            return CommandResult(getNetworkStatus())
        }

        // 9. Device info
        if (lower.contains("модель телефона") || lower.contains("какой у меня телефон") ||
            lower.contains("версия андроид") || lower.contains("версия android") ||
            lower.contains("об устройстве") || lower.contains("информация о телефоне")
        ) {
            val brand = Build.MANUFACTURER.replaceFirstChar { it.titlecase(ruLocale) }
            return CommandResult("Устройство $brand ${Build.MODEL}, версия Android ${Build.VERSION.RELEASE}.")
        }

        // 10. Alarms & Timers & Stopwatch & Calendar
        if (lower.contains("покажи будильник") || lower == "открой будильник" || lower == "будильники") {
            return CommandResult(openAlarms())
        }
        if (lower.contains("будильник") || lower.startsWith("разбуди ")) {
            return CommandResult(setAlarmFromSpeech(original))
        }
        if (lower.contains("секундомер") || lower.contains("покажи таймер")) {
            return CommandResult(openTimers())
        }
        if (lower.contains("таймер") || lower.startsWith("засеки ")) {
            return CommandResult(setTimerFromSpeech(original))
        }
        if (lower.startsWith("добавь в календарь") || lower.startsWith("создай событие") ||
            lower.startsWith("запланируй") || lower.startsWith("напомни ")
        ) {
            return CommandResult(createCalendarEvent(original))
        }
        if (lower.contains("открой календарь") || lower == "календарь") {
            return CommandResult(openCalendar())
        }

        // 11. Shopping list & Voice notes & Clipboard
        handleShoppingList(lower, original)?.let { return CommandResult(it) }
        handleNotes(lower, original)?.let { return CommandResult(it) }
        handleClipboardAndShare(lower, original)?.let { return CommandResult(it) }

        // 12. Weather (Online via Open-Meteo)
        val setCityMatch = Regex("^(?:мой город|установи город|запомни город)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(original)
        if (setCityMatch != null) {
            val saved = WeatherClient.saveCity(context, setCityMatch.groupValues[1])
            return CommandResult("Запомнил ваш город: $saved. Теперь по команде «какая погода» буду проверять его.")
        }
        if (lower.contains("погод") || lower.contains("сколько градусов на улице") || lower.contains("температура на улице")) {
            val cityMatch = Regex("погод\\w*\\s+(?:в|во|на)\\s+([а-яёa-z\\-\\s]+)", RegexOption.IGNORE_CASE)
                .find(original)
            val city = cityMatch?.groupValues?.get(1)?.trim()
            WeatherClient.fetchWeatherAsync(context, city) { forecast ->
                onAsyncReply(original, forecast)
            }
            val labelCity = if (city.isNullOrBlank()) WeatherClient.getSavedCity(context) else WeatherClient.normalizeCityCandidate(city)
            return CommandResult(
                spokenResponse = "Проверяю погоду в городе $labelCity…",
                asyncPending = true
            )
        }

        // 13. Spoken Math / Calculator (before generic "сколько"/"что")
        RussianNumberParser.tryEvaluateMath(original)?.let { mathAnswer ->
            return CommandResult(mathAnswer)
        }

        // 14. Time, Date, Day of week, New Year countdown
        if (lower.contains("сколько дней до нового года") || lower.contains("когда новый год")) {
            return CommandResult(getDaysUntilNewYear())
        }
        if (lower.contains("день недели") || lower == "какой сегодня день" || lower == "какой завтра день") {
            return CommandResult(getDayOfWeekInfo(lower))
        }
        if (lower.contains("котор") && lower.contains("час") ||
            lower.contains("сколько врем") ||
            lower.contains("скажи время") ||
            lower.contains("текущее время") ||
            lower == "время"
        ) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            return CommandResult("Сейчас $time.")
        }
        if (lower.contains("какая сегодня дата") || lower.contains("какое сегодня число") ||
            lower.contains("какая дата") || lower.contains("какое число") ||
            lower.contains("сегодняшняя дата") || lower == "дата" || lower == "число"
        ) {
            val date = SimpleDateFormat("d MMMM yyyy 'года', EEEE", ruLocale).format(Date())
            return CommandResult("Сегодня $date.")
        }

        // 15. Calls & SMS
        if (lower.startsWith("позвони") || lower.startsWith("набери")) {
            return CommandResult(handleDialCommand(original))
        }
        if (lower.startsWith("напиши смс") || lower.startsWith("отправь смс") ||
            lower.startsWith("напиши сообщение") || lower.startsWith("отправь сообщение")
        ) {
            return CommandResult(handleSmsCommand(original))
        }

        // 16. Camera (photo / video)
        if (lower.contains("сделай фото") || lower.contains("сфотографируй") ||
            lower.contains("открой камер") || lower == "камера" || lower == "селфи"
        ) {
            return CommandResult(openCamera(video = false))
        }
        if (lower.contains("сними видео") || lower.contains("запиши видео") || lower.contains("видеокамер")) {
            return CommandResult(openCamera(video = true))
        }

        // 17. Specific system settings
        handleSettingsCommand(lower)?.let { return CommandResult(it) }

        // 18. YouTube & Music
        handleYouTubeOrMusic(lower, original)?.let { return CommandResult(it) }

        // 19. Maps, Navigation & Nearby places
        handleMapsCommand(lower, original)?.let { return CommandResult(it) }

        // 20. Translator
        handleTranslateCommand(lower, original)?.let { return CommandResult(it) }

        // 21. Wikipedia
        val wikiMatch = Regex("^(?:найди в википедии|поищи в википедии|википедия|открой в википедии)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(original)
        if (wikiMatch != null) {
            val query = wikiMatch.groupValues[1].trim()
            val encoded = URLEncoder.encode(query, "UTF-8")
            openUrl("https://ru.m.wikipedia.org/wiki/Special:Search?search=$encoded")
            return CommandResult("Открываю Википедию по запросу «$query».")
        }

        // 22. Open installed app or built-in tool ("открой ...", "запусти ...")
        val openMatch = Regex("^(?:открой|запусти|включи приложение)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(original)
        if (openMatch != null) {
            val target = openMatch.groupValues[1].trim()
            return CommandResult(openAppOrShortcut(target))
        }

        // 23. Games, Random, Jokes, Facts, Advice
        if (lower.contains("монетк") || lower.contains("орел или решка")) {
            val res = if (Random.nextBoolean()) "Выпал орёл!" else "Выпала решка!"
            return CommandResult(res)
        }
        if (lower.contains("кубик") || lower.contains("кинь кости") || lower.contains("брось кости")) {
            val die = Random.nextInt(1, 7)
            return CommandResult("На кубике выпало число $die.")
        }
        if (lower.contains("случайное число") || lower.contains("загадай число") || lower.contains("рандомное число")) {
            return CommandResult(generateRandomNumber(original))
        }
        if (lower == "да или нет" || lower.contains("прими решение")) {
            val answers = listOf("Однозначно да!", "Скорее да.", "Думаю, нет.", "Лучше подождать.", "Определённо нет.")
            return CommandResult(answers.random())
        }
        if (lower.contains("анекдот") || lower.contains("пошути") || lower.contains("шутк") || lower.contains("рассмеши")) {
            return CommandResult(jokes.random())
        }
        if (lower.contains("факт") || lower.contains("что-нибудь интересное")) {
            return CommandResult(facts.random())
        }
        if (lower.contains("мотивац") || lower.contains("дай совет") || lower.contains("цитат")) {
            return CommandResult(quotes.random())
        }

        // 24. Help & Capabilities
        if (lower.contains("что ты умеешь") || lower.contains("список команд") ||
            lower.contains("какие команды") || lower == "помощь" || lower == "команды" || lower == "справка"
        ) {
            return CommandResult(
                "Я умею управлять фонариком и громкостью, ставить будильники и таймеры, открывать любые приложения, камеру и настройки, озвучивать погоду и заряд батареи, считать примеры, запоминать заметки и список покупок, искать в Google, YouTube, Википедии и на картах."
            )
        }

        // 25. Web Search ("найди ...", "загугли ...", "что такое ...", "кто такой ...", "как ...")
        handleWebSearch(lower, original)?.let { return CommandResult(it) }

        // 26. Conversational phrases
        handleConversation(lower)?.let { return CommandResult(it) }

        return CommandResult(
            spokenResponse = "Не удалось распознать команду «$original». Скажите «что ты умеешь» или «найди $original».",
            matched = false
        )
    }

    private fun toggleFlashlight(enable: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "На этом устройстве не найдена вспышка."
            cm.setTorchMode(cameraId, enable)
            if (enable) "Фонарик включён." else "Фонарик выключен."
        } catch (_: Exception) {
            "Не удалось переключить фонарик."
        }
    }

    private fun handleVolumeCommand(lower: String, original: String): String? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        // Exact percentage: "громкость на 50 процентов" / "громкость 70"
        if (lower.contains("громкость")) {
            val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
            val pctMatch = Regex("(\\d{1,3})").find(normalized)
            if (pctMatch != null) {
                val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
                if (pct != null) {
                    val target = ((pct / 100.0) * maxVol).toInt().coerceIn(0, maxVol)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    return "Громкость установлена на $pct процентов."
                }
            }
        }

        if (lower.contains("максимальн") && lower.contains("громк") ||
            lower.contains("звук на максимум") || lower == "на полную громкость"
        ) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
            return "Громкость на максимуме."
        }

        if (lower in listOf("без звука", "выключи звук", "отключи звук", "убери звук") ||
            lower.contains("беззвучный режим")
        ) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            return "Звук мультимедиа выключен."
        }

        if (lower == "включи звук" || lower == "верни звук") {
            val target = (maxVol * 0.6).toInt().coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            return "Звук включён."
        }

        if (lower in listOf("громче", "погромче", "прибавь звук", "увеличь громкость", "сделай громче", "прибавь громкость")) {
            val step = (maxVol * 0.2).toInt().coerceAtLeast(1)
            val next = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceAtMost(maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
            val pct = (next * 100) / maxVol
            return "Сделал громче: $pct процентов."
        }

        if (lower in listOf("тише", "потише", "убавь звук", "уменьши громкость", "сделай тише", "убавь громкость")) {
            val step = (maxVol * 0.2).toInt().coerceAtLeast(1)
            val next = (am.getStreamVolume(AudioManager.STREAM_MUSIC) - step).coerceAtLeast(0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
            val pct = (next * 100) / maxVol
            return "Сделал тише: $pct процентов."
        }

        return null
    }

    private fun getBatteryStatus(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val stateStr = if (isCharging) "телефон заряжается" else "работает от батареи"
        return if (level in 0..100) {
            "Заряд батареи $level%, $stateStr."
        } else {
            "Не удалось определить точный уровень заряда."
        }
    }

    private fun getNetworkStatus(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "Подключение к интернету отсутствует."
            val caps = cm.getNetworkCapabilities(net) ?: return "Сеть недоступна."
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "по Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "через мобильную сеть"
                else -> "к сети"
            }
            if (hasInternet) "Интернет подключён $type." else "Подключено $type, но доступ в интернет ограничен."
        } catch (_: Exception) {
            "Не удалось проверить состояние сети."
        }
    }

    private fun setAlarmFromSpeech(original: String): String {
        val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
        // Match "7:30" or "7 30" or "7 часов 30 минут" or "7"
        val colonMatch = Regex("(\\d{1,2})[:.](\\d{2})").find(normalized)
        var hour: Int? = null
        var minute = 0

        if (colonMatch != null) {
            hour = colonMatch.groupValues[1].toIntOrNull()
            minute = colonMatch.groupValues[2].toIntOrNull() ?: 0
        } else {
            val nums = Regex("\\b(\\d{1,2})\\b").findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            if (nums.isNotEmpty()) {
                hour = nums[0]
                if (nums.size >= 2) {
                    minute = nums[1]
                }
            }
        }

        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            return "Назовите время для будильника, например: поставь будильник на 7 часов 30 минут."
        }

        if ((normalized.contains("вечера") || normalized.contains("дня")) && hour in 1..11) {
            hour += 12
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Голосовой помощник")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val minFormatted = minute.toString().padStart(2, '0')
        return if (safeStart(intent)) {
            "Будильник установлен на $hour:$minFormatted."
        } else {
            "Не найдено приложение Часы для установки будильника."
        }
    }

    private fun openAlarms(): String {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (safeStart(intent)) "Открываю список будильников." else "Не удалось открыть будильники."
    }

    private fun openTimers(): String {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(AlarmClock.ACTION_SHOW_TIMERS)
        } else {
            Intent(AlarmClock.ACTION_SET_TIMER)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (safeStart(intent)) "Открываю таймеры." else "Не удалось открыть таймеры."
    }

    private fun setTimerFromSpeech(original: String): String {
        val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
        var totalSeconds = 0

        val hoursMatch = Regex("(\\d+)\\s*час").find(normalized)
        val minsMatch = Regex("(\\d+)\\s*мин").find(normalized)
        val secsMatch = Regex("(\\d+)\\s*сек").find(normalized)

        if (hoursMatch != null) totalSeconds += (hoursMatch.groupValues[1].toIntOrNull() ?: 0) * 3600
        if (minsMatch != null) totalSeconds += (minsMatch.groupValues[1].toIntOrNull() ?: 0) * 60
        if (secsMatch != null) totalSeconds += (secsMatch.groupValues[1].toIntOrNull() ?: 0)

        if (totalSeconds == 0) {
            val anyNum = Regex("(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (anyNum != null && anyNum > 0) {
                totalSeconds = anyNum * 60
            }
        }

        if (totalSeconds <= 0) {
            return "Укажите время таймера, например: поставь таймер на 5 минут."
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Таймер помощника")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val desc = when {
            totalSeconds % 3600 == 0 -> "${totalSeconds / 3600} ч."
            totalSeconds >= 60 && totalSeconds % 60 == 0 -> "${totalSeconds / 60} мин."
            totalSeconds > 60 -> "${totalSeconds / 60} мин. ${totalSeconds % 60} сек."
            else -> "$totalSeconds сек."
        }
        return if (safeStart(intent)) {
            "Таймер на $desc запущен."
        } else {
            "Не найдено приложение Часы для запуска таймера."
        }
    }

    private fun createCalendarEvent(original: String): String {
        val title = original.replace(
            Regex("^(?:добавь в календарь|создай событие|запланируй|напомни)\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim().ifEmpty { "Напоминание" }

        val startMillis = System.currentTimeMillis() + 3600_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3600_000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (safeStart(intent)) {
            "Открываю календарь для сохранения события «$title»."
        } else {
            "Не удалось открыть календарь."
        }
    }

    private fun openCalendar(): String {
        val uri = Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (safeStart(intent)) "Открываю календарь." else "Приложение Календарь не найдено."
    }

    private fun handleShoppingList(lower: String, original: String): String? {
        if (lower.startsWith("добавь в список покупок") || lower.startsWith("добавь в покупки") || lower.startsWith("нужно купить ")) {
            val item = original.replace(
                Regex("^(?:добавь в список покупок|добавь в покупки|нужно купить)\\s*", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (item.isEmpty()) return "Скажите, что именно добавить в список покупок."
            val list = loadStringList("shopping_list")
            list.add(item)
            saveStringList("shopping_list", list)
            return "Добавил в список покупок: $item."
        }
        if (lower.contains("список покупок") && (lower.contains("прочитай") || lower.contains("покажи") || lower.contains("что в") || lower == "список покупок")) {
            val list = loadStringList("shopping_list")
            return if (list.isEmpty()) {
                "Ваш список покупок пуст."
            } else {
                "В списке покупок (${list.size}): ${list.joinToString(", ")}."
            }
        }
        if (lower.contains("очисти список покупок") || lower.contains("удали список покупок")) {
            saveStringList("shopping_list", mutableListOf())
            return "Список покупок очищен."
        }
        return null
    }

    private fun handleNotes(lower: String, original: String): String? {
        if (lower.startsWith("запомни ") || lower.startsWith("запиши заметку") ||
            lower.startsWith("добавь заметку") || lower.startsWith("сохрани заметку")
        ) {
            val note = original.replace(
                Regex("^(?:запомни|запиши заметку|добавь заметку|сохрани заметку)\\s*", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (note.isEmpty()) return "Продиктуйте текст заметки после слова «запомни»."
            val notes = loadStringList("voice_notes")
            notes.add(note)
            saveStringList("voice_notes", notes)
            return "Записал заметку: «$note»."
        }
        if (lower.contains("прочитай заметк") || lower.contains("мои заметк") ||
            lower.contains("покажи заметк") || lower.contains("что я просил запомнить") || lower == "заметки"
        ) {
            val notes = loadStringList("voice_notes")
            return if (notes.isEmpty()) {
                "Сохранённых заметок пока нет. Скажите «запомни» и нужный текст."
            } else {
                val formatted = notes.mapIndexed { idx, s -> "${idx + 1}) $s" }.joinToString("; ")
                "Ваши заметки: $formatted."
            }
        }
        if (lower.contains("удали последнюю заметку")) {
            val notes = loadStringList("voice_notes")
            if (notes.isEmpty()) return "Заметок нет."
            val removed = notes.removeAt(notes.size - 1)
            saveStringList("voice_notes", notes)
            return "Удалена заметка: «$removed»."
        }
        if (lower.contains("очисти заметк") || lower.contains("удали все заметк")) {
            saveStringList("voice_notes", mutableListOf())
            return "Все заметки удалены."
        }
        return null
    }

    private fun handleClipboardAndShare(lower: String, original: String): String? {
        if (lower.startsWith("скопируй ")) {
            val text = original.replace(Regex("^скопируй(?:\\s+текст)?\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (text.isNotEmpty()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Голосовой ввод", text))
                return "Текст скопирован в буфер обмена."
            }
        }
        if (lower.startsWith("поделись текстом ") || lower.startsWith("отправь текст ")) {
            val text = original.replace(Regex("^(?:поделись текстом|отправь текст)\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (text.isNotEmpty()) {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(sendIntent, "Отправить через").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                safeStart(chooser)
                return "Выберите приложение для отправки текста."
            }
        }
        return null
    }

    private fun getDaysUntilNewYear(): String {
        val now = Calendar.getInstance()
        val nextYear = now.get(Calendar.YEAR) + 1
        val ny = Calendar.getInstance().apply {
            set(nextYear, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((ny.timeInMillis - now.timeInMillis) / (24L * 3600_000L)).toInt()
        return "До Нового $nextYear года осталось $diffDays дней."
    }

    private fun getDayOfWeekInfo(lower: String): String {
        val cal = Calendar.getInstance()
        val prefix = when {
            lower.contains("послезавтра") -> {
                cal.add(Calendar.DAY_OF_YEAR, 2)
                "Послезавтра"
            }
            lower.contains("завтра") -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                "Завтра"
            }
            else -> "Сегодня"
        }
        val dayName = SimpleDateFormat("EEEE, d MMMM", ruLocale).format(cal.time)
        return "$prefix $dayName."
    }

    private fun handleDialCommand(original: String): String {
        val digits = original.filter { it.isDigit() || it == '+' }
        return if (digits.length >= 3) {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (safeStart(intent)) "Открываю набор номера $digits." else "Не удалось открыть телефон."
        } else {
            val contactsIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (safeStart(contactsIntent)) {
                "Открываю контакты для вызова."
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                safeStart(dialIntent)
                "Открываю набор номера."
            }
        }
    }

    private fun handleSmsCommand(original: String): String {
        val body = original.replace(
            Regex("^(?:напиши|отправь)\\s+(?:смс|сообщение)\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
            if (body.isNotEmpty()) {
                putExtra("sms_body", body)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (safeStart(intent)) {
            if (body.isNotEmpty()) "Открываю СМС с вашим текстом." else "Открываю сообщения."
        } else {
            "Не удалось открыть приложение сообщений."
        }
    }

    private fun openCamera(video: Boolean): String {
        val action = if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return if (safeStart(intent)) {
            if (video) "Открываю видеокамеру." else "Открываю камеру."
        } else {
            "Не удалось открыть камеру."
        }
    }

    private fun handleSettingsCommand(lower: String): String? {
        if (!lower.contains("настройк") &&
            !lower.contains("вайфай") && !lower.contains("wi-fi") &&
            !lower.contains("блютуз") && !lower.contains("bluetooth") &&
            !lower.contains("авиарежим") && !lower.contains("режим полета")
        ) {
            return null
        }

        val (action, label) = when {
            lower.contains("вайфай") || lower.contains("вай-фай") || lower.contains("wi-fi") || lower.contains("wifi") ->
                Settings.ACTION_WIFI_SETTINGS to "Открываю настройки Wi-Fi."
            lower.contains("блютуз") || lower.contains("bluetooth") ->
                Settings.ACTION_BLUETOOTH_SETTINGS to "Открываю настройки Bluetooth."
            lower.contains("звук") ->
                Settings.ACTION_SOUND_SETTINGS to "Открываю настройки звука."
            lower.contains("экран") || lower.contains("яркост") ->
                Settings.ACTION_DISPLAY_SETTINGS to "Открываю настройки экрана."
            lower.contains("батаре") || lower.contains("энергосбереж") ->
                Settings.ACTION_BATTERY_SAVER_SETTINGS to "Открываю настройки батареи."
            lower.contains("приложен") ->
                Settings.ACTION_APPLICATION_SETTINGS to "Открываю настройки приложений."
            lower.contains("локац") || lower.contains("геолокац") || lower.contains("gps") || lower.contains("местополож") ->
                Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Открываю настройки геолокации."
            lower.contains("авиарежим") || lower.contains("режим полета") ->
                Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Открываю настройки режима полёта."
            lower.contains("мобильн") || lower.contains("передач") ->
                Settings.ACTION_DATA_ROAMING_SETTINGS to "Открываю настройки мобильной сети."
            lower.contains("настройк") ->
                Settings.ACTION_SETTINGS to "Открываю настройки телефона."
            else -> return null
        }

        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return if (safeStart(intent)) {
            label
        } else {
            safeStart(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Открываю основные настройки."
        }
    }

    private fun handleYouTubeOrMusic(lower: String, original: String): String? {
        val ytMatch = Regex(
            "^(?:включи на ютубе|найди на ютубе|включи на youtube|найди на youtube|поищи на ютубе|найди видео|включи видео|включи песню|поставь песню|включи музыку)\\s*(.*)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val query = ytMatch.groupValues[1].trim()
        if (query.isEmpty()) {
            openUrl("https://m.youtube.com")
            return "Открываю YouTube."
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        openUrl("https://www.youtube.com/results?search_query=$encoded")
        return "Ищу на YouTube: «$query»."
    }

    private fun handleMapsCommand(lower: String, original: String): String? {
        val nearbyPlaces = mapOf(
            "аптек" to "аптека",
            "магазин" to "продуктовый магазин",
            "супермаркет" to "супермаркет",
            "кафе" to "кафе",
            "ресторан" to "ресторан",
            "заправк" to "АЗС",
            "азс" to "АЗС",
            "банкомат" to "банкомат",
            "больниц" to "больница"
        )
        if (lower.contains("ближайш") || lower.contains("рядом")) {
            for ((stem, placeQuery) in nearbyPlaces) {
                if (lower.contains(stem)) {
                    openGeoQuery(placeQuery)
                    return "Ищу на карте поблизости: $placeQuery."
                }
            }
        }

        val mapMatch = Regex(
            "^(?:покажи на карте|найди на карте|где находится|построй маршрут до|построй маршрут в|маршрут до|маршрут в|навигатор до)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val dest = mapMatch.groupValues[1].trim()
        openGeoQuery(dest)
        return "Открываю карты: «$dest»."
    }

    private fun openGeoQuery(query: String) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!safeStart(geoIntent)) {
            openUrl("https://www.google.com/maps/search/?api=1&query=$encoded")
        }
    }

    private fun handleTranslateCommand(lower: String, original: String): String? {
        if (lower == "переводчик") {
            openUrl("https://translate.google.com/?sl=ru&tl=en")
            return "Открываю Google Переводчик."
        }
        val trMatch = Regex(
            "^переведи(?:\\s+на\\s+(английский|казахский|немецкий|французский|испанский|турецкий|китайский|русский))?\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val langWord = trMatch.groupValues[1].lowercase(ruLocale)
        val phrase = trMatch.groupValues[2].trim()
        val tl = when (langWord) {
            "казахский" -> "kk"
            "немецкий" -> "de"
            "французский" -> "fr"
            "испанский" -> "es"
            "турецкий" -> "tr"
            "китайский" -> "zh-CN"
            "русский" -> "ru"
            else -> "en"
        }
        val encoded = URLEncoder.encode(phrase, "UTF-8")
        openUrl("https://translate.google.com/?sl=auto&tl=$tl&text=$encoded&op=translate")
        return "Перевожу фразу «$phrase»."
    }

    @Suppress("DEPRECATION")
    private fun openAppOrShortcut(rawTarget: String): String {
        val target = rawTarget.lowercase(ruLocale).replace('ё', 'е').trim(' ', '.', ',', '!', '?')

        // Built-in quick shortcuts
        when {
            target.contains("галере") || target == "фото" || target == "фотографии" -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (safeStart(intent)) return "Открываю галерею."
            }
            target.contains("контакт") -> {
                val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (safeStart(intent)) return "Открываю контакты."
            }
            target.contains("телефон") || target.contains("звонилк") -> {
                val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (safeStart(intent)) return "Открываю телефон."
            }
            target.contains("калькулятор") -> {
                val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (safeStart(intent)) return "Открываю калькулятор."
            }
            target.contains("браузер") || target == "интернет" -> {
                openUrl("https://www.google.com")
                return "Открываю браузер."
            }
            target.contains("ютуб") || target == "youtube" -> {
                if (!launchByKeyword(listOf("youtube", "ютуб"))) {
                    openUrl("https://m.youtube.com")
                }
                return "Открываю YouTube."
            }
            target.contains("карт") || target.contains("навигатор") -> {
                if (!launchByKeyword(listOf("maps", "карты", "2gis", "2гис", "навигатор"))) {
                    openGeoQuery("")
                }
                return "Открываю карты."
            }
        }

        // Alias table for popular apps in RU/KZ
        val aliases: Map<String, List<String>> = mapOf(
            "ватсап" to listOf("whatsapp", "ватсап"),
            "вотсап" to listOf("whatsapp"),
            "whatsapp" to listOf("whatsapp"),
            "телеграм" to listOf("telegram", "телеграм"),
            "телегу" to listOf("telegram", "телеграм"),
            "telegram" to listOf("telegram"),
            "каспи" to listOf("kaspi", "каспи"),
            "kaspi" to listOf("kaspi"),
            "2гис" to listOf("2gis", "2гис", "дубльгис"),
            "дубльгис" to listOf("2gis", "2гис"),
            "инстаграм" to listOf("instagram", "инстаграм"),
            "тикток" to listOf("tiktok", "тик ток"),
            "тик ток" to listOf("tiktok"),
            "вконтакте" to listOf("vk", "вконтакте"),
            "вк" to listOf("vk", "вконтакте"),
            "хром" to listOf("chrome", "хром"),
            "плей маркет" to listOf("play маркет", "google play", "vending"),
            "маркет" to listOf("play маркет", "google play"),
            "яндекс" to listOf("яндекс", "yandex"),
            "почт" to listOf("gmail", "почта", "mail")
        )

        val searchTerms = mutableListOf(target)
        for ((key, vals) in aliases) {
            if (target.contains(key)) {
                searchTerms.addAll(vals)
            }
        }

        if (launchByKeyword(searchTerms.distinct())) {
            return "Открываю «$rawTarget»."
        }

        return "Приложение «$rawTarget» не найдено на телефоне."
    }

    @Suppress("DEPRECATION")
    private fun launchByKeyword(keywords: List<String>): Boolean {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val match = apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString().lowercase(ruLocale).replace('ё', 'е')
                val pkg = resolveInfo.activityInfo.packageName.lowercase(Locale.US)
                keywords.any { kw -> kw.isNotEmpty() && (label.contains(kw) || pkg.contains(kw)) }
            } ?: return false

            val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return false
            safeStart(launchIntent)
        } catch (_: Exception) {
            false
        }
    }

    private fun handleWebSearch(lower: String, original: String): String? {
        val searchMatch = Regex(
            "^(?:найди в интернете|поищи в интернете|найди в гугле|загугли|гугл|поищи|найди|поиск|что такое|кто такой|кто такая|как приготовить|почему|зачем)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val query = if (lower.startsWith("что такое ") || lower.startsWith("кто так") ||
            lower.startsWith("как приготовить ") || lower.startsWith("почему ") || lower.startsWith("зачем ")
        ) {
            original.trim()
        } else {
            searchMatch.groupValues[1].trim()
        }

        if (query.isEmpty()) return null
        val encoded = URLEncoder.encode(query, "UTF-8")
        openUrl("https://www.google.com/search?q=$encoded")
        return "Ищу в Google: «$query»."
    }

    private fun generateRandomNumber(original: String): String {
        val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
        val nums = Regex("(\\d+)").findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val (min, max) = if (nums.size >= 2) {
            val a = nums[0]
            val b = nums[1]
            if (a <= b) a to b else b to a
        } else if (nums.size == 1 && nums[0] > 1) {
            1 to nums[0]
        } else {
            1 to 100
        }
        val picked = if (min == max) min else Random.nextInt(min, max + 1)
        return "Случайное число от $min до $max: $picked."
    }

    private fun handleConversation(lower: String): String? {
        return when {
            lower.contains("доброе утро") -> "Доброе утро! Отличного вам дня. Чем помочь?"
            lower.contains("добрый день") -> "Добрый день! Готов к работе."
            lower.contains("добрый вечер") -> "Добрый вечер! Чем могу быть полезен?"
            lower.contains("спокойной ночи") || lower.contains("доброй ночи") -> "Спокойной ночи и приятных снов!"
            lower.contains("привет") || lower.contains("здравствуй") || lower == "салам" || lower == "алло" ->
                "Привет! Слушаю вас напрямую — говорите любую команду."
            lower.contains("как дела") || lower.contains("как ты") || lower.contains("как настроение") ->
                "Всё отлично, работаю на полную мощность! Чем могу помочь?"
            lower.contains("спасибо") || lower.contains("благодарю") ->
                "Всегда пожалуйста! Обращайтесь."
            lower.contains("молодец") || lower.contains("круто") || lower.contains("отлично") || lower.contains("супер") ->
                "Спасибо, рад стараться!"
            lower.contains("кто ты") || lower.contains("как тебя зовут") ->
                "Я ваш голосовой помощник для Android с прямым распознаванием русской речи."
            lower == "пока" || lower == "до свидания" ->
                "До свидания! Если нужно отключить фоновый режим, скажите «останови помощника»."
            else -> null
        }
    }

    private fun openUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return safeStart(intent)
    }

    private fun safeStart(intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun loadStringList(key: String): MutableList<String> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { idx -> arr.getString(idx) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        private val jokes = listOf(
            "Программист ставит на тумбочку два стакана: один с водой — если захочет пить ночью, а второй пустой — если не захочет.",
            "Жена говорит мужу-программисту: сходи в магазин за хлебом, и если будут яйца — возьми десяток. Он вернулся с десятью батонами хлеба: яйца были!",
            "Почему смартфоны никогда не мерзнут зимой? Потому что на них открыто слишком много вкладок!",
            "Будильник — единственное устройство, которое ругают за то, что оно исправно работает.",
            "Только русский человек может сказать: да нет наверное — и все его прекрасно поймут!",
            "Спросил у навигатора кратчайший путь к успеху — он ответил: маршрут перестроен, продолжайте движение прямо."
        )

        private val facts = listOf(
            "Сердце синего кита настолько велико, что его биение можно услышать под водой за три километра.",
            "На Венере сутки длятся дольше, чем целый венерианский год.",
            "Мёд — один из немногих продуктов, который не портится даже спустя тысячи лет.",
            "Осьминоги имеют три сердца и голубую кровь.",
            "Свет от Солнца долетает до Земли примерно за восемь минут и двадцать секунд.",
            "Человеческий мозг генерирует достаточно электричества, чтобы зажечь небольшую светодиодную лампочку."
        )

        private val quotes = listOf(
            "Лучший момент, чтобы начать — прямо сейчас. Маленький шаг сегодня лучше идеального плана завтра.",
            "Дорогу осилит идущий: сосредоточьтесь на одном главном деле, и день пройдёт не зря.",
            "Не бойтесь сложных задач — именно на них быстрее всего растут навыки."
        )
    }
}
