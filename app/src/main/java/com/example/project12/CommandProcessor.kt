package com.example.project12

import android.app.ActivityManager
import android.app.DownloadManager
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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    fun stripWakeWordPrefix(raw: String): Pair<Boolean, String> {
        val trimmed = raw.trim()
        val regex = Regex(
            "^(?:эй\\s+|привет\\s+|слушай\\s+|алло\\s+)?(?:нелли|нели|нэлли|нэли|нелля|неля|нелле|нелличка|nelly|neli|помощница|помощник|ассистент|бот)[,\\s:!-]*(.*)$",
            RegexOption.IGNORE_CASE
        )
        val m = regex.find(trimmed)
        if (m != null) {
            return true to m.groupValues[1].trim()
        }

        // Phonetic wake-word tolerance (e.g. ASR hears "неле, включи ватсап")
        val spaceIdx = trimmed.indexOf(' ')
        val firstToken = (if (spaceIdx > 0) trimmed.substring(0, spaceIdx) else trimmed)
            .trim(' ', ',', '.', '!', '?', ':', '-')
        if (PhoneticSimilarity.wordSimilarity(firstToken, "нелли") >= 0.80) {
            val rest = if (spaceIdx > 0) trimmed.substring(spaceIdx + 1).trim() else ""
            return true to rest
        }

        return false to trimmed
    }

    /**
     * Evaluates all speech-recognition candidates (N-best hypotheses) against local commands,
     * contact calling, and fuzzy app launching BEFORE falling back to free-form cloud answers.
     * This dramatically improves accuracy when a word's pronunciation is ambiguous!
     */
    fun processCandidates(candidates: List<String>): Pair<String, CommandResult> {
        val cleanedList = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanedList.isEmpty()) {
            return "" to CommandResult(spokenResponse = null, matched = false)
        }

        // Pass 1: Check ALL candidates locally (without cloud fallback) so candidate #2/#3 can rescue a misheard app or contact name!
        for (candidate in cleanedList) {
            val (hadWake, stripped) = stripWakeWordPrefix(candidate)
            val effective = if (hadWake && stripped.isNotEmpty()) stripped else candidate
            val localRes = processInternal(effective, allowCloudFallback = false)
            if (localRes.matched) {
                return candidate to localRes
            }
        }

        // Pass 2: None of the N-best candidates matched a phone command/app/contact -> answer primary via freeform AI / Wikipedia
        val primary = cleanedList.first()
        val (hadWake, stripped) = stripWakeWordPrefix(primary)
        val effective = if (hadWake && stripped.isNotEmpty()) stripped else primary
        return primary to processInternal(effective, allowCloudFallback = true)
    }

    fun process(rawInput: String): CommandResult {
        return processInternal(rawInput, allowCloudFallback = true)
    }

    private fun processInternal(rawInput: String, allowCloudFallback: Boolean): CommandResult {
        val userRaw = rawInput.trim()
        if (userRaw.isEmpty()) {
            return CommandResult(spokenResponse = null, matched = false)
        }

        // Step A: Multi-turn Conversational Context Resolution (follow-ups, active games, riddles, slots, math chain)
        when (val ctxRes = NaturalIntentEngine.resolveContextualTurn(userRaw)) {
            is ContextualResolution.DirectReply -> {
                ConversationContext.recordTurn(userRaw, ctxRes.text)
                return CommandResult(ctxRes.text)
            }
            is ContextualResolution.RewrittenCommand -> {
                val res = executeCore(ctxRes.command, originalUserDisplay = userRaw)
                if (res.matched) {
                    res.spokenResponse?.let { ConversationContext.recordTurn(userRaw, it) }
                    return res
                }
            }
            ContextualResolution.PassThrough -> Unit
        }

        // Step B: Strip polite fillers & normalize natural phrasing
        val strippedNatural = NaturalIntentEngine.stripConversationalFillers(userRaw)
        val lowerNatural = strippedNatural.lowercase(ruLocale).replace('ё', 'е').trim(' ', '.', ',', '!', '?')

        // Step C: Execute explicit or pronunciation-tolerant command matching
        val directRes = executeCore(strippedNatural, originalUserDisplay = userRaw)
        if (directRes.matched) {
            directRes.spokenResponse?.let { ConversationContext.recordTurn(userRaw, it) }
            return directRes
        }

        // Step D: Situational / Indirect Intent Inference ("тут темно", "брать зонт?", "телефон садится", "хочу есть")
        val inferred = NaturalIntentEngine.inferNaturalSituationIntent(lowerNatural, strippedNatural)
        if (inferred != null) {
            if (inferred == "__WEATHER_ADVICE__") {
                ConversationContext.lastAction = "WEATHER"
                WeatherClient.fetchWeatherAsync(context, null, ForecastMode.ADVICE) { advice ->
                    ConversationContext.recordTurn(userRaw, advice)
                    onAsyncReply(userRaw, advice)
                }
                return CommandResult(
                    spokenResponse = "Смотрю погоду за окном…",
                    asyncPending = true
                )
            }
            val inferredRes = executeCore(inferred, originalUserDisplay = userRaw)
            if (inferredRes.matched) {
                inferredRes.spokenResponse?.let { ConversationContext.recordTurn(userRaw, it) }
                return inferredRes
            }
        }

        if (!allowCloudFallback) {
            return CommandResult(spokenResponse = null, matched = false)
        }

        // Step E: Free-form Contextual Knowledge / AI Answer (when user asks an arbitrary question or speaks freely)
        KnowledgeAndCurrencyClient.answerFreeformQuestionAsync(strippedNatural) { smartReply ->
            ConversationContext.recordTurn(userRaw, smartReply)
            onAsyncReply(userRaw, smartReply)
        }
        return CommandResult(
            spokenResponse = "Секунду, сейчас отвечу…",
            asyncPending = true,
            matched = true
        )
    }

    private fun executeCore(commandInput: String, originalUserDisplay: String): CommandResult {
        val original = commandInput.trim()
        val lower = original.lowercase(ruLocale)
            .replace('ё', 'е')
            .trim(' ', '.', ',', '!', '?')

        // 1. Assistant Speech & Service Control
        if (lower in listOf("замолчи", "тихо", "хватит говорить", "стоп речь", "заткнись", "прекрати говорить")) {
            return CommandResult(spokenResponse = null, stopSpeakingOnly = true)
        }
        if (lower in listOf("стоп", "выключись", "отключись", "заверши работу", "спать") ||
            lower.contains("останови помощни") || lower.contains("выключи помощни") || lower.contains("отключи помощни") ||
            lower.contains("останови нелли") || lower.contains("выключи нелли")
        ) {
            return CommandResult(spokenResponse = "Выключаюсь. Зовите меня в любое время!", shouldStopService = true)
        }
        if (lower.contains("очисти историю") || lower.contains("сотри историю диалога")) {
            AssistantStateBus.clearHistory()
            return CommandResult("История диалога очищена.")
        }
        if (lower.contains("прямой режим") || lower.contains("слушай напрямую") || lower.contains("без ключевого слова") || lower.contains("без имени")) {
            AssistantStateBus.setDirectMode(context, true)
            return CommandResult("Включён прямой режим — говорите команды сразу своими словами.")
        }
        if (lower.contains("режим ключевого слова") || lower.contains("только по имени") ||
            lower.contains("только по слову нелли") || lower.contains("только по слову помощник")
        ) {
            AssistantStateBus.setDirectMode(context, false)
            return CommandResult("Хорошо! Теперь буду откликаться по имени «Нелли».")
        }
        if (lower.contains("говори быстрее") || lower.contains("быстрее говори") || lower.contains("ускорь речь")) {
            return CommandResult(spokenResponse = "Перехожу на быстрый темп речи.", speechRate = 1.35f)
        }
        if (lower.contains("говори медленнее") || lower.contains("медленнее говори") || lower.contains("замедли речь")) {
            return CommandResult(spokenResponse = "Буду говорить помедленнее.", speechRate = 0.8f)
        }
        if (lower.contains("обычная скорость") || lower.contains("нормальная скорость речи")) {
            return CommandResult(spokenResponse = "Возвращаю обычную скорость речи.", speechRate = 1.0f)
        }

        // 2. Flashlight (ON / OFF / Blink / SOS)
        if (lower.contains("помигай фонар") || lower.contains("мигни фонар") ||
            lower.contains("сигнал сос") || lower.contains("стробоскоп")
        ) {
            return CommandResult(blinkFlashlight(isSos = lower.contains("сос")))
        }
        if (lower.contains("фонарик") || lower.contains("фонарь") || lower.contains("вспышк") ||
            lower in listOf("включи свет", "выключи свет", "посвети", "погаси свет", "включи подсветку", "выключи подсветку")
        ) {
            val turnOff = lower.contains("выключ") || lower.contains("отключ") || lower.contains("погаси") || lower.contains("убери")
            return CommandResult(toggleFlashlight(!turnOff))
        }

        // 3. Vibration test
        if (lower.contains("проверь вибрац") || lower == "вибрация" || lower == "пожужжи" || lower.contains("включи вибро")) {
            return CommandResult(triggerVibration())
        }

        // 4. Media playback control (Play / Pause / Next / Previous track)
        handleMediaPlaybackKeys(lower)?.let { return CommandResult(it) }

        // 5. Volume controls (Media, Ringtone, Alarm, Current volume query)
        handleVolumeCommand(lower, original)?.let { return CommandResult(it) }

        // 6. Full Device Diagnostics: Battery, Temp, Storage, RAM, Network, IP, Uptime, Model, Android version
        handleDeviceDiagnostics(lower)?.let { return CommandResult(it) }

        // 7. Voice Stopwatch (built-in) & Voice Countdown Reminder & System Alarms/Timers/Calendar
        handleStopwatchAndTimersAndAlarms(lower, original, originalUserDisplay)?.let { return it }

        // 8. Currency Exchange Rates & Unit Converter & Discount/Tip/Split & Math
        handleCurrencyCommand(lower, original, originalUserDisplay)?.let { return it }
        RussianNumberParser.tryConvertUnits(original)?.let { return CommandResult(it) }
        RussianNumberParser.tryEvaluateDiscountOrSplit(original)?.let { return CommandResult(it) }
        RussianNumberParser.tryEvaluateMath(original)?.let { return CommandResult(it) }

        // 9. Lists: Shopping List, To-Do Tasks, Voice Notes, Password/PIN Generator, Clipboard & Share
        handleShoppingList(lower, original)?.let { return CommandResult(it) }
        handleTodoTasks(lower, original)?.let { return CommandResult(it) }
        handleNotes(lower, original)?.let { return CommandResult(it) }
        handlePasswordAndClipboard(lower, original)?.let { return CommandResult(it) }

        // 10. Weather (Today, Tomorrow, Clothing/Umbrella Advice, Set Home City)
        handleWeatherCommands(lower, original, originalUserDisplay)?.let { return it }

        // 11. Time (Local + World Cities), Date, Calendar math, Week number, Countdown to holidays
        handleTimeAndDateCommands(lower, original)?.let { return CommandResult(it) }

        // 12. Calls, Emergency Numbers, Contacts, SMS, WhatsApp/Telegram message, Email
        handleCommunicationCommands(lower, original)?.let { return CommandResult(it) }

        // 13. Camera (Photo / Video), Voice Recorder, Downloads
        if (lower.contains("сделай фото") || lower.contains("сфотографируй") || lower.contains("сфоткай") ||
            lower.contains("открой камер") || lower == "камера" || lower == "селфи"
        ) {
            return CommandResult(openCamera(video = false))
        }
        if (lower.contains("сними видео") || lower.contains("запиши видео") || lower.contains("видеокамер") || lower.contains("запиши ролик")) {
            return CommandResult(openCamera(video = true))
        }
        if (lower.contains("диктофон") || lower.contains("записать звук") || lower.contains("запиши голос")) {
            val intent = Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            return CommandResult(if (safeStart(intent)) "Открываю диктофон." else "Стандартный диктофон не найден.")
        }
        if (lower.contains("открой загрузк") || lower == "загрузки" || lower.contains("скачанные файлы")) {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            return CommandResult(if (safeStart(intent)) "Открываю папку загрузок." else "Не удалось открыть загрузки.")
        }

        // 14. YouTube Video/Song Search (only when searching a specific video/song)
        handleYouTubeOrMusic(lower, original)?.let { return CommandResult(it) }

        // 15. Pronunciation-Tolerant App Launcher (YouTube, WhatsApp, Telegram, Instagram, Kaspi, 2GIS, or ANY installed app!)
        AppLauncherEngine.tryLaunchFromUtterance(context, lower, original)?.let { return CommandResult(it) }

        // 16. System Settings (15 sections)
        handleSettingsCommand(lower)?.let { return CommandResult(it) }

        // 17. Maps, Navigation & 20+ Nearby Place types
        handleMapsCommand(lower, original)?.let { return CommandResult(it) }

        // 18. Translator (12 languages + slot filling if phrase omitted)
        handleTranslateCommand(lower, original)?.let { return CommandResult(it) }

        // 19. Spoken Wikipedia / Encyclopedia ("кто такой ...", "что такое ...", "расскажи про ...")
        handleWikipediaAndKnowledge(lower, original, originalUserDisplay)?.let { return it }

        // 20. Open Website / News / Images / Check Installed Apps
        handleSitesAndApps(lower, original)?.let { return CommandResult(it) }

        // 21. Interactive Games (Cities, Riddles), Randomizer, Choice Picker ("или"), Jokes, Facts, Advice, Breathing
        handleGamesAndFun(lower, original)?.let { return CommandResult(it) }

        // 22. Help & Capabilities
        if (lower.contains("что ты умеешь") || lower.contains("список команд") ||
            lower.contains("какие команды") || lower == "помощь" || lower == "команды" || lower == "справка" ||
            lower.contains("чему ты научилась") || lower.contains("чему ты научился")
        ) {
            return CommandResult(
                "Меня зовут Нелли! Я понимаю более ста действий и живую речь без шаблонов: звоню вашим контактам по имени («позвони маме», «включи звонок Саше»), открываю Ютуб, Ватсап, Телеграм, Инстаграм и любые приложения на телефоне даже при неточном произношении, считаю валюты и отвечаю на вопросы."
            )
        }

        // 22. Explicit Web Search ("найди ...", "загугли ...", "поищи ...")
        handleWebSearch(lower, original)?.let { return CommandResult(it) }

        // 23. Warm Human Conversation
        handleConversation(lower)?.let { return CommandResult(it) }

        return CommandResult(spokenResponse = null, matched = false)
    }

    private fun toggleFlashlight(enable: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "На этом устройстве нет вспышки."
            cm.setTorchMode(cameraId, enable)
            ConversationContext.flashlightOn = enable
            ConversationContext.lastAction = if (enable) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF"
            if (enable) "Фонарик включён." else "Фонарик выключен."
        } catch (_: Exception) {
            "Не удалось переключить фонарик."
        }
    }

    private fun blinkFlashlight(isSos: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return "Вспышка недоступна на устройстве."

        bgExecutor.execute {
            try {
                val durations = if (isSos) {
                    listOf(180L, 180L, 180L, 450L, 450L, 450L, 180L, 180L, 180L)
                } else {
                    listOf(220L, 220L, 220L)
                }
                for (ms in durations) {
                    cm.setTorchMode(cameraId, true)
                    Thread.sleep(ms)
                    cm.setTorchMode(cameraId, false)
                    Thread.sleep(160L)
                }
                ConversationContext.flashlightOn = false
            } catch (_: Exception) {
            }
        }
        return if (isSos) "Подаю световой сигнал СОС вспышкой." else "Моргаю фонариком."
    }

    @Suppress("DEPRECATION")
    private fun triggerVibration(): String {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1))
            } else {
                vibrator.vibrate(longArrayOf(0, 200, 100, 250), -1)
            }
            "Вибрация работает исправно."
        } catch (_: Exception) {
            "Не удалось включить вибромотор."
        }
    }

    private fun handleMediaPlaybackKeys(lower: String): String? {
        val keyCode = when {
            lower in listOf("пауза", "поставь на паузу", "останови музыку", "выключи музыку") ->
                KeyEvent.KEYCODE_MEDIA_PAUSE
            lower in listOf("продолжи музыку", "продолжи воспроизведение", "сними с паузы", "играй дальше") ->
                KeyEvent.KEYCODE_MEDIA_PLAY
            lower in listOf("следующий трек", "следующая песня", "переключи песню", "дальше песню", "скипни трек") ->
                KeyEvent.KEYCODE_MEDIA_NEXT
            lower in listOf("предыдущий трек", "предыдущая песня", "прошлая песня", "верни песню") ->
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return null
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Музыка поставлена на паузу."
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Продолжаю воспроизведение."
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Переключила на следующий трек."
                else -> "Вернула предыдущий трек."
            }
        } catch (_: Exception) {
            "Не удалось отправить команду плееру."
        }
    }

    private fun handleVolumeCommand(lower: String, original: String): String? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Query current volume
        if (lower.contains("какая сейчас громкость") || lower.contains("уровень громкости") ||
            lower.contains("на сколько включен звук")
        ) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            return "Сейчас громкость мультимедиа составляет ${(cur * 100) / max}%."
        }

        // Ringtone / Alarm stream targeting
        if (lower.contains("громкость звонка") || lower.contains("звонок громче") || lower.contains("звонок тише")) {
            val maxR = am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
            return try {
                val up = lower.contains("громче") || lower.contains("максимум") || lower.contains("прибав")
                val next = if (lower.contains("максимум")) maxR
                else if (up) (am.getStreamVolume(AudioManager.STREAM_RING) + 2).coerceAtMost(maxR)
                else (am.getStreamVolume(AudioManager.STREAM_RING) - 2).coerceAtLeast(0)
                am.setStreamVolume(AudioManager.STREAM_RING, next, AudioManager.FLAG_SHOW_UI)
                "Громкость звонка: ${(next * 100) / maxR}%."
            } catch (_: SecurityException) {
                "Телефон в режиме «Не беспокоить» — изменение звонка заблокировано системой."
            }
        }

        if (lower.contains("громкость будильника") || lower.contains("будильник громче") || lower.contains("будильник тише")) {
            val maxA = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
            val up = lower.contains("громче") || lower.contains("максимум") || lower.contains("прибав")
            val next = if (lower.contains("максимум")) maxA
            else if (up) (am.getStreamVolume(AudioManager.STREAM_ALARM) + 2).coerceAtMost(maxA)
            else (am.getStreamVolume(AudioManager.STREAM_ALARM) - 2).coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_ALARM, next, AudioManager.FLAG_SHOW_UI)
            return "Громкость будильника установлена на ${(next * 100) / maxA}%."
        }

        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        if (lower.contains("громкость на половин") || lower.contains("звук на половин") || lower.contains("средняя громкость")) {
            val target = maxVol / 2
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            return "Громкость установлена на 50 процентов."
        }

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
            lower.contains("звук на максимум") || lower.contains("на полную громкость") || lower == "на полную"
        ) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
            ConversationContext.lastAction = "VOLUME_UP"
            return "Громкость на максимуме — 100%."
        }

        if (lower in listOf("без звука", "выключи звук", "отключи звук", "убери звук") ||
            lower.contains("беззвучный режим")
        ) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            ConversationContext.lastAction = "VOLUME_MUTE"
            return "Звук выключен."
        }

        if (lower in listOf("включи звук", "верни звук", "верни громкость")) {
            val target = (maxVol * 0.6).toInt().coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            ConversationContext.lastAction = "VOLUME_UP"
            return "Звук включён на 60%."
        }

        if (lower in listOf("громче", "погромче", "прибавь звук", "увеличь громкость", "сделай громче", "прибавь громкость", "добавь громкости")) {
            val step = (maxVol * 0.2).toInt().coerceAtLeast(1)
            val next = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceAtMost(maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
            ConversationContext.lastAction = "VOLUME_UP"
            return "Сделала громче: ${(next * 100) / maxVol}%."
        }

        if (lower in listOf("тише", "потише", "убавь звук", "уменьши громкость", "сделай тише", "убавь громкость", "сделай потише")) {
            val step = (maxVol * 0.2).toInt().coerceAtLeast(1)
            val next = (am.getStreamVolume(AudioManager.STREAM_MUSIC) - step).coerceAtLeast(0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
            ConversationContext.lastAction = "VOLUME_DOWN"
            return "Сделала тише: ${(next * 100) / maxVol}%."
        }

        return null
    }

    private fun handleDeviceDiagnostics(lower: String): String? {
        if (lower.contains("диагностика телефона") || lower.contains("полный отчет") ||
            lower.contains("состояние телефона") || lower.contains("проверь телефон")
        ) {
            return "${getBatteryStatus(includeTemp = true)} ${getStorageStatus()} ${getRamStatus()} ${getNetworkStatus()}"
        }

        if (lower.contains("температура батаре") || lower.contains("температура телефон")) {
            return getBatteryStatus(includeTemp = true)
        }

        if ((lower.contains("заряд") || lower.contains("батаре") || lower.contains("аккумулятор")) &&
            !lower.contains("настройк") && !lower.contains("энергосбереж")
        ) {
            return getBatteryStatus(includeTemp = false)
        }

        if ((lower.contains("свободной памят") || lower.contains("сколько памяти") ||
                lower.contains("сколько места") || lower.contains("свободное место") ||
                lower.contains("сколько гигабайт")) && !lower.contains("оператив")
        ) {
            return getStorageStatus()
        }

        if (lower.contains("оперативная память") || lower.contains("оперативной памяти") ||
            lower == "озу" || lower.contains("сколько озу")
        ) {
            return getRamStatus()
        }

        if (lower.contains("айпи адрес") || lower.contains("мой ip") || lower.contains("какой у меня ip") || lower.contains("мой айпи")) {
            return getLocalIpAddress()
        }

        if (lower.contains("есть ли интернет") || lower.contains("статус сети") ||
            lower.contains("проверь интернет") || lower.contains("я на вайфае") || lower.contains("какая сеть")
        ) {
            return getNetworkStatus()
        }

        if (lower.contains("без перезагрузк") || lower.contains("аптайм") || lower.contains("сколько работает телефон")) {
            val mins = SystemClock.elapsedRealtime() / 60_000L
            val h = mins / 60
            val m = mins % 60
            return "Телефон работает без перезагрузки $h ч. $m мин."
        }

        if (lower.contains("часовой пояс")) {
            val tz = TimeZone.getDefault()
            val offsetHours = tz.rawOffset / 3600_000.0
            val sign = if (offsetHours >= 0) "+" else ""
            return "Ваш часовой пояс: ${tz.id} (UTC$sign${RussianNumberParser.formatDoubleRu(offsetHours)})."
        }

        if (lower.contains("язык системы")) {
            val loc = Locale.getDefault()
            return "Язык системы: ${loc.displayLanguage}, регион: ${loc.displayCountry}."
        }

        if (lower.contains("модель телефона") || lower.contains("какой у меня телефон") ||
            lower.contains("характеристики телефона") || lower.contains("процессор телефона") ||
            lower.contains("версия андроид") || lower.contains("версия android") ||
            lower.contains("об устройстве") || lower.contains("информация о телефоне")
        ) {
            val brand = Build.MANUFACTURER.replaceFirstChar { it.titlecase(ruLocale) }
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "ARM"
            return "Смартфон $brand ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (архитектура $abi, плата ${Build.HARDWARE})."
        }

        return null
    }

    private fun getBatteryStatus(includeTemp: Boolean): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val stateStr = if (isCharging) "телефон заряжается" else "работает от аккумулятора"
        val tempStr = if (includeTemp && tempTenths > 0) ", температура ${tempTenths / 10.0} °C" else ""
        return if (level in 0..100) {
            val lowHint = if (!isCharging && level <= 20) " Рекомендую подключить зарядку." else ""
            "Заряд батареи $level% ($stateStr$tempStr).$lowHint"
        } else {
            "Не удалось определить заряд батареи."
        }
    }

    private fun getStorageStatus(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
            "В памяти телефона свободно ${String.format(ruLocale, "%.1f", freeGb)} ГБ из ${String.format(ruLocale, "%.0f", totalGb)} ГБ."
        } catch (_: Exception) {
            "Не удалось проверить хранилище."
        }
    }

    private fun getRamStatus(): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
            val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            "Оперативная память: свободно ${String.format(ruLocale, "%.1f", availGb)} ГБ из ${String.format(ruLocale, "%.1f", totalGb)} ГБ."
        } catch (_: Exception) {
            "Не удалось прочитать данные ОЗУ."
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return "Ваш локальный IP-адрес: ${addr.hostAddress}."
                    }
                }
            }
            "Локальный IP-адрес не назначен (нет активной сети)."
        } catch (_: Exception) {
            "Не удалось определить IP-адрес."
        }
    }

    private fun getNetworkStatus(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "Интернет сейчас отключён."
            val caps = cm.getNetworkCapabilities(net) ?: return "Сеть недоступна."
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "по Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "через мобильную сеть"
                else -> "к сети"
            }
            if (hasInternet) "Интернет подключён $type." else "Подключено $type без выхода в интернет."
        } catch (_: Exception) {
            "Не удалось проверить сеть."
        }
    }

    private fun handleStopwatchAndTimersAndAlarms(
        lower: String,
        original: String,
        originalUserDisplay: String
    ): CommandResult? {
        // Built-in Voice Stopwatch!
        if (lower in listOf("запусти секундомер", "включи секундомер", "засеки время", "старт секундомера")) {
            ConversationContext.stopwatchStartElapsedMs = SystemClock.elapsedRealtime()
            return CommandResult("Секундомер запущен! Скажите «сколько прошло» или «останови секундомер», когда будете готовы.")
        }
        if (lower in listOf("сколько прошло", "сколько на секундомере", "время секундомера")) {
            val started = ConversationContext.stopwatchStartElapsedMs
                ?: return CommandResult("Секундомер сейчас не запущен. Скажите «засеки время».")
            val sec = (SystemClock.elapsedRealtime() - started) / 1000L
            return CommandResult("Прошло ${formatDurationRu(sec)}, секундомер продолжает идти.")
        }
        if (lower in listOf("останови секундомер", "стоп секундомер", "выключи секундомер")) {
            val started = ConversationContext.stopwatchStartElapsedMs
                ?: return CommandResult("Секундомер не был запущен.")
            val sec = (SystemClock.elapsedRealtime() - started) / 1000L
            ConversationContext.stopwatchStartElapsedMs = null
            return CommandResult("Секундомер остановлен. Итоговое время: ${formatDurationRu(sec)}.")
        }

        // Built-in Spoken Mini-Reminder ("скажи через 30 секунд ...", "напомни через 5 минут ...")
        if ((lower.startsWith("скажи через ") || lower.startsWith("напомни через ")) &&
            (lower.contains("секунд") || lower.contains("минут"))
        ) {
            val secs = parseDurationSeconds(original)
            if (secs in 1..7200) {
                val reminderNote = original.replace(
                    Regex("^(?:скажи|напомни)\\s+через\\s+(?:\\S+\\s+){1,3}", RegexOption.IGNORE_CASE),
                    ""
                ).trim().ifEmpty { "Время вышло!" }
                mainHandler.postDelayed({
                    onAsyncReply("Напоминание", "Напоминаю: $reminderNote")
                }, secs * 1000L)
                return CommandResult("Хорошо! Озвучу напоминание через ${formatDurationRu(secs.toLong())}.")
            }
        }

        // Alarms
        if (lower.contains("покажи будильник") || lower == "будильники" || lower.contains("какие будильники")) {
            return CommandResult(openAlarms())
        }
        if (lower.startsWith("будильник через ") || lower.startsWith("разбуди через ") || lower.startsWith("разбуди меня через ")) {
            val secs = parseDurationSeconds(original)
            if (secs >= 60) {
                val cal = Calendar.getInstance().apply { add(Calendar.SECOND, secs) }
                return CommandResult(scheduleSystemAlarm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)))
            }
        }
        if (lower.contains("будильник") || lower.startsWith("разбуди")) {
            return CommandResult(setAlarmFromSpeech(original))
        }

        // Timers
        if (lower == "секундомер" || lower.contains("покажи таймер") || lower == "открой таймер") {
            return CommandResult(openTimers())
        }
        if (lower.contains("таймер") || lower.startsWith("засеки ")) {
            return CommandResult(setTimerFromSpeech(original))
        }

        // Calendar
        if (lower.startsWith("добавь в календарь") || lower.startsWith("создай событие") ||
            lower.startsWith("запланируй") || lower.startsWith("напомни ")
        ) {
            return CommandResult(createCalendarEvent(original))
        }
        if (lower.contains("открой календарь") || lower == "календарь" || lower == "моё расписание") {
            return CommandResult(openCalendar())
        }

        return null
    }

    private fun parseDurationSeconds(original: String): Int {
        val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
        var total = 0
        Regex("(\\d+)\\s*час").find(normalized)?.let { total += (it.groupValues[1].toIntOrNull() ?: 0) * 3600 }
        Regex("(\\d+)\\s*мин").find(normalized)?.let { total += (it.groupValues[1].toIntOrNull() ?: 0) * 60 }
        Regex("(\\d+)\\s*сек").find(normalized)?.let { total += (it.groupValues[1].toIntOrNull() ?: 0) }
        if (total == 0) {
            val anyNum = Regex("(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            if (anyNum != null && anyNum > 0) total = anyNum * 60
        }
        return total
    }

    private fun formatDurationRu(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("$h ч.")
        if (m > 0) parts.add("$m мин.")
        if (s > 0 || parts.isEmpty()) parts.add("$s сек.")
        return parts.joinToString(" ")
    }

    private fun setAlarmFromSpeech(original: String): String {
        val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
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
                if (nums.size >= 2) minute = nums[1]
            }
        }

        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            ConversationContext.setPendingSlot(PendingSlot.ALARM_TIME)
            return "На какое время поставить будильник? Например: на 7:30."
        }

        if ((normalized.contains("вечера") || normalized.contains("дня")) && hour in 1..11) {
            hour += 12
        }

        return scheduleSystemAlarm(hour, minute)
    }

    private fun scheduleSystemAlarm(hour: Int, minute: Int): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Голосовой помощник")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val minFormatted = minute.toString().padStart(2, '0')
        ConversationContext.lastAction = "ALARM"
        return if (safeStart(intent)) {
            "Будильник установлен на $hour:$minFormatted."
        } else {
            "Не найдено приложение Часы для будильника."
        }
    }

    private fun openAlarms(): String {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return if (safeStart(intent)) "Открываю будильники." else "Не удалось открыть будильники."
    }

    private fun openTimers(): String {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(AlarmClock.ACTION_SHOW_TIMERS)
        } else {
            Intent(AlarmClock.ACTION_SET_TIMER)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return if (safeStart(intent)) "Открываю таймеры." else "Не удалось открыть таймеры."
    }

    private fun setTimerFromSpeech(original: String): String {
        val totalSeconds = parseDurationSeconds(original)
        if (totalSeconds <= 0) {
            ConversationContext.setPendingSlot(PendingSlot.TIMER_DURATION)
            return "На сколько времени поставить таймер? Например: на 5 минут."
        }

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Таймер помощника")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val desc = formatDurationRu(totalSeconds.toLong())
        ConversationContext.lastAction = "TIMER"
        return if (safeStart(intent)) {
            "Таймер на $desc запущен."
        } else {
            // Fallback to built-in voice timer if device lacks Clock timer intent!
            mainHandler.postDelayed({
                onAsyncReply("Таймер", "Внимание! Время таймера ($desc) истекло!")
            }, totalSeconds * 1000L)
            "Встроенный голосовой таймер на $desc запущен."
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
        return if (safeStart(intent)) "Открываю календарь: «$title»." else "Календарь не найден."
    }

    private fun openCalendar(): String {
        val uri = Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return if (safeStart(intent)) "Открываю календарь." else "Календарь не найден."
    }

    private fun handleCurrencyCommand(lower: String, original: String, userDisplay: String): CommandResult? {
        val hasCurrencyWord = listOf("курс", "доллар", "евро", "тенге", "рубл", "юан", "лир", "дирхам", "фунт", "валют")
            .any { lower.contains(it) }
        if (!hasCurrencyWord) return null

        val normalized = RussianNumberParser.normalizeNumbersAndTime(original).replace(',', '.')
        val amount = Regex("(\\d+(?:\\.\\d+)?)").find(normalized)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0

        val tokens = normalized.split(Regex("\\s+"))
        val detectedCodes = tokens.mapNotNull { KnowledgeAndCurrencyClient.detectCurrencyCode(it) }.distinct()

        val fromCode = detectedCodes.firstOrNull() ?: "USD"
        val toCode = detectedCodes.getOrNull(1)

        ConversationContext.lastAction = "CURRENCY"
        KnowledgeAndCurrencyClient.fetchCurrencyAsync(amount, fromCode, toCode) { reply ->
            ConversationContext.recordTurn(userDisplay, reply)
            onAsyncReply(userDisplay, reply)
        }
        return CommandResult(spokenResponse = "Проверяю свежий курс валют…", asyncPending = true)
    }

    private fun handleShoppingList(lower: String, original: String): String? {
        if (lower.startsWith("добавь в список покупок") || lower.startsWith("добавь в покупки") ||
            lower.startsWith("нужно купить ") || lower.startsWith("купить в магазине ")
        ) {
            val item = original.replace(
                Regex("^(?:добавь в список покупок|добавь в покупки|нужно купить|купить в магазине)\\s*", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (item.isEmpty()) {
                ConversationContext.setPendingSlot(PendingSlot.SHOPPING_ITEM)
                return "Что добавить в список покупок?"
            }
            val list = loadStringList("shopping_list")
            list.add(item)
            saveStringList("shopping_list", list)
            return "Добавила в покупки: $item."
        }
        if (lower.startsWith("вычеркни из покупок ") || lower.startsWith("удали из покупок ")) {
            val item = original.replace(Regex("^(?:вычеркни|удали)\\s+из\\s+покупок\\s+", RegexOption.IGNORE_CASE), "").trim().lowercase(ruLocale)
            val list = loadStringList("shopping_list")
            val removed = list.removeAll { it.lowercase(ruLocale).contains(item) }
            saveStringList("shopping_list", list)
            return if (removed) "Вычеркнула «$item» из списка покупок." else "Пункт «$item» не найден в списке."
        }
        if (lower.contains("список покупок") || lower == "что нужно купить" || lower == "что купить") {
            if (lower.contains("очисти") || lower.contains("удали все")) {
                saveStringList("shopping_list", mutableListOf())
                return "Список покупок очищен."
            }
            val list = loadStringList("shopping_list")
            return if (list.isEmpty()) "Список покупок пуст." else "В списке покупок (${list.size}): ${list.joinToString(", ")}."
        }
        return null
    }

    private fun handleTodoTasks(lower: String, original: String): String? {
        if (lower.startsWith("добавь задачу") || lower.startsWith("новая задача") || lower.startsWith("добавь дело")) {
            val task = original.replace(Regex("^(?:добавь задачу|новая задача|добавь дело)\\s*", RegexOption.IGNORE_CASE), "").trim()
            if (task.isEmpty()) {
                ConversationContext.setPendingSlot(PendingSlot.TASK_ITEM)
                return "Какую задачу записать?"
            }
            val tasks = loadStringList("todo_tasks")
            tasks.add(task)
            saveStringList("todo_tasks", tasks)
            return "Задача добавлена: «$task»."
        }
        if (lower.contains("мои задачи") || lower.contains("список дел") || lower.contains("какие дела на сегодня")) {
            val tasks = loadStringList("todo_tasks")
            return if (tasks.isEmpty()) "Список задач пуст." else "Ваши задачи: ${tasks.mapIndexed { i, t -> "${i + 1}) $t" }.joinToString("; ")}."
        }
        if (lower.contains("очисти задачи") || lower.contains("удали все задачи") || lower.contains("очисти список дел")) {
            saveStringList("todo_tasks", mutableListOf())
            return "Все задачи очищены."
        }
        return null
    }

    private fun handleNotes(lower: String, original: String): String? {
        if (lower.startsWith("найди в заметках ")) {
            val kw = original.replace(Regex("^найди в заметках\\s+", RegexOption.IGNORE_CASE), "").trim().lowercase(ruLocale)
            val found = loadStringList("voice_notes").filter { it.lowercase(ruLocale).contains(kw) }
            return if (found.isEmpty()) "В заметках ничего не найдено по слову «$kw»." else "Найдено в заметках: ${found.joinToString("; ")}."
        }
        if (lower.startsWith("удали заметку номер ") || lower.startsWith("удали заметку ")) {
            val idx = RussianNumberParser.normalizeNumbersAndTime(original)
                .let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (idx != null) {
                val notes = loadStringList("voice_notes")
                if (idx !in 1..notes.size) return "Заметки с номером $idx нет."
                val removed = notes.removeAt(idx - 1)
                saveStringList("voice_notes", notes)
                return "Удалена заметка №$idx: «$removed»."
            }
        }
        if (lower.startsWith("запомни") || lower.startsWith("запиши заметку") ||
            lower.startsWith("добавь заметку") || lower.startsWith("сохрани заметку") || lower.startsWith("не забыть ")
        ) {
            val note = original.replace(
                Regex("^(?:запомни что|запомни|запиши заметку|добавь заметку|сохрани заметку|не забыть)\\s*", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            if (note.isEmpty()) {
                ConversationContext.setPendingSlot(PendingSlot.NOTE_TEXT)
                return "Что именно запомнить в заметку?"
            }
            val notes = loadStringList("voice_notes")
            notes.add(note)
            saveStringList("voice_notes", notes)
            return "Запомнила: «$note»."
        }
        if (lower.contains("прочитай заметк") || lower.contains("мои заметк") ||
            lower.contains("покажи заметк") || lower.contains("что я просил запомнить") || lower.contains("что я просила запомнить") || lower == "заметки"
        ) {
            val notes = loadStringList("voice_notes")
            return if (notes.isEmpty()) "Сохранённых заметок пока нет." else "Ваши заметки: ${notes.mapIndexed { i, s -> "${i + 1}) $s" }.joinToString("; ")}."
        }
        if (lower.contains("удали последнюю заметку")) {
            val notes = loadStringList("voice_notes")
            if (notes.isEmpty()) return "Заметок нет."
            val removed = notes.removeAt(notes.size - 1)
            saveStringList("voice_notes", notes)
            return "Удалена заметка: «$removed»."
        }
        if (lower.contains("очисти заметк") || lower.contains("удали все заметк") || lower.contains("сотри заметки")) {
            saveStringList("voice_notes", mutableListOf())
            return "Все заметки удалены."
        }
        return null
    }

    private fun handlePasswordAndClipboard(lower: String, original: String): String? {
        if (lower.contains("придумай пин") || lower.contains("сгенерируй пин")) {
            val pin = (1000..9999).random().toString()
            copyToClipboard(pin)
            return "Ваш случайный ПИН-код: ${pin.toCharArray().joinToString(" ")} (скопирован в буфер обмена)."
        }
        if (lower.contains("сгенерируй пароль") || lower.contains("придумай пароль") || lower.contains("надежный пароль")) {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$"
            val pwd = (1..12).map { chars.random() }.joinToString("")
            copyToClipboard(pwd)
            return "Сгенерирован надёжный пароль из 12 символов и скопирован в буфер обмена."
        }
        if (lower.startsWith("скопируй ")) {
            val text = original.replace(Regex("^скопируй(?:\\s+текст)?\\s+", RegexOption.IGNORE_CASE), "").trim()
            if (text.isNotEmpty()) {
                copyToClipboard(text)
                return "Скопировано в буфер обмена."
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
                return "Выберите приложение для отправки."
            }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Помощник AI", text))
    }

    private fun handleWeatherCommands(lower: String, original: String, userDisplay: String): CommandResult? {
        val setCityMatch = Regex("^(?:мой город|установи город|запомни город|я живу в городе|я живу в)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(original)
        if (setCityMatch != null) {
            val saved = WeatherClient.saveCity(context, setCityMatch.groupValues[1])
            return CommandResult("Отлично, запомнила ваш город: $saved.")
        }

        if (lower.contains("погод") || lower.contains("сколько градусов") || lower.contains("температура на улице") ||
            lower.contains("сильный ли ветер")
        ) {
            val isTomorrow = lower.contains("завтра")
            val mode = if (isTomorrow) ForecastMode.TOMORROW else ForecastMode.NOW
            val cityMatch = Regex("(?:погод\\w*|градус\\w*|температур\\w*)\\s+(?:завтра\\s+)?(?:в|во|на)\\s+([а-яёa-z\\-\\s]+)", RegexOption.IGNORE_CASE)
                .find(original)
            val city = cityMatch?.groupValues?.get(1)?.trim()

            ConversationContext.lastAction = "WEATHER"
            WeatherClient.fetchWeatherAsync(context, city, mode) { forecast ->
                ConversationContext.recordTurn(userDisplay, forecast)
                onAsyncReply(userDisplay, forecast)
            }
            val labelCity = if (city.isNullOrBlank()) {
                ConversationContext.lastWeatherCity ?: WeatherClient.getSavedCity(context)
            } else {
                WeatherClient.normalizeCityCandidate(city)
            }
            val dayLabel = if (isTomorrow) "на завтра " else ""
            return CommandResult(
                spokenResponse = "Узнаю погоду ${dayLabel}в городе $labelCity…",
                asyncPending = true
            )
        }
        return null
    }

    private fun handleTimeAndDateCommands(lower: String, original: String): String? {
        // World time in 25+ major cities
        val worldTimeMatch = Regex("(?:сколько времени|который час|время)\\s+(?:сейчас\\s+)?(?:в|во)\\s+([а-яёa-z\\-\\s]+)", RegexOption.IGNORE_CASE)
            .find(original)
        if (worldTimeMatch != null) {
            val cityRaw = worldTimeMatch.groupValues[1].trim().lowercase(ruLocale).replace('ё', 'е')
            val tzPair = worldCityTimeZones.entries.firstOrNull { cityRaw.contains(it.key) }
            if (tzPair != null) {
                val (displayCity, tzId) = tzPair.value
                val fmt = SimpleDateFormat("HH:mm (d MMMM)", ruLocale).apply {
                    timeZone = TimeZone.getTimeZone(tzId)
                }
                ConversationContext.lastAction = "WORLD_TIME"
                return "В городе $displayCity сейчас ${fmt.format(Date())}."
            }
        }

        if (lower.contains("сколько дней до нового года") || lower.contains("когда новый год")) {
            val now = Calendar.getInstance()
            val nextYear = now.get(Calendar.YEAR) + 1
            val ny = Calendar.getInstance().apply { set(nextYear, Calendar.JANUARY, 1, 0, 0, 0) }
            val days = ((ny.timeInMillis - now.timeInMillis) / (24L * 3600_000L)).toInt()
            return "До Нового $nextYear года осталось $days дней."
        }

        if (lower.contains("сколько дней до лета")) {
            val now = Calendar.getInstance()
            var year = now.get(Calendar.YEAR)
            val summer = Calendar.getInstance().apply { set(year, Calendar.JUNE, 1, 0, 0, 0) }
            if (now.get(Calendar.MONTH) in Calendar.JUNE..Calendar.AUGUST) {
                return "Сейчас уже лето!"
            }
            if (summer.before(now)) {
                year++
                summer.set(year, Calendar.JUNE, 1, 0, 0, 0)
            }
            val days = ((summer.timeInMillis - now.timeInMillis) / (24L * 3600_000L)).toInt()
            return "До начала лета осталось $days дней."
        }

        if (lower.contains("какое число будет через ") || lower.contains("какая дата через ")) {
            val norm = RussianNumberParser.normalizeNumbersAndTime(original)
            val days = Regex("(\\d+)").find(norm)?.groupValues?.get(1)?.toIntOrNull()
            if (days != null) {
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }
                val formatted = SimpleDateFormat("d MMMM yyyy 'года' (EEEE)", ruLocale).format(cal.time)
                return "Через $days дн. будет $formatted."
            }
        }

        if (lower.contains("номер недели") || lower.contains("какая по счету неделя")) {
            val week = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
            return "Сейчас идёт $week-я неделя года."
        }

        if (lower.contains("день недели") || lower in listOf("какой сегодня день", "какой завтра день", "какой вчера был день")) {
            val cal = Calendar.getInstance()
            val prefix = when {
                lower.contains("послезавтра") -> { cal.add(Calendar.DAY_OF_YEAR, 2); "Послезавтра" }
                lower.contains("завтра") -> { cal.add(Calendar.DAY_OF_YEAR, 1); "Завтра" }
                lower.contains("вчера") -> { cal.add(Calendar.DAY_OF_YEAR, -1); "Вчера был" }
                else -> "Сегодня"
            }
            ConversationContext.lastAction = "DAY_OF_WEEK"
            return "$prefix ${SimpleDateFormat("EEEE, d MMMM", ruLocale).format(cal.time)}."
        }

        if ((lower.contains("котор") && lower.contains("час")) ||
            lower.contains("сколько врем") || lower.contains("скажи время") ||
            lower.contains("текущее время") || lower == "время"
        ) {
            return "Сейчас ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}."
        }

        if (lower.contains("какая сегодня дата") || lower.contains("какое сегодня число") ||
            lower.contains("какая дата") || lower.contains("какое число") ||
            lower.contains("сегодняшняя дата") || lower in listOf("дата", "число")
        ) {
            ConversationContext.lastAction = "DATE"
            return "Сегодня ${SimpleDateFormat("d MMMM yyyy 'года', EEEE", ruLocale).format(Date())}."
        }

        return null
    }

    private fun handleCommunicationCommands(lower: String, original: String): String? {
        // Emergency numbers dial screen (safe ACTION_DIAL, never auto-calls without user confirmation)
        if (lower.contains("скорая помощь") || lower.contains("вызови скорую") || lower == "номер скорой") {
            safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:103")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return "Открываю набор скорой помощи: 103 (единый номер экстренных служб — 112)."
        }
        if (lower.contains("служба спасения") || lower == "номер 112") {
            safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return "Открываю набор экстренной службы 112."
        }

        // Add contact
        if (lower.startsWith("создай контакт") || lower.startsWith("добавь контакт")) {
            val name = original.replace(Regex("^(?:создай|добавь)\\s+контакт\\s*", RegexOption.IGNORE_CASE), "").trim()
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                if (name.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.NAME, name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (safeStart(intent)) "Открываю создание контакта." else "Не удалось открыть Контакты."
        }

        // Voice Call to Contact by Name or Number + WhatsApp contact message/call!
        ContactCaller.handleCallOrContactMessage(context, lower, original)?.let { return it }

        // Send message directly to WhatsApp or Telegram (tolerating pronunciation variants!)
        val waMsgMatch = Regex(
            "^(?:напиши|отправь)\\s+в\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|воцап\\w*|whatsapp)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (waMsgMatch != null) {
            val msg = waMsgMatch.groupValues[1].trim()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, msg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (safeStart(intent)) "Открываю WhatsApp для выбора получателя." else "WhatsApp не установлен на устройстве."
        }

        val tgMsgMatch = Regex(
            "^(?:напиши|отправь)\\s+в\\s+(?:телеграм\\w*|тилеграм\\w*|телегу|телеге|telegram)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (tgMsgMatch != null) {
            val msg = tgMsgMatch.groupValues[1].trim()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("org.telegram.messenger")
                putExtra(Intent.EXTRA_TEXT, msg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (safeStart(intent)) "Открываю Telegram для отправки сообщения." else "Telegram не установлен."
        }

        // Email
        if (lower.startsWith("напиши письмо") || lower.startsWith("отправь письмо")) {
            val body = original.replace(Regex("^(?:напиши|отправь)\\s+письмо\\s*", RegexOption.IGNORE_CASE), "").trim()
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                if (body.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (safeStart(intent)) "Открываю почтовый клиент." else "Почтовое приложение не найдено."
        }

        if (lower.startsWith("напиши смс") || lower.startsWith("отправь смс") ||
            lower.startsWith("напиши сообщение") || lower.startsWith("отправь сообщение")
        ) {
            val body = original.replace(Regex("^(?:напиши|отправь)\\s+(?:смс|сообщение)\\s*", RegexOption.IGNORE_CASE), "").trim()
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                if (body.isNotEmpty()) putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (safeStart(intent)) "Открываю сообщения." else "Приложение СМС не найдено."
        }

        return null
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
        val triggers = listOf(
            "настройк", "вайфай", "wi-fi", "wifi", "блютуз", "bluetooth",
            "авиарежим", "режим полета", "нфс", "nfc", "точк", "яркост",
            "не беспокоить", "энергосбереж", "экономия заряда", "автоповорот"
        )
        if (triggers.none { lower.contains(it) }) return null

        val (action, label) = when {
            lower.contains("вайфай") || lower.contains("вай-фай") || lower.contains("wi-fi") || lower.contains("wifi") ->
                Settings.ACTION_WIFI_SETTINGS to "Открываю настройки Wi-Fi."
            lower.contains("блютуз") || lower.contains("bluetooth") ->
                Settings.ACTION_BLUETOOTH_SETTINGS to "Открываю настройки Bluetooth."
            lower.contains("точки доступа") || lower.contains("раздач") || lower.contains("модем") ->
                Settings.ACTION_WIRELESS_SETTINGS to "Открываю беспроводные сети и точку доступа."
            lower.contains("нфс") || lower.contains("nfc") ->
                Settings.ACTION_NFC_SETTINGS to "Открываю настройки NFC."
            lower.contains("не беспокоить") ->
                Settings.ACTION_SOUND_SETTINGS to "Открываю настройки звука и режима «Не беспокоить»."
            lower.contains("звук") || lower.contains("рингтон") ->
                Settings.ACTION_SOUND_SETTINGS to "Открываю настройки звука."
            lower.contains("экран") || lower.contains("яркост") || lower.contains("диспле") || lower.contains("автоповорот") ->
                Settings.ACTION_DISPLAY_SETTINGS to "Открываю настройки экрана и яркости."
            lower.contains("батаре") || lower.contains("энергосбереж") || lower.contains("экономия заряда") ->
                Settings.ACTION_BATTERY_SAVER_SETTINGS to "Открываю настройки энергосбережения."
            lower.contains("хранилищ") || lower.contains("памяти") ->
                Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "Открываю настройки хранилища."
            lower.contains("безопасн") || lower.contains("блокировк") ->
                Settings.ACTION_SECURITY_SETTINGS to "Открываю настройки безопасности."
            lower.contains("даты") || lower.contains("времени") ->
                Settings.ACTION_DATE_SETTINGS to "Открываю настройки даты и времени."
            lower.contains("специальн") ->
                Settings.ACTION_ACCESSIBILITY_SETTINGS to "Открываю специальные возможности."
            lower.contains("приложен") ->
                Settings.ACTION_APPLICATION_SETTINGS to "Открываю список приложений."
            lower.contains("локац") || lower.contains("геолокац") || lower.contains("gps") || lower.contains("местополож") ->
                Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Открываю настройки геолокации."
            lower.contains("авиарежим") || lower.contains("режим полета") ->
                Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Открываю настройки режима полёта."
            lower.contains("мобильн") || lower.contains("сим") ->
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
        if (lower.startsWith("хочу послушать ") || lower.startsWith("поставь музыку ")) {
            val genre = original.replace(Regex("^(?:хочу послушать|поставь музыку)\\s+", RegexOption.IGNORE_CASE), "").trim()
            val encoded = URLEncoder.encode(genre, "UTF-8")
            openUrl("https://www.youtube.com/results?search_query=$encoded")
            return "Включаю на YouTube: «$genre»."
        }

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
        if (lower in listOf("где я", "где я сейчас", "мое местоположение", "покажи где я")) {
            openGeoQuery("")
            return "Открываю карты для показа вашего местоположения."
        }

        val nearbyPlaces = mapOf(
            "аптек" to "аптека",
            "супермаркет" to "супермаркет",
            "магазин" to "продуктовый магазин",
            "кофейн" to "кофейня",
            "кафе" to "кафе",
            "ресторан" to "ресторан",
            "столов" to "столовая",
            "заправк" to "АЗС",
            "азс" to "АЗС",
            "автомойк" to "автомойка",
            "шиномонтаж" to "шиномонтаж",
            "банкомат" to "банкомат",
            "банк" to "отделение банка",
            "больниц" to "больница",
            "поликлиник" to "поликлиника",
            "стоматолог" to "стоматология",
            "парикмахерск" to "парикмахерская",
            "барбершоп" to "барбершоп",
            "гостиниц" to "гостиница",
            "отель" to "отель",
            "почт" to "отделение почты",
            "парк" to "парк отдыха"
        )
        if (lower.contains("ближайш") || lower.contains("рядом") || lower.contains("поблизости")) {
            for ((stem, placeQuery) in nearbyPlaces) {
                if (lower.contains(stem)) {
                    openGeoQuery(placeQuery)
                    return "Ищу поблизости на карте: $placeQuery."
                }
            }
        }

        val mapMatch = Regex(
            "^(?:покажи на карте|найди на карте|построй маршрут до|построй маршрут в|маршрут до|маршрут в|навигатор до|как доехать до|поехали в)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val dest = mapMatch.groupValues[1].trim()
        openGeoQuery(dest)
        return "Открываю маршрут / карту: «$dest»."
    }

    private fun openGeoQuery(query: String) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val uriStr = if (query.isEmpty()) "geo:0,0" else "geo:0,0?q=$encoded"
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!safeStart(geoIntent)) {
            openUrl("https://www.google.com/maps/search/?api=1&query=$encoded")
        }
    }

    private fun handleTranslateCommand(lower: String, original: String): String? {
        if (lower == "переводчик" || lower == "открой переводчик") {
            openUrl("https://translate.google.com/?sl=ru&tl=en")
            return "Открываю Google Переводчик."
        }
        val emptyTranslate = Regex("^переведи(?:\\s+на\\s+([а-яё]+))?$", RegexOption.IGNORE_CASE).find(original)
        if (emptyTranslate != null) {
            val langPart = emptyTranslate.groupValues[1].let { if (it.isNotEmpty()) "на $it" else "на английский" }
            ConversationContext.setPendingSlot(PendingSlot.TRANSLATE_PHRASE, extra = langPart)
            return "Какую фразу перевести $langPart?"
        }

        val trMatch = Regex(
            "^переведи(?:\\s+на\\s+(английский|казахский|немецкий|французский|испанский|итальянский|турецкий|китайский|японский|корейский|арабский|русский))?\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val langWord = trMatch.groupValues[1].lowercase(ruLocale)
        val phrase = trMatch.groupValues[2].trim()
        val tl = when (langWord) {
            "казахский" -> "kk"
            "немецкий" -> "de"
            "французский" -> "fr"
            "испанский" -> "es"
            "итальянский" -> "it"
            "турецкий" -> "tr"
            "китайский" -> "zh-CN"
            "японский" -> "ja"
            "корейский" -> "ko"
            "арабский" -> "ar"
            "русский" -> "ru"
            else -> "en"
        }
        val encoded = URLEncoder.encode(phrase, "UTF-8")
        openUrl("https://translate.google.com/?sl=auto&tl=$tl&text=$encoded&op=translate")
        return "Открываю перевод фразы «$phrase»."
    }

    private fun handleWikipediaAndKnowledge(lower: String, original: String, userDisplay: String): CommandResult? {
        val openWikiMatch = Regex("^(?:открой в википедии|статья в википедии)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(original)
        if (openWikiMatch != null) {
            val q = openWikiMatch.groupValues[1].trim()
            openUrl("https://ru.m.wikipedia.org/wiki/Special:Search?search=${URLEncoder.encode(q, "UTF-8")}")
            return CommandResult("Открываю статью Википедии «$q».")
        }

        // Spoken Wikipedia Encyclopedia summary!
        val spokenWikiMatch = Regex(
            "^(?:расскажи про|расскажи о|что такое|кто такой|кто такая|википедия)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (spokenWikiMatch != null) {
            val topic = spokenWikiMatch.groupValues[1].trim(' ', '?', '.', '!')
            if (topic.isNotEmpty()) {
                KnowledgeAndCurrencyClient.fetchWikipediaSummaryAsync(topic) { summary ->
                    ConversationContext.recordTurn(userDisplay, summary)
                    onAsyncReply(userDisplay, summary)
                }
                return CommandResult(spokenResponse = "Ищу краткую справку о «$topic»…", asyncPending = true)
            }
        }
        return null
    }

    private fun handleSitesAndApps(lower: String, original: String): String? {
        if (lower.contains("последние новости") || lower == "новости" || lower == "открой новости") {
            openUrl("https://news.google.com/?hl=ru")
            return "Открываю свежие новости."
        }
        if (lower.startsWith("покажи картинки ") || lower.startsWith("найди фото ")) {
            val q = original.replace(Regex("^(?:покажи картинки|найди фото)\\s+", RegexOption.IGNORE_CASE), "").trim()
            openUrl("https://www.google.com/search?tbm=isch&q=${URLEncoder.encode(q, "UTF-8")}")
            return "Показываю картинки по запросу «$q»."
        }
        if (lower.startsWith("есть ли у меня приложение ") || lower.startsWith("установлен ли ") || lower.startsWith("установлено ли ")) {
            val appName = original.replace(Regex("^(?:есть ли у меня приложение|установлено ли|установлен ли)\\s+", RegexOption.IGNORE_CASE), "").trim()
            val (installed, label) = AppLauncherEngine.isAppInstalledByFuzzyName(context, appName)
            return if (installed) "Да, приложение «$label» установлено на телефоне." else "Приложение «$appName» не найдено на устройстве."
        }

        if (lower in listOf("открой", "запусти", "открой приложение", "включи приложение", "запусти приложение")) {
            ConversationContext.setPendingSlot(PendingSlot.OPEN_APP_NAME)
            return "Какое приложение открыть? Например: Ютуб, Ватсап, Телеграм или Инстаграм."
        }

        return AppLauncherEngine.tryLaunchFromUtterance(context, lower, original)
    }

    private fun handleGamesAndFun(lower: String, original: String): String? {
        // Cities Game!
        if (lower.contains("поиграем в города") || lower.contains("игра в города") || lower == "города" || lower.contains("сыграем в города")) {
            return NaturalIntentEngine.startCitiesGame()
        }

        // Interactive Riddle!
        if (lower.contains("загадай загадк") || lower == "загадка" || lower.contains("викторин")) {
            val picked = riddles.random()
            ConversationContext.activeRiddle = picked
            ConversationContext.lastAction = "RIDDLE"
            return "Загадка: ${picked.question} Скажите ваш ответ, «подсказка» или «сдаюсь»."
        }

        // User Choice Picker ("выбери пицца или суши", "чай или кофе")
        if (lower.contains(" или ") && !lower.contains("орел или решка") && lower != "да или нет") {
            val cleanedChoice = original.replace(
                Regex("^(?:выбери|что выбрать|что лучше|реши)\\s*[:,]?\\s*", RegexOption.IGNORE_CASE),
                ""
            ).trim(' ', '?', '.', '!')
            val options = cleanedChoice.split(Regex("\\s+или\\s+", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (options.size >= 2) {
                return "Я выбираю вариант: «${options.random()}»!"
            }
        }

        if (lower.contains("монетк") || lower.contains("орел или решка")) {
            ConversationContext.lastAction = "COIN"
            return if (Random.nextBoolean()) "Выпал орёл!" else "Выпала решка!"
        }

        if (lower.contains("кубик") || lower.contains("кинь кости") || lower.contains("брось кости")) {
            ConversationContext.lastAction = "DICE"
            return if (lower.contains("два кубика") || lower.contains("2 кубика")) {
                val a = Random.nextInt(1, 7)
                val b = Random.nextInt(1, 7)
                "На двух кубиках выпало $a и $b (сумма ${a + b})."
            } else {
                "На кубике выпало число ${Random.nextInt(1, 7)}."
            }
        }

        if (lower.contains("случайное число") || lower.contains("загадай число") || lower.contains("рандомное число")) {
            ConversationContext.lastAction = "RANDOM_NUM"
            val normalized = RussianNumberParser.normalizeNumbersAndTime(original)
            val nums = Regex("(\\d+)").findAll(normalized).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            val (min, max) = when {
                nums.size >= 2 -> if (nums[0] <= nums[1]) nums[0] to nums[1] else nums[1] to nums[0]
                nums.size == 1 && nums[0] > 1 -> 1 to nums[0]
                else -> 1 to 100
            }
            val picked = if (min == max) min else Random.nextInt(min, max + 1)
            return "Случайное число от $min до $max: $picked."
        }

        if (lower == "да или нет" || lower.contains("прими решение") || lower.startsWith("стоит ли ")) {
            val answers = listOf("Однозначно да!", "Скорее всего да.", "Думаю, сейчас не стоит.", "Интуиция подсказывает — да!", "Определённо нет.")
            return answers.random()
        }

        if (lower.contains("анекдот") || lower.contains("пошути") || lower.contains("шутк") || lower.contains("рассмеши")) {
            ConversationContext.lastAction = "JOKE"
            var idx = Random.nextInt(jokes.size)
            if (jokes.size > 1 && idx == ConversationContext.lastJokeIndex) {
                idx = (idx + 1) % jokes.size
            }
            ConversationContext.lastJokeIndex = idx
            return jokes[idx]
        }

        if (lower.contains("факт") || lower.contains("удиви меня") || lower.contains("что-нибудь познавательное")) {
            ConversationContext.lastAction = "FACT"
            var idx = Random.nextInt(facts.size)
            if (facts.size > 1 && idx == ConversationContext.lastFactIndex) {
                idx = (idx + 1) % facts.size
            }
            ConversationContext.lastFactIndex = idx
            return facts[idx]
        }

        if (lower.contains("мотивац") || lower.contains("дай совет") || lower.contains("цитат") || lower.contains("поддержи меня")) {
            ConversationContext.lastAction = "QUOTE"
            return quotes.random()
        }

        if (lower.contains("дыхательн") || lower.contains("успокоиться") || lower.contains("стресс")) {
            return "Сделаем квадратное дыхание: плавный вдох на 4 секунды… задержка дыхания на 4 секунды… медленный выдох на 4 секунды. Повторите три раза — пульс сразу станет спокойнее."
        }

        return null
    }

    private fun handleWebSearch(lower: String, original: String): String? {
        if (lower in listOf("найди", "поищи", "загугли", "найди в интернете")) {
            ConversationContext.setPendingSlot(PendingSlot.SEARCH_QUERY)
            return "Что именно найти в интернете?"
        }

        val searchMatch = Regex(
            "^(?:найди в интернете|поищи в интернете|найди в гугле|загугли|открой в гугле|поищи|найди|поиск|найди рецепт)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val query = if (lower.startsWith("найди рецепт ")) "рецепт ${searchMatch.groupValues[1].trim()}" else searchMatch.groupValues[1].trim()
        if (query.isEmpty()) return null
        openUrl("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")
        return "Открываю поиск Google: «$query»."
    }

    private fun handleConversation(lower: String): String? {
        return when {
            lower in listOf("нелли", "нели", "нэлли", "нэли", "нелля", "неля") ->
                "Да, я здесь! Чем помочь?"
            lower.contains("доброе утро") -> "Доброе утро! Я Нелли. Пусть день сложится отлично — чем помочь?"
            lower.contains("добрый день") -> "Добрый день! Нелли на связи — говорите своими словами, что нужно сделать."
            lower.contains("добрый вечер") -> "Добрый вечер! Как прошёл ваш день? Чем помочь?"
            lower.contains("спокойной ночи") || lower.contains("доброй ночи") -> "Спокойной ночи! Хотите, поставлю будильник на утро?"
            lower in listOf("привет", "здравствуй", "здравствуйте", "салам", "салем", "приветик", "хэй") ->
                "Привет! Меня зовут Нелли. Можете говорить своими словами — я позвоню контактам или открою любое приложение!"
            lower.contains("как дела") || lower.contains("как ты") || lower.contains("как жизнь") ->
                "У меня всё отлично! Микрофон слушает внимательно. Кому позвоним или что откроем?"
            lower.contains("спасибо") || lower.contains("благодарю") || lower == "рахмет" ->
                "Всегда пожалуйста! Очень рада помочь."
            lower.contains("молодец") || lower.contains("красавица") || lower.contains("умница") || lower == "круто" || lower == "супер" ->
                "Спасибо, мне очень приятно! Стараюсь понимать вас с полуслова."
            lower.contains("кто ты") || lower.contains("как тебя зовут") || lower.contains("твое имя") ->
                "Меня зовут Нелли! Я ваша голосовая помощница на Android. Звоню контактам по имени, открываю Ютуб, Ватсап, Телеграм, Инстаграм и понимаю живую речь."
            lower in listOf("пока", "до свидания", "увидимся") ->
                "До встречи! Чтобы полностью остановить фоновый режим, скажите «выключи Нелли»."
            else -> null
        }
    }

    private fun openUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
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
        private val worldCityTimeZones = mapOf(
            "актобе" to ("Актобе" to "Asia/Aqtobe"),
            "алмат" to ("Алматы" to "Asia/Almaty"),
            "астан" to ("Астана" to "Asia/Almaty"),
            "москв" to ("Москва" to "Europe/Moscow"),
            "петербург" to ("Санкт-Петербург" to "Europe/Moscow"),
            "питер" to ("Санкт-Петербург" to "Europe/Moscow"),
            "минск" to ("Минск" to "Europe/Minsk"),
            "ташкент" to ("Ташкент" to "Asia/Tashkent"),
            "бишкек" to ("Бишкек" to "Asia/Bishkek"),
            "баку" to ("Баку" to "Asia/Baku"),
            "тбилис" to ("Тбилиси" to "Asia/Tbilisi"),
            "дуба" to ("Дубай" to "Asia/Dubai"),
            "стамбул" to ("Стамбул" to "Europe/Istanbul"),
            "лондон" to ("Лондон" to "Europe/London"),
            "берлин" to ("Берлин" to "Europe/Berlin"),
            "париж" to ("Париж" to "Europe/Paris"),
            "рим" to ("Рим" to "Europe/Rome"),
            "мадрид" to ("Мадрид" to "Europe/Madrid"),
            "пекин" to ("Пекин" to "Asia/Shanghai"),
            "токио" to ("Токио" to "Asia/Tokyo"),
            "сеул" to ("Сеул" to "Asia/Seoul"),
            "нью-йорк" to ("Нью-Йорк" to "America/New_York"),
            "лос-анджелес" to ("Лос-Анджелес" to "America/Los_Angeles"),
            "чикаго" to ("Чикаго" to "America/Chicago"),
            "сидне" to ("Сидней" to "Australia/Sydney")
        )

        private val riddles = listOf(
            RiddleItem(
                question = "Висит груша — нельзя скушать. Что это?",
                canonicalAnswer = "лампочка",
                acceptedSubstrings = listOf("лампочк", "лампа"),
                hint = "Она светит под потолком."
            ),
            RiddleItem(
                question = "Без рук, без топорёнка построена избёнка. Что это?",
                canonicalAnswer = "гнездо",
                acceptedSubstrings = listOf("гнезд"),
                hint = "Птичий дом на ветке."
            ),
            RiddleItem(
                question = "Что всегда идёт, но с места никогда не сходит?",
                canonicalAnswer = "часы и время",
                acceptedSubstrings = listOf("час", "врем"),
                hint = "Вы смотрите на них, чтобы не опоздать."
            ),
            RiddleItem(
                question = "Зимой и летом одним цветом. Что это?",
                canonicalAnswer = "ёлка",
                acceptedSubstrings = listOf("елк", "ель", "сосн"),
                hint = "Её наряжают на Новый год."
            ),
            RiddleItem(
                question = "Чем больше из неё берёшь, тем больше она становится. Что это?",
                canonicalAnswer = "яма",
                acceptedSubstrings = listOf("яма", "яму"),
                hint = "Её копают лопатой в земле."
            )
        )

        private val jokes = listOf(
            "Программист ставит на тумбочку два стакана: один с водой — если захочет пить ночью, а второй пустой — если не захочет.",
            "Жена говорит мужу-программисту: сходи в магазин за хлебом, и если будут яйца — возьми десяток. Он вернулся с десятью батонами хлеба: яйца были!",
            "Почему смартфоны никогда не мерзнут зимой? Потому что на них открыто слишком много вкладок!",
            "Будильник — единственное устройство, которое ругают за то, что оно исправно работает.",
            "Только русский человек может сказать: да нет наверное — и все его прекрасно поймут!",
            "Спросил у навигатора кратчайший путь к успеху — он ответил: маршрут перестроен, продолжайте движение прямо.",
            "Пациент: Доктор, меня все игнорируют! Доктор: Следующий!",
            "Ничто так не бодрит с утра, как чашечка свежесваренного… пропущенного будильника!"
        )

        private val facts = listOf(
            "Сердце синего кита настолько велико, что его биение можно услышать под водой за три километра.",
            "На Венере сутки длятся дольше, чем целый венерианский год.",
            "Мёд — один из немногих продуктов, который не портится даже спустя тысячи лет.",
            "Осьминоги имеют три сердца и голубую кровь.",
            "Свет от Солнца долетает до Земли примерно за восемь минут и двадцать секунд.",
            "Человеческий мозг генерирует достаточно электричества, чтобы зажечь небольшую светодиодную лампочку.",
            "Бананы с точки зрения ботаники являются ягодами, а клубника — нет.",
            "В космосе металлы одинакового состава при соприкосновении могут намертво свариваться сами по себе — это называется холодной сваркой."
        )

        private val quotes = listOf(
            "Лучший момент, чтобы начать — прямо сейчас. Маленький шаг сегодня лучше идеального плана завтра.",
            "Дорогу осилит идущий: сосредоточьтесь на одном главном деле, и день пройдёт не зря.",
            "Не бойтесь сложных задач — именно на них быстрее всего растут навыки.",
            "Отдых — это не потеря времени, а заправка топливом для следующего рывка."
        )
    }
}
