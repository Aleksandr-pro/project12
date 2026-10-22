package com.example.project12

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * v5.0 — Характер голоса Нелли.
 *
 * «Милый женский голос» = женский русский voice (Google «-ruf» / «-dfc» / Standard-A/C)
 * + немного повышенный тон (pitch) + мягкая скорость речи.
 * Пользователь может выбрать один из трёх характеров или конкретный голос из установленных.
 */
enum class VoiceStyle(
    val labelRu: String,
    val pitch: Float,
    val rate: Float,
    val description: String
) {
    SWEET("Милая", 1.24f, 0.97f, "Высокий мягкий голос — самый милый вариант"),
    NATURAL("Естественная", 1.10f, 1.00f, "Обычный женский голос Нелли"),
    CALM("Спокойная", 0.98f, 0.92f, "Низкий спокойный голос, как у диктора");

    companion object {
        fun fromName(name: String?): VoiceStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SWEET
    }
}

object VoicePersona {

    private const val PREFS = "assistant_prefs"
    private const val KEY_STYLE = "voice_style"
    private const val KEY_VOICE_NAME = "voice_name"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var style: VoiceStyle
        get() = VoiceStyle.fromName(prefs?.getString(KEY_STYLE, VoiceStyle.SWEET.name))
        set(value) {
            prefs?.edit()?.putString(KEY_STYLE, value.name)?.apply()
        }

    /** Имя конкретного voice (например, ru-ru-x-dfc-local). Пусто = авто-выбор. */
    var preferredVoiceName: String
        get() = prefs?.getString(KEY_VOICE_NAME, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_VOICE_NAME, value)?.apply()
        }

    /** Применяет тон и скорость. rateMultiplier — пользовательская команда «быстрее/медленнее». */
    fun apply(engine: TextToSpeech, rateMultiplier: Float = 1f) {
        val s = style
        try {
            engine.setPitch(s.pitch)
            engine.setSpeechRate((s.rate * rateMultiplier).coerceIn(0.55f, 1.9f))
        } catch (_: Exception) {
        }
    }

    fun russianVoices(engine: TextToSpeech): List<Voice> {
        val all = try {
            engine.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return all.filter { v ->
            val lang = v.locale.language.lowercase(Locale.US)
            lang == "ru"
        }.sortedWith(
            compareByDescending<Voice> { femaleScore(it) }.thenBy { it.name }
        )
    }

    private fun femaleScore(v: Voice): Int {
        val n = v.name.lowercase(Locale.US)
        var score = 0
        if (n.contains("female")) score += 40
        if (n.contains("-ruf")) score += 32
        if (n.contains("-dfc")) score += 26
        if (n.contains("standard-a") || n.contains("standard-c")) score += 30
        if (n.contains("male") || n.contains("-rud") || n.contains("-rjs") ||
            n.contains("standard-b") || n.contains("standard-d")
        ) {
            score -= 60
        }
        if (v.quality >= Voice.QUALITY_HIGH) score += 10
        if (!v.isNetworkConnectionRequired) score += 6
        return score
    }

    /** Выбирает самый «милый» женский русский голос либо сохранённый пользователем. */
    fun selectVoice(engine: TextToSpeech) {
        try {
            val voices = russianVoices(engine)
            if (voices.isEmpty()) return

            val saved = preferredVoiceName
            if (saved.isNotEmpty()) {
                voices.firstOrNull { it.name == saved }?.let {
                    engine.voice = it
                    return
                }
            }

            val best = voices.firstOrNull { femaleScore(it) > 0 } ?: voices.first()
            engine.voice = best
        } catch (_: Exception) {
        }
    }

    fun describeCurrent(engine: TextToSpeech?): String {
        val s = style
        val voiceName = try {
            engine?.voice?.name
        } catch (_: Exception) {
            null
        }
        val voiceText = when {
            voiceName.isNullOrBlank() -> "системный русский голос"
            voiceName.contains("-dfc") || voiceName.contains("-ruf") -> "женский голос Google ($voiceName)"
            else -> voiceName
        }
        return "Сейчас у меня характер «${s.labelRu}», тон ${String.format(Locale.US, "%.2f", s.pitch)}, " +
            "скорость ${String.format(Locale.US, "%.2f", s.rate)}. Голос: $voiceText. " +
            "Скажите «говори милым голосом», «обычным голосом» или «спокойным голосом», " +
            "а конкретный голос можно выбрать кнопкой на главном экране."
    }

    /**
     * Убирает эмодзи и раскрывает сокращения, чтобы синтезатор не «спотыкался».
     * Это заметно снижает недопонимание на слух.
     */
    fun prepareTextForSpeech(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw

        // Эмодзи, стрелки, пиктограммы и модификаторы
        t = t.replace(
            Regex("[\\u2190-\\u21FF\\u2300-\\u27BF\\u2B00-\\u2BFF\\uFE0F\\u200D\\u20E3]"),
            ""
        )
        t = t.replace(Regex("[\\uD800-\\uDFFF]"), "")

        // Символы разметки
        t = t.replace("•", ",").replace("·", ",").replace("—", ",").replace("–", ",")
        t = t.replace("*", "").replace("#", "").replace("_", " ")
        t = t.replace("«", "\"").replace("»", "\"")

        // Аббревиатуры и обозначения
        t = t.replace("%", " процентов")
        t = t.replace("°C", " градусов")
        t = t.replace("°", " градусов")
        t = t.replace(Regex("(?i)\\bсмс\\b"), "эс эм эс")
        t = t.replace(Regex("(?i)\\bsms\\b"), "эс эм эс")
        t = t.replace(Regex("(?i)olymptrade|olymp trade|олимптрейд"), "Олимп Трейд")
        t = t.replace(Regex("(?i)whatsapp|вотсап|вацап"), "Ватсап")
        t = t.replace(Regex("(?i)telegram"), "Телеграм")
        t = t.replace(Regex("(?i)2гис"), "два гис")
        t = t.replace(Regex("(?i)\\bwi[- ]?fi\\b"), "вай фай")
        t = t.replace(Regex("(?i)\\bкм\\b"), "километров")
        t = t.replace(Regex("(?i)\\bм\\b"), "метров")
        t = t.replace(Regex("\\s+"), " ")
        return t.trim()
    }
}
