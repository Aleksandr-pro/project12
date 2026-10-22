package com.example.project12

import java.util.Locale

sealed class ContextualResolution {
    data class DirectReply(val text: String) : ContextualResolution()
    data class RewrittenCommand(val command: String) : ContextualResolution()
    object PassThrough : ContextualResolution()
}

object NaturalIntentEngine {

    private val ruLocale = Locale("ru", "RU")

    private val citiesDictionary: List<String> = listOf(
        "Актобе", "Алматы", "Астана", "Атырау", "Актау", "Анкара", "Амстердам", "Афины",
        "Баку", "Берлин", "Бишкек", "Брест", "Будапешт", "Барселона", "Бангкок",
        "Варшава", "Вена", "Вильнюс", "Владивосток", "Волгоград", "Воронеж",
        "Гамбург", "Гомель", "Грозный", "Генуя",
        "Дубай", "Душанбе", "Дрезден", "Дублин", "Дели",
        "Екатеринбург", "Ереван", "Ессентуки",
        "Женева", "Жезказган", "Житомир",
        "Загреб", "Зальцбург",
        "Иркутск", "Ижевск", "Иерусалим", "Стамбул",
        "Казань", "Караганда", "Костанай", "Кокшетау", "Кызылорда", "Калининград", "Краснодар", "Каир", "Киото",
        "Лондон", "Лиссабон", "Лион", "Люксембург",
        "Москва", "Минск", "Мадрид", "Милан", "Мюнхен", "Мурманск", "Монреаль",
        "Новосибирск", "Нижний Новгород", "Неаполь", "Ницца", "Нью-Йорк",
        "Омск", "Осло", "Оренбург", "Одесса", "Оттава", "Осака",
        "Павлодар", "Париж", "Прага", "Пермь", "Пекин", "Петропавловск",
        "Рига", "Рим", "Ростов", "Рязань", "Роттердам",
        "Самара", "Саратов", "Семей", "Сочи", "Сеул", "Сидней", "Сингапур", "Стокгольм",
        "Талдыкорган", "Тараз", "Ташкент", "Тбилиси", "Тверь", "Токио", "Томск", "Турин", "Тюмень", "Туркестан",
        "Уральск", "Усть-Каменогорск", "Уфа", "Ульяновск",
        "Франкфурт", "Флоренция",
        "Хабаровск", "Хельсинки", "Харбин",
        "Цюрих",
        "Челябинск", "Чикаго", "Чита",
        "Шымкент", "Шанхай", "Штутгарт",
        "Экибастуз", "Эдинбург",
        "Юрмала", "Южно-Сахалинск",
        "Якутск", "Ярославль"
    )

    private val verbInfinitiveReplacements = listOf(
        "включить" to "включи",
        "включать" to "включи",
        "включай" to "включи",
        "выключить" to "выключи",
        "выключать" to "выключи",
        "отключить" to "выключи",
        "открыть" to "открой",
        "открывать" to "открой",
        "открывай" to "открой",
        "запустить" to "запусти",
        "запускать" to "запусти",
        "запускай" to "запусти",
        "поставить" to "поставь",
        "установить" to "установи",
        "найти" to "найди",
        "поискать" to "поищи",
        "посчитать" to "посчитай",
        "вычислить" to "вычисли",
        "показать" to "покажи",
        "рассказать" to "расскажи",
        "проверить" to "проверь",
        "перевести" to "переведи",
        "добавить" to "добавь",
        "записать" to "запиши",
        "запомнить" to "запомни",
        "удалить" to "удали",
        "очистить" to "очисти",
        "сделать" to "сделай",
        "прибавить" to "прибавь",
        "убавить" to "убавь",
        "уменьшить" to "уменьши",
        "увеличить" to "увеличь",
        "позвонить" to "позвони",
        "звонить" to "позвони",
        "набрать" to "набери",
        "набирать" to "набери",
        "отправить" to "отправь",
        "написать" to "напиши",
        "скопировать" to "скопируй",
        "засечь" to "засеки",
        "разбудить" to "разбуди"
    )

