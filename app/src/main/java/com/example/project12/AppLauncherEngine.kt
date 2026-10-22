package com.example.project12

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object PhoneticSimilarity {

    private val ruLocale = Locale("ru", "RU")

    fun normalizeBasic(raw: String): String {
        return raw.lowercase(ruLocale)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun stripRussianEndings(word: String): String {
        val w = word.lowercase(ruLocale).replace('ё', 'е').trim()
        if (w.length <= 3) return w
        val endings = listOf(
            "ами", "ями", "ому", "ему", "ого", "его",
            "ой", "ей", "ом", "ем", "ам", "ям", "ах", "ях", "ую", "юю",
            "у", "ю", "е", "и", "а", "я", "ы"
        )
        for (end in endings) {
            if (w.endsWith(end) && w.length - end.length >= 3) {
                return w.dropLast(end.length)
            }
        }
        return w
    }

    fun toPhoneticKey(raw: String): String {
        var s = raw.lowercase(ruLocale).replace('ё', 'е')
        // Multi-letter English & Russian phonetic normalizations first
        val multiReplacements = listOf(
            "whatsapp" to "вацап",
            "whats app" to "вацап",
            "whatsup" to "вацап",
            "youtube" to "ютуп",
            "you tube" to "ютуп",
            "instagram" to "инстаграм",
            "telegram" to "тилиграм",
            "tiktok" to "тикток",
            "tik tok" to "тикток",
            "kaspi" to "каспи",
            "2gis" to "двагис",
            "dgis" to "двагис",
            "chrome" to "хром",
            "google" to "гугл",
            "spotify" to "спатифай",
            "viber" to "вайбир",
            "indrive" to "индрайв",
            "wildberries" to "вайлдбирис",
            "halyk" to "халык",
            "homebank" to "хаумбанк",
            "sh" to "ш",
            "ch" to "ч",
            "th" to "т",
            "ph" to "ф",
            "wh" to "в",
            "qu" to "кв",
            "ck" to "к",
            "you" to "ю",
            "oo" to "у",
            "ee" to "и",
            "ea" to "и"
        )
        for ((k, v) in multiReplacements) {
            s = s.replace(k, v)
        }

        // Single Latin -> Cyrillic phonetic map
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                'a' -> sb.append('а')
                'b' -> sb.append('б')
                'c' -> sb.append('к')
                'd' -> sb.append('д')
                'e' -> sb.append('и')
                'f' -> sb.append('ф')
                'g' -> sb.append('г')
                'h' -> sb.append('х')
                'i' -> sb.append('и')
                'j' -> sb.append("дж")
                'k' -> sb.append('к')
                'l' -> sb.append('л')
                'm' -> sb.append('м')
                'n' -> sb.append('н')
                'o' -> sb.append('а')
                'p' -> sb.append('п')
                'q' -> sb.append('к')
                'r' -> sb.append('р')
                's' -> sb.append('с')
                't' -> sb.append('т')
                'u' -> sb.append('у')
                'v' -> sb.append('в')
                'w' -> sb.append('в')
                'x' -> sb.append("кс")
                'y' -> sb.append('и')
                'z' -> sb.append('з')
                else -> if (ch.isLetterOrDigit() || ch == ' ') sb.append(ch)
            }
        }

        var ru = sb.toString()
            .replace("тс", "ц")
            .replace("дс", "ц")
            .replace("ютю", "юту")
            .replace("тю", "ту")
            .replace('о', 'а')
            .replace('э', 'и')
            .replace('е', 'и')
            .replace('й', 'и')
            .replace('щ', 'ш')
            .replace('ъ', ' ')
            .replace('ь', ' ')
            .replace(" ", "")

        // Word-final devoicing in Russian ("ютуб" == "ютуп", "ватсаб" == "ватсап")
        if (ru.endsWith("б")) ru = ru.dropLast(1) + "п"
        if (ru.endsWith("д")) ru = ru.dropLast(1) + "т"
        if (ru.endsWith("г")) ru = ru.dropLast(1) + "к"
        if (ru.endsWith("в")) ru = ru.dropLast(1) + "ф"
        if (ru.endsWith("з")) ru = ru.dropLast(1) + "с"

        // Collapse consecutive duplicate characters ("телеграмм" -> "тилиграм", "ватсапп" -> "вацап")
        val dedup = StringBuilder(ru.length)
        var prev = '\u0000'
        for (c in ru) {
            if (c != prev) {
                dedup.append(c)
                prev = c
            }
        }
        return dedup.toString()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(min(curr[j] + 1, prev[j + 1] + 1), prev[j] + cost)
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }

    fun normalizedScore(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = max(a.length, b.length)
        val dist = levenshtein(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    fun wordSimilarity(spokenRaw: String, targetRaw: String): Double {
        val aNorm = normalizeBasic(spokenRaw).replace(" ", "")
        val bNorm = normalizeBasic(targetRaw).replace(" ", "")
        if (aNorm.isEmpty() || bNorm.isEmpty()) return 0.0
        if (aNorm == bNorm) return 1.0

        val aStem = stripRussianEndings(aNorm)
        val bStem = stripRussianEndings(bNorm)
        if (aStem == bStem && aStem.length >= 3) return 0.97

        val aPhon = toPhoneticKey(aNorm)
        val bPhon = toPhoneticKey(bNorm)
        if (aPhon.isNotEmpty() && aPhon == bPhon) return 0.96

        val aStemPhon = toPhoneticKey(aStem)
        val bStemPhon = toPhoneticKey(bStem)
        if (aStemPhon.length >= 3 && aStemPhon == bStemPhon) return 0.95

        val rawScore = normalizedScore(aNorm, bNorm)
        val stemScore = normalizedScore(aStem, bStem) * 0.96
        val phonScore = normalizedScore(aPhon, bPhon) * 0.95
        val stemPhonScore = normalizedScore(aStemPhon, bStemPhon) * 0.94

        return maxOf(rawScore, stemScore, phonScore, stemPhonScore)
    }
}

data class KnownAppEntry(
    val displayName: String,
    val packages: List<String>,
    val aliases: List<String>,
    val fallbackWebUrl: String? = null
)

object AppLauncherEngine {

    private val ruLocale = Locale("ru", "RU")

    private val knownApps: List<KnownAppEntry> = listOf(
        KnownAppEntry(
            displayName = "YouTube",
            packages = listOf("com.google.android.youtube", "app.revanced.android.youtube", "com.google.android.apps.youtube.mango"),
            aliases = listOf(
                "ютуб", "ютюб", "ютубе", "ютюбе", "ютубчик", "ютуп", "ютюп", "утуб", "ятуб",
                "ю туб", "ю тюб", "етуб", "ютьюб", "youtube", "you tube", "yt"
            ),
            fallbackWebUrl = "https://m.youtube.com"
        ),
        KnownAppEntry(
            displayName = "WhatsApp",
            packages = listOf("com.whatsapp", "com.whatsapp.w4b"),
            aliases = listOf(
                "ватсап", "ватсапп", "вотсап", "вотсапп", "вацап", "вацапп", "воцап",
                "ватсаб", "вотсаб", "васап", "восап", "ватс ап", "вотс ап", "ватсапе", "вацапе",
                "вотсапе", "вацапчик", "ватсап бизнес", "whatsapp", "whats app", "watsap", "whatsup"
            ),
            fallbackWebUrl = null
        ),
        KnownAppEntry(
            displayName = "Telegram",
            packages = listOf(
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "org.thunderdog.challegram",
                "tw.nekomimi.nekogram",
                "uz.unnarsx.cherrygram"
            ),
            aliases = listOf(
                "телеграм", "телеграмм", "телегу", "телега", "телеге", "телеграме", "телеграмме",
                "тилеграм", "тилеграмм", "тилиграм", "телегра", "телеграмчик", "тг",
                "telegram", "telegramm", "telega"
            ),
            fallbackWebUrl = "https://web.telegram.org"
        ),
        KnownAppEntry(
            displayName = "Instagram",
            packages = listOf("com.instagram.android", "com.instagram.lite"),
            aliases = listOf(
                "инстаграм", "инстаграмм", "инста", "инсту", "инсте", "инстограм", "инстограмм",
                "инстаграме", "инстаграмме", "истограм", "истограмм", "инстагр", "инстаграмчик",
                "instagram", "insta", "inst"
            ),
            fallbackWebUrl = "https://www.instagram.com"
        ),
        KnownAppEntry(
            displayName = "Kaspi.kz",
            packages = listOf("kz.kaspi.mobile"),
            aliases = listOf(
                "каспи", "каспий", "каспи кз", "каспи голд", "каспи банк", "каспи магазин",
                "каспі", "kaspi", "kaspi kz", "kaspi.kz"
            )
        ),
        KnownAppEntry(
            displayName = "Halyk",
            packages = listOf("kz.kkb.homebank", "com.halyk.superapp"),
            aliases = listOf("халык", "халык банк", "народный банк", "хоумбанк", "хомбанк", "halyk", "homebank")
        ),
        KnownAppEntry(
            displayName = "2ГИС",
            packages = listOf("ru.dublgis.dgismobile"),
            aliases = listOf("2гис", "2 гис", "два гис", "двагис", "дубльгис", "дубль гис", "тугис", "ту гис", "2gis", "dgis")
        ),
        KnownAppEntry(
            displayName = "TikTok",
            packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
            aliases = listOf("тикток", "тик ток", "тиктоке", "тик так", "тиктокер", "tiktok", "tik tok")
        ),
        KnownAppEntry(
            displayName = "ВКонтакте",
            packages = listOf("com.vkontakte.android", "com.vk.im"),
            aliases = listOf("вконтакте", "в контакте", "вк", "вконтакт", "vk", "vkontakte")
        ),
        KnownAppEntry(
            displayName = "Chrome",
            packages = listOf("com.android.chrome"),
            aliases = listOf("хром", "гугл хром", "хроме", "chrome", "google chrome")
        ),
        KnownAppEntry(
            displayName = "Spotify",
            packages = listOf("com.spotify.music", "com.spotify.lite"),
            aliases = listOf("спотифай", "спотик", "спатифай", "spotify")
        ),
        KnownAppEntry(
            displayName = "Яндекс Go",
            packages = listOf("ru.yandex.taxi"),
            aliases = listOf("яндекс го", "яндекс такси", "яндекс гоу", "yandex go", "yandex taxi")
        ),
        KnownAppEntry(
            displayName = "Яндекс Карты",
            packages = listOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi"),
            aliases = listOf("яндекс карты", "яндекс навигатор")
        ),
        KnownAppEntry(
            displayName = "Google Карты",
            packages = listOf("com.google.android.apps.maps"),
            aliases = listOf("гугл карты", "гугл мапс", "google maps")
        ),
        KnownAppEntry(
            displayName = "inDrive",
            packages = listOf("sinet.startup.inDriver"),
            aliases = listOf("индрайв", "индрайвер", "ин драйв", "ин драйвер", "indrive", "indriver")
        ),
        KnownAppEntry(
            displayName = "Wildberries",
            packages = listOf("com.wildberries.ru"),
            aliases = listOf("вайлдберриз", "валберис", "валдберис", "вайлдберис", "вб", "wildberries")
        ),
        KnownAppEntry(
            displayName = "Ozon",
            packages = listOf("ru.ozon.app.android"),
            aliases = listOf("озон", "азон", "ozon")
        ),
        KnownAppEntry(
            displayName = "OLX",
            packages = listOf("kz.slando", "com.olx.olx", "ua.slando"),
            aliases = listOf("олх", "оликс", "оэлэкс", "olx")
        ),
        KnownAppEntry(
            displayName = "Колёса",
            packages = listOf("kz.kolesa"),
            aliases = listOf("колеса", "колёса", "колеса кз", "kolesa")
        ),
        KnownAppEntry(
            displayName = "Крыша",
            packages = listOf("kz.krisha"),
            aliases = listOf("крыша", "крыша кз", "krisha")
        ),
        KnownAppEntry(
            displayName = "Google Play",
            packages = listOf("com.android.vending"),
            aliases = listOf("плей маркет", "плеймаркет", "гугл плей", "маркет", "play market", "google play")
        ),
        KnownAppEntry(
            displayName = "Gmail",
            packages = listOf("com.google.android.gm"),
            aliases = listOf("джимейл", "гмейл", "гугл почта", "gmail")
        ),
        KnownAppEntry(
            displayName = "Viber",
            packages = listOf("com.viber.voip"),
            aliases = listOf("вайбер", "вибер", "viber")
        ),
        KnownAppEntry(
            displayName = "Zoom",
            packages = listOf("us.zoom.videomeetings"),
            aliases = listOf("зум", "zoom")
        ),
        KnownAppEntry(
            displayName = "Discord",
            packages = listOf("com.discord"),
            aliases = listOf("дискорд", "discord")
        )
    )

    private val nonAppObjectsForVkluchi = listOf(
        "фонар", "свет", "подсветк", "вспышк", "звук", "громк", "музык", "песн", "видео",
        "трек", "режим", "секундомер", "таймер", "будильник", "вибр", "звонок", "вызов", "на ютуб", "на youtube"
    )

    /**
     * Extracts target app query when user says:
     * - "открой / запусти / включи / зайди в / перейди в / открывай / включай <приложение>"
     * - OR even says just the app name alone ("ватсап", "ютуб", "телеграм", "инстаграм", "каспи", etc.)!
     */
    fun tryLaunchFromUtterance(context: Context, lower: String, original: String): String? {
        // 1. Check bare app utterance (e.g. user simply says "Ютуб", "Вотсап", "Инстаграм", "Телеграм")
        val cleanBare = lower
            .replace(Regex("^(?:приложение|программу|программа)\\s+"), "")
            .trim()
        if (cleanBare.split(" ").size <= 2) {
            val bareKnown = findBestKnownApp(cleanBare, minThreshold = 0.80)
            if (bareKnown != null) {
                return launchKnownApp(context, bareKnown)
            }
        }

        // 2. Check explicit launch verb ("открой ...", "включи ...", "запусти ...", "зайди в ...")
        val verbMatch = Regex(
            "^(?:открой|открывай|открыть|запусти|запускай|запустить|включи|включай|включить|зайди в|зайти в|перейди в|перейти в|покажи приложение)\\s+(?:приложение\\s+|программу\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        val rawTarget = verbMatch.groupValues[1].trim(' ', '.', ',', '!', '?', '«', '»', '"')
        val lowerTarget = rawTarget.lowercase(ruLocale).replace('ё', 'е')

        // If user used "включи ...", ignore hardware/media phrases handled elsewhere (like "включи фонарик", "включи звук", "включи на ютубе ...")
        if (lower.startsWith("включ") && nonAppObjectsForVkluchi.any { lowerTarget.contains(it) }) {
            return null
        }

        return openTargetAppWithFuzzyMatch(context, rawTarget)
    }

    fun openTargetAppWithFuzzyMatch(context: Context, rawTarget: String): String {
        val targetClean = rawTarget
            .replace(Regex("^(?:приложение|программу|программа|мне|сейчас)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:пожалуйста|плиз|быстро|сейчас)$", RegexOption.IGNORE_CASE), "")
            .trim(' ', '.', ',', '!', '?', '«', '»', '"')

        val targetLower = targetClean.lowercase(ruLocale).replace('ё', 'е')
        if (targetLower.isEmpty()) {
            ConversationContext.setPendingSlot(PendingSlot.OPEN_APP_NAME)
            return "Какое приложение открыть? Например: Ютуб, Ватсап, Телеграм или Инстаграм."
        }

        // A. Built-in System Apps & Standard Intents (with fuzzy pronunciation!)
        tryOpenSystemShortcut(context, targetLower)?.let { return it }

        // B. Known Popular Apps (YouTube, WhatsApp, Telegram, Instagram, Kaspi, TikTok, 2GIS, etc.) with phonetic fuzzy tolerance!
        val bestKnown = findBestKnownApp(targetLower, minThreshold = 0.72)
        if (bestKnown != null) {
            return launchKnownApp(context, bestKnown)
        }

        // C. Fuzzy scan across ALL installed apps on the user's phone!
        val installedMatch = findBestInstalledAppFuzzy(context, targetLower, minScore = 0.68)
        if (installedMatch != null) {
            val (label, pkg) = installedMatch
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null && safeStart(context, launchIntent)) {
                ConversationContext.lastAction = "OPEN_APP"
                return "Открываю приложение $label."
            }
        }

        // D. Well-known Websites if named explicitly
        val sites = mapOf(
            "гитхаб" to "https://github.com",
            "github" to "https://github.com",
            "википеди" to "https://ru.m.wikipedia.org",
            "гугл" to "https://www.google.com",
            "яндекс" to "https://ya.ru"
        )
        for ((k, url) in sites) {
            if (targetLower.contains(k)) {
                openUrl(context, url)
                return "Открываю сайт $targetClean."
            }
        }

        return "Я не нашла приложение «$targetClean» на телефоне. Проверьте, установлено ли оно."
    }

    fun isAppInstalledByFuzzyName(context: Context, rawName: String): Pair<Boolean, String> {
        val lower = rawName.lowercase(ruLocale).replace('ё', 'е').trim()
        val known = findBestKnownApp(lower, minThreshold = 0.74)
        if (known != null) {
            for (pkg in known.packages) {
                if (context.packageManager.getLaunchIntentForPackage(pkg) != null) {
                    return true to known.displayName
                }
            }
        }
        val installed = findBestInstalledAppFuzzy(context, lower, minScore = 0.72)
        if (installed != null) {
            return true to installed.first
        }
        return false to rawName
    }

    private fun findBestKnownApp(queryLower: String, minThreshold: Double): KnownAppEntry? {
        var bestApp: KnownAppEntry? = null
        var bestScore = 0.0

        for (entry in knownApps) {
            for (alias in entry.aliases) {
                val score = if (queryLower == alias) {
                    1.0
                } else if (alias.length >= 4 && queryLower.contains(alias)) {
                    0.96
                } else {
                    PhoneticSimilarity.wordSimilarity(queryLower, alias)
                }
                if (score > bestScore) {
                    bestScore = score
                    bestApp = entry
                }
            }
        }

        return if (bestScore >= minThreshold) bestApp else null
    }

    private fun launchKnownApp(context: Context, entry: KnownAppEntry): String {
        val pm = context.packageManager

        // 1. Try known official package IDs first
        for (pkg in entry.packages) {
            val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null && safeStart(context, intent)) {
                ConversationContext.lastAction = "OPEN_APP"
                return "Открываю ${entry.displayName}."
            }
        }

        // 2. Try scanning installed apps matching display name or aliases (covers OEM clones / dual apps)
        val fuzzyInstalled = findBestInstalledAppFuzzy(context, entry.displayName.lowercase(ruLocale), minScore = 0.78)
        if (fuzzyInstalled != null) {
            val intent = pm.getLaunchIntentForPackage(fuzzyInstalled.second)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null && safeStart(context, intent)) {
                ConversationContext.lastAction = "OPEN_APP"
                return "Открываю ${entry.displayName}."
            }
        }

        // 3. Fallback to web version if available (e.g. YouTube, Instagram, Telegram)
        if (entry.fallbackWebUrl != null) {
            if (openUrl(context, entry.fallbackWebUrl)) {
                return "Приложение ${entry.displayName} не установлено — открываю веб-версию в браузере."
            }
        }

        return "Приложение ${entry.displayName} не установлено на этом телефоне."
    }

    @Suppress("DEPRECATION")
    private fun findBestInstalledAppFuzzy(context: Context, queryLower: String, minScore: Double): Pair<String, String>? {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(mainIntent, 0)

            var bestPair: Pair<String, String>? = null
            var bestScore = 0.0

            for (ri in activities) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) continue
                val label = ri.loadLabel(pm).toString().trim()
                val labelLower = label.lowercase(ruLocale).replace('ё', 'е')

                var score = PhoneticSimilarity.wordSimilarity(queryLower, labelLower)

                // Also check individual words of multi-word app labels (e.g. "Kaspi.kz" -> "kaspi")
                for (token in PhoneticSimilarity.normalizeBasic(labelLower).split(" ")) {
                    if (token.length >= 3) {
                        val tokenScore = PhoneticSimilarity.wordSimilarity(queryLower, token) * 0.96
                        if (tokenScore > score) score = tokenScore
                    }
                }

                // Also check last segment of package name
                val pkgTail = pkg.substringAfterLast('.').lowercase(Locale.US)
                if (pkgTail.length >= 3) {
                    val pkgScore = PhoneticSimilarity.wordSimilarity(queryLower, pkgTail) * 0.92
                    if (pkgScore > score) score = pkgScore
                }

                if (score > bestScore) {
                    bestScore = score
                    bestPair = label to pkg
                }
            }

            if (bestScore >= minScore) bestPair else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOpenSystemShortcut(context: Context, target: String): String? {
        return when {
            PhoneticSimilarity.wordSimilarity(target, "галерея") >= 0.78 || target in listOf("фото", "мои фото", "фотографии") -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (safeStart(context, intent)) "Открываю галерею." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "камера") >= 0.80 -> {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (safeStart(context, intent)) "Открываю камеру." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "контакты") >= 0.78 || target.contains("телефонную книгу") -> {
                val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (safeStart(context, intent)) "Открываю контакты." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "телефон") >= 0.82 || target.contains("звонилк") -> {
                val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (safeStart(context, intent)) "Открываю телефон." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "калькулятор") >= 0.78 -> {
                val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (safeStart(context, intent)) "Открываю калькулятор." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "часы") >= 0.84 -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (safeStart(context, intent)) "Открываю часы." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "настройки") >= 0.80 -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (safeStart(context, intent)) "Открываю настройки." else null
            }
            PhoneticSimilarity.wordSimilarity(target, "браузер") >= 0.78 || target == "интернет" -> {
                openUrl(context, "https://www.google.com")
                "Открываю браузер."
            }
            else -> null
        }
    }

    private fun openUrl(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return safeStart(context, intent)
    }

    private fun safeStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
