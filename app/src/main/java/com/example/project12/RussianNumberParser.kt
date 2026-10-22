package com.example.project12

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

object RussianNumberParser {

    private data class NumToken(val value: Int, val rank: Int)

    // rank: 4 = thousand, 3 = hundreds, 2 = tens (20..90), 1 = teens (10..19) or units (1..9), 0 = zero
    // Note: tens (rank 2) can combine with units (1..9), so we distinguish teens (rank 1, no further unit) vs units (rank 1)
    private val wordMap: Map<String, Pair<Int, Int>> = mapOf(
        "ноль" to (0 to 0),
        "нуль" to (0 to 0),
        "один" to (1 to 1),
        "одна" to (1 to 1),
        "одну" to (1 to 1),
        "одного" to (1 to 1),
        "раз" to (1 to 1),
        "два" to (2 to 1),
        "две" to (2 to 1),
        "двух" to (2 to 1),
        "три" to (3 to 1),
        "трех" to (3 to 1),
        "трёх" to (3 to 1),
        "четыре" to (4 to 1),
        "четырех" to (4 to 1),
        "четырёх" to (4 to 1),
        "пять" to (5 to 1),
        "пяти" to (5 to 1),
        "шесть" to (6 to 1),
        "шести" to (6 to 1),
        "семь" to (7 to 1),
        "семи" to (7 to 1),
        "восемь" to (8 to 1),
        "восьми" to (8 to 1),
        "девять" to (9 to 1),
        "девяти" to (9 to 1),
        "десять" to (10 to 15),
        "одиннадцать" to (11 to 15),
        "двенадцать" to (12 to 15),
        "тринадцать" to (13 to 15),
        "четырнадцать" to (14 to 15),
        "пятнадцать" to (15 to 15),
        "шестнадцать" to (16 to 15),
        "семнадцать" to (17 to 15),
        "восемнадцать" to (18 to 15),
        "девятнадцать" to (19 to 15),
        "двадцать" to (20 to 20),
        "тридцать" to (30 to 20),
        "сорок" to (40 to 20),
        "пятьдесят" to (50 to 20),
        "шестьдесят" to (60 to 20),
        "семьдесят" to (70 to 20),
        "восемьдесят" to (80 to 20),
        "девяносто" to (90 to 20),
        "сто" to (100 to 30),
        "двести" to (200 to 30),
        "триста" to (300 to 30),
        "четыреста" to (400 to 30),
        "пятьсот" to (500 to 30),
        "шестьсот" to (600 to 30),
        "семьсот" to (700 to 30),
        "восемьсот" to (800 to 30),
        "девятьсот" to (900 to 30),
        "тысяча" to (1000 to 40),
        "тысячу" to (1000 to 40),
        "тысячи" to (1000 to 40)
    )

    private fun canCombine(prevRank: Int, nextRank: Int): Boolean {
        if (prevRank == 40 && nextRank in listOf(30, 20, 15, 1)) return true
        if (prevRank == 30 && nextRank in listOf(20, 15, 1)) return true
        if (prevRank == 20 && nextRank == 1) return true
        return false
    }

    fun normalizeNumbersAndTime(input: String): String {
        var s = input.lowercase(Locale("ru", "RU"))
            .replace("полчаса", "30 минут")
            .replace("пол часа", "30 минут")
            .replace("четверть часа", "15 минут")
            .replace("полторы минуты", "90 секунд")
            .replace("полтора часа", "90 минут")
            .replace(Regex("\\bна минуту\\b"), "на 1 минуту")
            .replace(Regex("\\bчерез минуту\\b"), "через 1 минуту")
            .replace(Regex("\\bна час\\b"), "на 1 час")
            .replace(Regex("\\bчерез час\\b"), "через 1 час")
            .replace(Regex("\\bна секунду\\b"), "на 1 секунду")

        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var accValue: Int? = null
        var lastRank: Int = -1

        for (raw in tokens) {
            val clean = raw.trimEnd(',', '.', '!', '?')
            val suffix = raw.substring(clean.length)
            val entry = wordMap[clean]
            if (entry != null) {
                val (v, rank) = entry
                if (accValue == null) {
                    accValue = v
                    lastRank = rank
                } else if (canCombine(lastRank, rank)) {
                    accValue += v
                    lastRank = rank
                } else {
                    out.add(accValue.toString())
                    accValue = v
                    lastRank = rank
                }
                if (suffix.isNotEmpty()) {
                    out.add(accValue.toString() + suffix)
                    accValue = null
                    lastRank = -1
                }
            } else {
                if (accValue != null) {
                    out.add(accValue.toString())
                    accValue = null
                    lastRank = -1
                }
                out.add(raw)
            }
        }
        if (accValue != null) {
            out.add(accValue.toString())
        }
        return out.joinToString(" ")
    }

    fun formatDoubleRu(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "не определено"
        val rounded = (value * 10000.0).roundToLong() / 10000.0
        val asLong = rounded.toLong()
        return if (rounded == asLong.toDouble()) {
            asLong.toString()
        } else {
            rounded.toString().trimEnd('0').trimEnd('.').replace('.', ',')
        }
    }