    fun stripConversationalFillers(raw: String): String {
        var s = raw.trim()
        // Repeatedly strip polite conversational prefixes so users can speak freely
        val prefixPattern = Regex(
            "^(?:нелли|нели|нэлли|нэли|нелля|неля|нелличка|nelly|помощница|помощник|ассистент|" +
                "слушай|слышь|братан|брат|друг|подруга|дорогая|дорогой|короче|кстати|вообще|так|ну|эй|алло|" +
                "скажи мне пожалуйста|скажи пожалуйста|подскажи мне пожалуйста|подскажи пожалуйста|" +
                "скажи-ка|подскажи-ка|подскажи|будь добра|будь добр|если не сложно|если не трудно|" +
                "не могла бы ты|не мог бы ты|можешь ли ты|можешь пожалуйста|ты можешь|можешь|" +
                "мне бы хотелось чтобы ты|я хочу чтобы ты|мне нужно чтобы ты|мне надо чтобы ты|" +
                "я бы хотел|я бы хотела|я хочу|мне нужно|мне надо|надо бы|надо|" +
                "помоги мне|помоги|пожалуйста|плиз|давай-ка|ну-ка|быстренько|срочно)[,\\s!:-]+",
            RegexOption.IGNORE_CASE
        )
        var guard = 0
        while (guard++ < 5) {
            val next = prefixPattern.replaceFirst(s, "").trim()
            if (next == s || next.isEmpty()) break
            s = next
        }

        // Remove trailing "пожалуйста" / "плиз" / "быстренько"
        s = s.replace(Regex("[,\\s]+(?:пожалуйста|плиз|если не сложно|ладно|окей|ок)\\s*[.!?]*$", RegexOption.IGNORE_CASE), "")
            .trim()

        // Normalize leading infinitive verb ("включить фонарик" -> "включи фонарик")
        val firstSpace = s.indexOf(' ')
        val firstWord = (if (firstSpace > 0) s.substring(0, firstSpace) else s).lowercase(ruLocale).replace('ё', 'е')
        val rest = if (firstSpace > 0) s.substring(firstSpace) else ""
        for ((inf, imp) in verbInfinitiveReplacements) {
            if (firstWord == inf) {
                s = imp + rest
                break
            }
        }
        return s.trim()
    }

