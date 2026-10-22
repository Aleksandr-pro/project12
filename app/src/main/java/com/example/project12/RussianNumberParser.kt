package com.example.project12

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

object RussianNumberParser {

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
        "двоих" to (2 to 1),
        "три" to (3 to 1),
        "трех" to (3 to 1),
        "трёх" to (3 to 1),
        "троих" to (3 to 1),
        "четыре" to (4 to 1),
        "четырех" to (4 to 1),
        "четырёх" to (4 to 1),
        "четверых" to (4 to 1),
        "пять" to (5 to 1),
        "пяти" to (5 to 1),
        "пятерых" to (5 to 1),
        "шесть" to (6 to 1),
        "шести" to (6 to 1),
        "шестерых" to (6 to 1),
        "семь" to (7 to 1),
        "семи" to (7 to 1),
        "семерых" to (7 to 1),
        "восемь" to (8 to 1),
        "восьми" to (8 to 1),
        "восьмерых" to (8 to 1),
        "девять" to (9 to 1),
        "девяти" to (9 to 1),
        "десять" to (10 to 15),
        "десяти" to (10 to 15),
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
        "тысячи" to (1000 to 40),
        "тысяч" to (1000 to 40)
    )

    private fun canCombine(prevRank: Int, nextRank: Int): Boolean {
        if (prevRank == 40 && nextRank in listOf(30, 20, 15, 1)) return true
        if (prevRank == 30 && nextRank in listOf(20, 15, 1)) return true
        if (prevRank == 20 && nextRank == 1) return true
        return false
    }

    fun normalizeNumbersAndTime(input: String): String {
        val s = input.lowercase(Locale("ru", "RU"))
            .replace("полчаса", "30 минут")
            .replace("пол часа", "30 минут")
            .replace("четверть часа", "15 минут")
            .replace("полторы минуты", "90 секунд")
            .replace("полтора часа", "90 минут")
            .replace("пополам", "на 2")
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
                } else if (v == 1000 && lastRank in listOf(1, 15, 20)) {
                    // e.g. "пять тысяч" -> 5 * 1000 = 5000
                    accValue *= 1000
                    lastRank = 40
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

    fun tryEvaluateDiscountOrSplit(rawInput: String): String? {
        val text = normalizeNumbersAndTime(rawInput).replace(',', '.')

        // Discount: "скидка 25 процентов от 12000" / "12000 со скидкой 25%"
        val discMatch1 = Regex("скидк\\w*\\s+(\\d+(?:\\.\\d+)?)\\s*(?:%|процент\\w*)?\\s*(?:от|с|на)?\\s*(\\d+(?:\\.\\d+)?)")
            .find(text)
        val discMatch2 = Regex("(\\d+(?:\\.\\d+)?)\\s+со\\s+скидк\\w*\\s+(\\d+(?:\\.\\d+)?)")
            .find(text)
        if (discMatch1 != null || discMatch2 != null) {
            val pct = (discMatch1?.groupValues?.get(1) ?: discMatch2?.groupValues?.get(2))?.toDoubleOrNull()
            val price = (discMatch1?.groupValues?.get(2) ?: discMatch2?.groupValues?.get(1))?.toDoubleOrNull()
            if (pct != null && price != null && pct in 0.0..100.0) {
                val discountVal = price * pct / 100.0
                val finalPrice = price - discountVal
                ConversationContext.lastMathValue = finalPrice
                ConversationContext.lastAction = "MATH"
                return "Скидка составит ${formatDoubleRu(discountVal)}, итоговая сумма со скидкой: ${formatDoubleRu(finalPrice)}."
            }
        }

        // Tip: "чаевые 10 процентов от 8500"
        val tipMatch = Regex("чаев\\w*\\s+(\\d+(?:\\.\\d+)?)\\s*(?:%|процент\\w*)?\\s*(?:от|с)?\\s*(\\d+(?:\\.\\d+)?)")
            .find(text)
        if (tipMatch != null) {
            val pct = tipMatch.groupValues[1].toDoubleOrNull()
            val bill = tipMatch.groupValues[2].toDoubleOrNull()
            if (pct != null && bill != null) {
                val tip = bill * pct / 100.0
                val total = bill + tip
                ConversationContext.lastMathValue = total
                ConversationContext.lastAction = "MATH"
                return "Чаевые ${formatDoubleRu(pct)}% — это ${formatDoubleRu(tip)}. Всего к оплате: ${formatDoubleRu(total)}."
            }
        }

        // Split bill: "раздели счёт 15000 на 4" / "подели сумму 12000 на 3 человек"
        if (text.contains("счет") || text.contains("счёт") || text.contains("сумму") || text.contains("человек")) {
            val splitMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+на\\s+(\\d+)").find(text)
            if (splitMatch != null) {
                val sum = splitMatch.groupValues[1].toDoubleOrNull()
                val people = splitMatch.groupValues[2].toIntOrNull()
                if (sum != null && people != null && people > 0) {
                    val perPerson = sum / people
                    ConversationContext.lastMathValue = perPerson
                    ConversationContext.lastAction = "MATH"
                    return "Если разделить ${formatDoubleRu(sum)} на $people, получится по ${formatDoubleRu(perPerson)} с каждого."
                }
            }
        }

        return null
    }

    fun tryConvertUnits(rawInput: String): String? {
        val text = normalizeNumbersAndTime(rawInput).replace(',', '.')
        val numMatch = Regex("(-?\\d+(?:\\.\\d+)?)").find(text)
        val n = numMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
        val hasExplicitNumber = numMatch != null

        // Only trigger unit conversion when explicit unit pairs or "в ..." conversion phrasing appears
        if (!text.contains(" в ") && !text.contains("переведи") && !text.contains("сколько ")) return null

        fun done(result: Double, outText: String): String {
            ConversationContext.lastMathValue = result
            ConversationContext.lastAction = "MATH"
            return outText
        }

        // Miles <-> KM
        if (text.contains("мил") && text.contains("километр") && !text.contains("час")) {
            return if (text.indexOf("мил") < text.indexOf("километр")) {
                val res = n * 1.60934
                done(res, "${formatDoubleRu(n)} миль — это ${formatDoubleRu(res)} км.")
            } else {
                val res = n / 1.60934
                done(res, "${formatDoubleRu(n)} км — это ${formatDoubleRu(res)} миль.")
            }
        }
        // Inches <-> CM
        if (text.contains("дюйм") && (text.contains("сантиметр") || text.contains("см"))) {
            return if (text.indexOf("дюйм") < maxOf(text.indexOf("сантиметр"), text.indexOf("см")).let { if (it < 0) 999 else it }) {
                val res = n * 2.54
                done(res, "${formatDoubleRu(n)} дюймов — это ${formatDoubleRu(res)} см.")
            } else {
                val res = n / 2.54
                done(res, "${formatDoubleRu(n)} см — это ${formatDoubleRu(res)} дюймов.")
            }
        }
        // Feet <-> Meters
        if (text.contains("фут") && text.contains("метр") && !text.contains("фунт")) {
            val res = n * 0.3048
            return done(res, "${formatDoubleRu(n)} футов — это ${formatDoubleRu(res)} метра.")
        }
        // Pounds <-> KG
        if (text.contains("фунт") && (text.contains("килограмм") || text.contains("кг"))) {
            return if (text.indexOf("фунт") < maxOf(text.indexOf("килограмм"), text.indexOf("кг"))) {
                val res = n * 0.453592
                done(res, "${formatDoubleRu(n)} фунтов — это ${formatDoubleRu(res)} кг.")
            } else {
                val res = n / 0.453592
                done(res, "${formatDoubleRu(n)} кг — это ${formatDoubleRu(res)} фунтов.")
            }
        }
        // Ounces <-> Grams
        if (text.contains("унци") && text.contains("грамм")) {
            val res = n * 28.3495
            return done(res, "${formatDoubleRu(n)} унций — это ${formatDoubleRu(res)} г.")
        }
        // Gallons <-> Liters
        if (text.contains("галлон") && text.contains("литр")) {
            val res = n * 3.78541
            return done(res, "${formatDoubleRu(n)} галлонов — это ${formatDoubleRu(res)} литра.")
        }
        // Fahrenheit <-> Celsius
        if (hasExplicitNumber && text.contains("фаренгейт") && text.contains("цельси")) {
            return if (text.indexOf("фаренгейт") < text.indexOf("цельси")) {
                val c = (n - 32.0) * 5.0 / 9.0
                done(c, "${formatDoubleRu(n)} °F — это ${formatDoubleRu(c)} °C.")
            } else {
                val f = n * 9.0 / 5.0 + 32.0
                done(f, "${formatDoubleRu(n)} °C — это ${formatDoubleRu(f)} °F.")
            }
        }
        // Speed m/s <-> km/h
        if (hasExplicitNumber && (text.contains("м/с") || text.contains("метров в секунду")) &&
            (text.contains("км/ч") || text.contains("километров в час"))
        ) {
            val res = n * 3.6
            return done(res, "${formatDoubleRu(n)} м/с — это ${formatDoubleRu(res)} км/ч.")
        }

        return null
    }

    fun tryEvaluateContextualMath(rawInput: String): String? {
        val prev = ConversationContext.lastMathValue ?: return null
        val text = normalizeNumbersAndTime(rawInput)
            .replace(',', '.')
            .replace(Regex("^(?:а\\s+|и\\s+|теперь\\s+|еще\\s+|ещё\\s+)+"), "")
            .trim()

        // Percentage modifier on previous result: "минус 15 процентов" / "плюс 20%"
        val pctMod = Regex("^(плюс|прибавь|добавь|минус|отними|вычти)\\s+(\\d+(?:\\.\\d+)?)\\s*(?:%|процент\\w*)$")
            .find(text)
        if (pctMod != null) {
            val op = pctMod.groupValues[1]
            val pct = pctMod.groupValues[2].toDoubleOrNull() ?: return null
            val delta = prev * pct / 100.0
            val next = if (op in listOf("плюс", "прибавь", "добавь")) prev + delta else prev - delta
            ConversationContext.lastMathValue = next
            ConversationContext.lastAction = "MATH"
            return "Получается ${formatDoubleRu(next)}."
        }

        val opMatch = Regex("^(плюс|прибавь|прибавить|добавь|\\+|минус|отними|отнять|вычти|-|умножь на|умножить на|\\*|раздели на|подели на|разделить на|/)\\s*(-?\\d+(?:\\.\\d+)?)$")
            .find(text) ?: return null

        val op = opMatch.groupValues[1]
        val arg = opMatch.groupValues[2].toDoubleOrNull() ?: return null
        val next = when {
            op.startsWith("плюс") || op.startsWith("прибав") || op.startsWith("добав") || op == "+" -> prev + arg
            op.startsWith("минус") || op.startsWith("отним") || op.startsWith("вычт") || op == "-" -> prev - arg
            op.startsWith("умнож") || op == "*" -> prev * arg
            else -> {
                if (arg == 0.0) return "На ноль делить нельзя."
                prev / arg
            }
        }
        ConversationContext.lastMathValue = next
        ConversationContext.lastAction = "MATH"
        return "${formatDoubleRu(prev)} → ответ: ${formatDoubleRu(next)}."
    }

    fun tryEvaluateMath(rawInput: String): String? {
        val text = normalizeNumbersAndTime(rawInput).replace(',', '.')

        val percentMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|процент\\w*)\\s+от\\s+(\\d+(?:\\.\\d+)?)")
            .find(text)
        if (percentMatch != null) {
            val pct = percentMatch.groupValues[1].toDoubleOrNull()
            val base = percentMatch.groupValues[2].toDoubleOrNull()
            if (pct != null && base != null) {
                val res = base * pct / 100.0
                ConversationContext.lastMathValue = res
                ConversationContext.lastAction = "MATH"
                return "${formatDoubleRu(pct)}% от ${formatDoubleRu(base)} равно ${formatDoubleRu(res)}."
            }
        }

        val sqrtMatch = Regex("корень\\s+(?:из\\s+)?(\\d+(?:\\.\\d+)?)").find(text)
        if (sqrtMatch != null) {
            val num = sqrtMatch.groupValues[1].toDoubleOrNull()
            if (num != null && num >= 0) {
                val res = sqrt(num)
                ConversationContext.lastMathValue = res
                ConversationContext.lastAction = "MATH"
                return "Квадратный корень из ${formatDoubleRu(num)} равен ${formatDoubleRu(res)}."
            }
        }

        val sqMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+в\\s+квадрате").find(text)
        if (sqMatch != null) {
            val n = sqMatch.groupValues[1].toDoubleOrNull()
            if (n != null) {
                val res = n * n
                ConversationContext.lastMathValue = res
                ConversationContext.lastAction = "MATH"
                return "${formatDoubleRu(n)} в квадрате равно ${formatDoubleRu(res)}."
            }
        }

        val cubeMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+в\\s+кубе").find(text)
        if (cubeMatch != null) {
            val n = cubeMatch.groupValues[1].toDoubleOrNull()
            if (n != null) {
                val res = n * n * n
                ConversationContext.lastMathValue = res
                ConversationContext.lastAction = "MATH"
                return "${formatDoubleRu(n)} в кубе равно ${formatDoubleRu(res)}."
            }
        }

        val powMatch = Regex("(\\d+(?:\\.\\d+)?)\\s+в\\s+степени\\s+(\\d+(?:\\.\\d+)?)").find(text)
        if (powMatch != null) {
            val base = powMatch.groupValues[1].toDoubleOrNull()
            val exp = powMatch.groupValues[2].toDoubleOrNull()
            if (base != null && exp != null) {
                val res = base.pow(exp)
                ConversationContext.lastMathValue = res
                ConversationContext.lastAction = "MATH"
                return "Ответ: ${formatDoubleRu(res)}."
            }
        }

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

        if (rawInput.lowercase(Locale("ru", "RU")).let {
                it.contains("сколько будет") || it.contains("посчитай") || it.contains("умнож")
            }) {
            expr = expr.replace(Regex("(\\d)\\s+на\\s+(\\d)"), "$1 * $2")
            expr = expr.replace(Regex("(\\d)\\s+[xх]\\s+(\\d)"), "$1 * $2")
        }

        // Do not accidentally treat clock time "7:30" as division!
        if (rawInput.contains("будильник") || rawInput.contains("разбуди") || rawInput.contains("вставать")) {
            return null
        }

        val cleaned = expr.replace(Regex("[^0-9.+\\-*/()]"), "").trim()
        if (!Regex("\\d+(?:\\.\\d+)?[+\\-*/]\\d+").containsMatchIn(cleaned)) {
            return null
        }

        val value = evalArithmetic(cleaned) ?: return null
        if (value.isInfinite() || value.isNaN()) {
            return "На ноль делить нельзя."
        }
        ConversationContext.lastMathValue = value
        ConversationContext.lastAction = "MATH"
        return "Ответ: ${formatDoubleRu(value)}."
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