    fun tryEvaluateMath(rawInput: String): String? {
        val text = normalizeNumbersAndTime(rawInput)
            .replace(',', '.')

        // Percentage: "20 процентов от 4500" / "20% от 4500"
        val percentMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|процент\\w*)\\s+от\\s+(\\d+(?:\\.\\d+)?)")
            .find(text)
        if (percentMatch != null) {
            val pct = percentMatch.groupValues[1].toDoubleOrNull()
            val base = percentMatch.groupValues[2].toDoubleOrNull()
            if (pct != null && base != null) {
                val res = base * pct / 100.0
                return "${formatDoubleRu(pct)}% от ${formatDoubleRu(base)} равно ${formatDoubleRu(res)}"
            }
        }

        // Square root: "корень из 144"
        val sqrtMatch = Regex("корень\\s+(?:из\\s+)?(\\d+(?:\\.\\d+)?)").find(text)
        if (sqrtMatch != null) {
            val num = sqrtMatch.groupValues[1].toDoubleOrNull()
            if (num != null && num >= 0) {
                return "Квадратный корень из ${formatDoubleRu(num)} равен ${formatDoubleRu(sqrt(num))}"
            }
        }

        // Square / Cube / Power: "5 в квадрате", "3 в кубе", "2 в степени 10"
        val sqMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+в\\s+квадрате").find(text)
        if (sqMatch != null) {
            val n = sqMatch.groupValues[1].toDoubleOrNull()
            if (n != null) return "${formatDoubleRu(n)} в квадрате равно ${formatDoubleRu(n * n)}"
        }
        val cubeMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+в\\s+кубе").find(text)
        if (cubeMatch != null) {
            val n = cubeMatch.groupValues[1].toDoubleOrNull()
            if (n != null) return "${formatDoubleRu(n)} в кубе равно ${formatDoubleRu(n * n * n)}"
        }
        val powMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+в\\s+степени\\s+(\\d+(?:\\.\\d+)?)").find(text)
        if (powMatch != null) {
            val base = powMatch.groupValues[1].toDoubleOrNull()
            val exp = powMatch.groupValues[2].toDoubleOrNull()
            if (base != null && exp != null) {
                return "Ответ: ${formatDoubleRu(base.pow(exp))}"
            }
        }

        // Replace spoken operators with math symbols
        var expr = text
            .replace("сколько будет", "")
            .replace("посчитай", "")
            .replace("вычисли", "")
            .replace("реши пример", "")
            .replace("реши", "")
            .replace("чему равно", "")
            .replace("умножить на", "*")
            .replace("умножь на", "*")
            .replace("помножить на", "*")
            .replace("разделить на", "/")
            .replace("поделить на", "/")
            .replace("раздели на", "/")
            .replace("прибавить", "+")
            .replace("плюс", "+")
            .replace("отнять", "-")
            .replace("вычесть", "-")
            .replace("минус", "-")
            .replace('×', '*')
            .replace('÷', '/')
            .replace(':', '/')

        // Also handle "25 на 4" when preceded by math intent ("сколько будет 25 на 4")
        if (rawInput.lowercase(Locale("ru", "RU")).let {
                it.contains("сколько будет") || it.contains("посчитай") || it.contains("умнож")
            }) {
            expr = expr.replace(Regex("(\\d)\\s+на\\s+(\\d)"), "$1 * $2")
            expr = expr.replace(Regex("(\\d)\\s+[xх]\\s+(\\d)"), "$1 * $2")
        }

        val cleaned = expr.replace(Regex("[^0-9.+\\-*/()]"), "").trim()
        // Must contain at least one operator between digits
        if (!Regex("\\d+(?:\\.\\d+)?[+\\-*/]\\d+").containsMatchIn(cleaned)) {
            return null
        }

        val value = evalArithmetic(cleaned) ?: return null
        if (value.isInfinite() || value.isNaN()) {
            return "На ноль делить нельзя"
        }
        return "Ответ: ${formatDoubleRu(value)}"
    }

    private fun evalArithmetic(expr: String): Double? {
        return try {
            object {
                var pos = -1
                var ch = 0

                fun nextChar() {
                    ch = if (++pos < expr.length) expr[pos].code else -1
                }

                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.code) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }

                fun parse(): Double? {
                    nextChar()
                    val x = parseExpression() ?: return null
                    if (pos < expr.length) return null
                    return x
                }

                fun parseExpression(): Double? {
                    var x = parseTerm() ?: return null
                    while (true) {
                        when {
                            eat('+'.code) -> x += parseTerm() ?: return null
                            eat('-'.code) -> x -= parseTerm() ?: return null
                            else -> return x
                        }
                    }
                }

                fun parseTerm(): Double? {
                    var x = parseFactor() ?: return null
                    while (true) {
                        when {
                            eat('*'.code) -> x *= parseFactor() ?: return null
                            eat('/'.code) -> {
                                val d = parseFactor() ?: return null
                                if (d == 0.0) return Double.NaN
                                x /= d
                            }
                            else -> return x
                        }
                    }
                }

                fun parseFactor(): Double? {
                    if (eat('+'.code)) return parseFactor()
                    if (eat('-'.code)) return -(parseFactor() ?: return null)

                    val startPos = pos
                    val x: Double
                    if (eat('('.code)) {
                        x = parseExpression() ?: return null
                        if (!eat(')'.code)) return null
                    } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                        while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                        x = expr.substring(startPos, pos).toDoubleOrNull() ?: return null
                    } else {
                        return null
                    }
                    return x
                }
            }.parse()
        } catch (_: Exception) {
            null
        }
    }
}