    fun resolveContextualTurn(rawInput: String): ContextualResolution {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase(ruLocale).replace('ё', 'е').trim(' ', '.', ',', '!', '?')

        // 1. Active Cities Game
        if (ConversationContext.citiesGameActive) {
            if (lower in listOf("стоп игра", "хватит играть", "закончить игру", "выход из игры", "сдаюсь", "надоело")) {
                val score = ConversationContext.usedCities.size / 2
                ConversationContext.resetCitiesGame()
                return ContextualResolution.DirectReply("Игра в города окончена! Вы назвали городов: $score. Было здорово поиграть!")
            }
            // Allow escaping if user clearly asks an unrelated phone command
            if (!looksLikePhoneCommandDuringGame(lower)) {
                return handleCitiesGameTurn(trimmed)
            } else {
                ConversationContext.resetCitiesGame()
            }
        }

        // 2. Active Riddle
        val riddle = ConversationContext.activeRiddle
        if (riddle != null) {
            if (lower.contains("подсказк") || lower == "помоги") {
                return ContextualResolution.DirectReply("Подсказка: ${riddle.hint}")
            }
            if (lower in listOf("сдаюсь", "не знаю", "какой ответ", "скажи ответ", "ответ")) {
                ConversationContext.activeRiddle = null
                return ContextualResolution.DirectReply("Правильный ответ: ${riddle.canonicalAnswer}! Хотите ещё загадку — скажите «ещё».")
            }
            if (riddle.acceptedSubstrings.any { kw -> lower.contains(kw) }) {
                ConversationContext.activeRiddle = null
                ConversationContext.lastAction = "RIDDLE"
                return ContextualResolution.DirectReply("Верно, молодец! Это действительно ${riddle.canonicalAnswer}! Скажите «ещё», если хотите следующую загадку.")
            }
            if (!looksLikePhoneCommandDuringGame(lower) && lower.split(" ").size <= 4) {
                return ContextualResolution.DirectReply("Почти, но нет! Подумайте ещё, скажите «подсказка» или «сдаюсь».")
            } else {
                ConversationContext.activeRiddle = null
            }
        }

        // 3. Pending Multi-Turn Slot Filling
        if (ConversationContext.peekPendingSlot() != null) {
            if (lower in listOf("отмена", "отмени", "не надо", "передумал", "передумала", "забудь")) {
                ConversationContext.clearPendingSlot()
                return ContextualResolution.DirectReply("Хорошо, отменила.")
            }
            val consumed = ConversationContext.consumePendingSlot()
            if (consumed != null) {
                val (slot, extra) = consumed
                val rewritten = when (slot) {
                    PendingSlot.TIMER_DURATION -> "поставь таймер $trimmed"
                    PendingSlot.ALARM_TIME -> "поставь будильник $trimmed"
                    PendingSlot.NOTE_TEXT -> "запомни $trimmed"
                    PendingSlot.SHOPPING_ITEM -> "добавь в список покупок $trimmed"
                    PendingSlot.TASK_ITEM -> "добавь задачу $trimmed"
                    PendingSlot.SEARCH_QUERY -> "найди $trimmed"
                    PendingSlot.TRANSLATE_PHRASE -> "переведи $extra $trimmed".trim()
                    PendingSlot.OPEN_APP_NAME -> "открой $trimmed"
                    PendingSlot.CALL_TARGET -> "позвони $trimmed"
                }
                return ContextualResolution.RewrittenCommand(rewritten)
            }
        }

        // 4. Repeat last spoken reply
        if (lower in listOf("повтори", "повтори ответ", "что ты сказал", "что ты сказала", "я не расслышал", "скажи еще раз", "не понял что ты сказал")) {
            val prev = ConversationContext.lastSpokenReply
            return if (prev.isNotBlank()) {
                ContextualResolution.DirectReply("Повторяю: $prev")
            } else {
                ContextualResolution.DirectReply("Пока нечего повторять.")
            }
        }

        // 5. Contextual Math continuation ("плюс 50", "раздели пополам", "минус 20 процентов")
        RussianNumberParser.tryEvaluateContextualMath(trimmed)?.let { mathFollowUp ->
            return ContextualResolution.DirectReply(mathFollowUp)
        }

        // 6. Contextual "ещё" / "давай другой" / "продолжай"
        if (lower in listOf("еще", "еще раз", "еще один", "еще одну", "давай еще", "давай другой", "давай другую", "другой", "продолжай")) {
            val mapped = when (ConversationContext.lastAction) {
                "VOLUME_UP" -> "громче"
                "VOLUME_DOWN" -> "тише"
                "JOKE" -> "расскажи анекдот"
                "FACT" -> "интересный факт"
                "QUOTE" -> "дай совет"
                "RIDDLE" -> "загадай загадку"
                "COIN" -> "подбрось монетку"
                "DICE" -> "брось кубик"
                "RANDOM_NUM" -> "случайное число"
                else -> null
            }
            if (mapped != null) return ContextualResolution.RewrittenCommand(mapped)
        }

        // 7. Contextual "выключи" / "погаси" / "убери" without object
        if (lower in listOf("выключи", "отключи", "погаси", "выключи его", "погаси его", "убери свет")) {
            if (ConversationContext.flashlightOn) {
                return ContextualResolution.RewrittenCommand("выключи фонарик")
            }
        }

        // 8. Contextual "наоборот" / "верни как было" / "откати"
        if (lower in listOf("наоборот", "верни как было", "откати", "сделай обратно")) {
            val reversed = when (ConversationContext.lastAction) {
                "FLASHLIGHT_ON" -> "выключи фонарик"
                "FLASHLIGHT_OFF" -> "включи фонарик"
                "VOLUME_UP" -> "тише"
                "VOLUME_DOWN" -> "громче"
                "VOLUME_MUTE" -> "включи звук"
                else -> null
            }
            if (reversed != null) return ContextualResolution.RewrittenCommand(reversed)
        }

        // 9. Contextual follow-ups after Weather or Time ("а завтра?", "а в Астане?")
        if (lower in listOf("а завтра", "а на завтра", "что завтра", "как завтра", "на завтра")) {
            if (ConversationContext.lastAction == "WEATHER") {
                return ContextualResolution.RewrittenCommand("погода завтра")
            }
            if (ConversationContext.lastAction in listOf("DATE", "DAY_OF_WEEK")) {
                return ContextualResolution.RewrittenCommand("какой завтра день недели")
            }
        }

        val followCityMatch = Regex("^(?:а\\s+)?(?:как\\s+)?(?:в|во)\\s+([а-яёa-z\\-\\s]{3,25})$", RegexOption.IGNORE_CASE)
            .find(trimmed)
        if (followCityMatch != null) {
            val place = followCityMatch.groupValues[1].trim()
            if (ConversationContext.lastAction == "WEATHER") {
                return ContextualResolution.RewrittenCommand("погода в $place")
            }
            if (ConversationContext.lastAction == "WORLD_TIME") {
                return ContextualResolution.RewrittenCommand("сколько времени в $place")
            }
        }

        return ContextualResolution.PassThrough
    }

    /**
     * Infers what the user wants from natural everyday descriptions / life situations
     * when they don't use strict command words!
     */
    fun inferNaturalSituationIntent(cleanedLower: String, original: String): String? {
        val s = cleanedLower

        // 1. Darkness / Need light -> Flashlight ON
        if (s.contains("темно") || s.contains("ничего не видно") || s.contains("не видно ничего") ||
            s.contains("подсвети") || s.contains("посветить") || s.contains("свет вырубили") ||
            s.contains("отключили свет") || s.contains("уронил под") || s.contains("дай свет") ||
            s.contains("вруби свет") || s.contains("лампочк")
        ) {
            return "включи фонарик"
        }

        // 2. Glare / Light no longer needed -> Flashlight OFF
        if ((s.contains("слепит") || s.contains("свет дали") || s.contains("уже светло") || s.contains("хватит светить"))) {
            return "выключи фонарик"
        }

        // 3. Quiet situations: meeting, lecture, sleeping child, cinema -> Mute
        if (s.contains("совещани") || s.contains("на паре") || s.contains("на уроке") ||
            s.contains("в кино") || s.contains("ребенок спит") || s.contains("дети спят") ||
            s.contains("чтобы не пиликал") || s.contains("чтобы не звенел") || s.contains("не шумел")
        ) {
            return "без звука"
        }

        // 4. Too loud / yelling -> Volume DOWN
        if (s.contains("слишком громко") || s.contains("очень громко") || s.contains("сильно орет") ||
            s == "орет" || s.contains("уши болят") || s.contains("уши вянут") || s.contains("потише чуток") ||
            s.contains("чуть тише") || s.contains("немного тише")
        ) {
            return "тише"
        }

        // 5. Too quiet / can't hear -> Volume UP
        if (s.contains("плохо слышно") || s.contains("ничего не слышно") || s.contains("не слышу") ||
            s.contains("слишком тихо") || s.contains("очень тихо") || s.contains("чуть громче") ||
            s.contains("немного громче") || s.contains("добавь звук")
        ) {
            return "громче"
        }

        // 6. Umbrella / Clothing / Outside conditions -> Weather with advice!
        if (s.contains("брать зонт") || s.contains("нужен зонт") || s.contains("зонтик") ||
            s.contains("как одеться") || s.contains("что надеть") || s.contains("холодно на улице") ||
            s.contains("жарко на улице") || s.contains("тепло на улице") || s.contains("что там за окном") ||
            s.contains("что на улице") || s.contains("идет дождь") || s.contains("будет дождь") ||
            s.contains("будет снег") || s.contains("надо куртку")
        ) {
            return "__WEATHER_ADVICE__"
        }

        // 7. Battery anxiety ("телефон садится?", "доживёт до вечера?", "ставить на зарядку?")
        if (s.contains("садится телефон") || s.contains("телефон садится") || s.contains("скоро сядет") ||
            s.contains("разрядил") || s.contains("надо заряжать") || s.contains("ставить на зарядку") ||
            s.contains("доживет до") || s.contains("сколько процентов осталось")
        ) {
            return "сколько заряда"
        }

        // 8. Phone overheating
        if (s.contains("телефон горячий") || s.contains("греется телефон") || s.contains("нагрелся")) {
            return "температура батареи"
        }

        // 9. Storage full / Phone slow
        if (s.contains("память забита") || s.contains("нет места") || s.contains("мало места") ||
            s.contains("места не хватает")
        ) {
            return "сколько свободной памяти"
        }
        if (s.contains("телефон тормозит") || s.contains("лагает")) {
            return "оперативная память"
        }

        // 10. Waking up / Nap / Oversleeping -> Alarm!
        if (s.contains("мне вставать") || s.contains("завтра вставать") || s.contains("рано вставать") ||
            s.contains("не проспать") || s.contains("подними меня")
        ) {
            return "поставь будильник $original"
        }
        if (s.contains("вздремнуть") || s.contains("полежу") || s.contains("поспать") && (s.contains("минут") || s.contains("час"))) {
            return "будильник через $original"
        }

        // 11. Cooking / Boiling / Oven / Reminder in X minutes -> Timer!
        if ((s.contains("варить") || s.contains("варятся") || s.contains("поставил плиту") ||
                s.contains("духовк") || s.contains("чайник") || s.contains("макарон") ||
                s.contains("пельмен") || s.contains("яйц") || s.contains("выключить плиту")) &&
            (s.contains("минут") || s.contains("секунд") || s.contains("полчаса"))
        ) {
            return "поставь таймер $original"
        }

        // 12. Everyday Real-Life Needs -> Nearby Places on Map!
        if (s.contains("хочу есть") || s.contains("хочу кушать") || s.contains("проголодал") ||
            s.contains("где поесть") || s.contains("где перекусить") || s.contains("где пообедать") || s.contains("где поужинать")
        ) {
            return "ближайшее кафе"
        }
        if (s.contains("хочу кофе") || s.contains("выпить кофе") || s.contains("кофейн")) {
            return "покажи на карте кофейня рядом"
        }
        if (s.contains("болит голова") || s.contains("болит зуб") && s.contains("таблет") ||
            s.contains("купить лекарств") || s.contains("нужны таблетки")
        ) {
            return "ближайшая аптека"
        }
        if (s.contains("болит зуб") && !s.contains("таблет")) {
            return "покажи на карте стоматология рядом"
        }
        if (s.contains("бензин заканчивается") || s.contains("бензин кончается") ||
            s.contains("мало бензина") || s.contains("надо заправиться") || s.contains("где заправиться")
        ) {
            return "ближайшая заправка"
        }
        if (s.contains("помыть машину") || s.contains("автомойк")) {
            return "покажи на карте автомойка рядом"
        }
        if (s.contains("спустило колесо") || s.contains("шиномонтаж") || s.contains("автосервис")) {
            return "покажи на карте шиномонтаж рядом"
        }
        if (s.contains("снять деньги") || s.contains("снять налич") || s.contains("нужны наличные")) {
            return "ближайший банкомат"
        }
        if (s.contains("купить продукты") || s.contains("хлеба купить")) {
            return "ближайший супермаркет"
        }
        if (s.contains("подстричься") || s.contains("барбершоп") || s.contains("парикмахерск")) {
            return "покажи на карте парикмахерская рядом"
        }

        // 13. Connectivity & Accessories Intentions
        if (s.contains("подключить наушники") || s.contains("подключить колонку") || s.contains("подключить часы")) {
            return "настройки блютуз"
        }
        if (s.contains("подключиться к вайфай") || s.contains("поменять вайфай") || s.contains("сети вайфай")) {
            return "настройки вайфай"
        }
        if (s.contains("раздать интернет") || s.contains("раздать вайфай") || s.contains("точка доступа")) {
            return "настройки точки доступа"
        }
        if (s.contains("оплатить телефоном") || s.contains("бесконтактная оплата")) {
            return "настройки nfc"
        }

        // 14. Mood / Boredom / Sleep / Relaxation
        if (s.contains("мне скучно") || s.contains("скучно") || s.contains("развлеки меня") || s.contains("чем заняться")) {
            return "загадай загадку"
        }
        if (s.contains("грустно") || s.contains("плохое настроение") || s.contains("подними настроение")) {
            return "расскажи анекдот"
        }
        if (s.contains("не могу уснуть") || s.contains("бессонниц") || s.contains("музыка для сна") || s.contains("хочу расслабиться")) {
            return "включи на ютубе спокойная музыка для сна и отдыха"
        }
        if (s.contains("хочу взбодриться") || s.contains("музыка для тренировки")) {
            return "включи на ютубе бодрая музыка для тренировки"
        }

        return null
    }

    fun startCitiesGame(): String {
        ConversationContext.resetCitiesGame()
        ConversationContext.citiesGameActive = true
        val firstCity = citiesDictionary.random()
        ConversationContext.usedCities.add(firstCity.lowercase(ruLocale))
        val letter = lastPlayableLetter(firstCity)
        ConversationContext.expectedCityLetter = letter
        return "Давайте поиграем в города! Я начинаю: $firstCity. Вам на букву «${letter.uppercaseChar()}». Чтобы закончить, скажите «хватит играть»."
    }

    private fun handleCitiesGameTurn(userRaw: String): ContextualResolution {
        val candidate = userRaw.trim().trim(' ', '.', ',', '!', '?')
        val lower = candidate.lowercase(ruLocale).replace('ё', 'е')
        if (lower.length < 2) {
            return ContextualResolution.DirectReply("Назовите существующий город или скажите «хватит играть».")
        }

        val expected = ConversationContext.expectedCityLetter
        if (expected != null && lower.first() != expected) {
            return ContextualResolution.DirectReply("Город должен начинаться на букву «${expected.uppercaseChar()}»! Попробуйте ещё раз.")
        }

        if (ConversationContext.usedCities.contains(lower)) {
            return ContextualResolution.DirectReply("Город «$candidate» уже называли в этой игре! Назовите другой на «${(expected ?: lower.first()).uppercaseChar()}».")
        }

        ConversationContext.usedCities.add(lower)
        val botRequired = lastPlayableLetter(lower)
        val replyCity = citiesDictionary.firstOrNull { city ->
            val cl = city.lowercase(ruLocale).replace('ё', 'е')
            cl.first() == botRequired && !ConversationContext.usedCities.contains(cl)
        }

        if (replyCity == null) {
            val total = ConversationContext.usedCities.size
            ConversationContext.resetCitiesGame()
            return ContextualResolution.DirectReply("Отлично, город «$candidate» принят! Я больше не могу вспомнить город на букву «${botRequired.uppercaseChar()}» — вы победили! Всего названо городов: $total.")
        }

        ConversationContext.usedCities.add(replyCity.lowercase(ruLocale).replace('ё', 'е'))
        val nextForUser = lastPlayableLetter(replyCity)
        ConversationContext.expectedCityLetter = nextForUser
        return ContextualResolution.DirectReply("$replyCity! Вам на букву «${nextForUser.uppercaseChar()}».")
    }

    private fun lastPlayableLetter(word: String): Char {
        val clean = word.lowercase(ruLocale).replace('ё', 'е').filter { it.isLetter() }
        val skip = setOf('ь', 'ъ', 'ы', 'й')
        for (i in clean.length - 1 downTo 0) {
            if (clean[i] !in skip) return clean[i]
        }
        return clean.lastOrNull() ?: 'а'
    }

    private fun looksLikePhoneCommandDuringGame(lower: String): Boolean {
        val prefixes = listOf(
            "включи ", "выключи ", "открой ", "запусти ", "поставь ", "таймер", "будильник",
            "сколько времени", "какая погода", "громче", "тише", "без звука", "останови помощника", "позвони"
        )
        return prefixes.any { lower.startsWith(it) || lower == it.trim() }
    }
}
