package com.example.project12

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale

data class ContactPhoneItem(
    val displayName: String,
    val number: String,
    val starred: Boolean,
    val isMobile: Boolean
)

object ContactCaller {

    private val ruLocale = Locale("ru", "RU")
    private val mainHandler = Handler(Looper.getMainLooper())

    // Equivalence groups so "Саше" matches "Александр", "Маме" matches "Мамочка", etc.
    private val synonymGroups: List<Set<String>> = listOf(
        setOf("мама", "мам", "мамочка", "мамуля", "матушка", "мать"),
        setOf("папа", "пап", "папуля", "папочка", "батя", "отец"),
        setOf("жена", "любимая", "супруга", "зайка", "родная"),
        setOf("муж", "любимый", "супруг", "родной"),
        setOf("брат", "братишка", "братан", "бро"),
        setOf("сестра", "сестренка", "сестричка"),
        setOf("бабушка", "бабуля", "баба"),
        setOf("дедушка", "дедуля", "дед"),
        setOf("сын", "сынок", "сынишка"),
        setOf("дочь", "дочка", "доченька"),
        setOf("александр", "александра", "саша", "саня", "санек", "шура"),
        setOf("дмитрий", "дима", "димон", "митя"),
        setOf("алексей", "леша", "леха", "алеша"),
        setOf("сергей", "сережа", "серега", "серый"),
        setOf("евгений", "евгения", "женя", "жека"),
        setOf("владимир", "вова", "володя", "вован"),
        setOf("николай", "коля", "колян"),
        setOf("михаил", "миша", "миха"),
        setOf("павел", "паша", "пашок"),
        setOf("иван", "ваня", "ванюша"),
        setOf("петр", "петя"),
        setOf("юрий", "юра"),
        setOf("вячеслав", "слава", "славик"),
        setOf("станислав", "стас", "стасик"),
        setOf("владислав", "влад", "владик"),
        setOf("ярослав", "ярик"),
        setOf("максим", "макс"),
        setOf("артем", "тема"),
        setOf("константин", "костя"),
        setOf("антон", "тоха"),
        setOf("роман", "рома"),
        setOf("андрей", "андрюха"),
        setOf("денис", "ден"),
        setOf("елена", "лена", "леночка"),
        setOf("наталья", "наталия", "наташа"),
        setOf("екатерина", "катя", "катюша"),
        setOf("анна", "аня", "анюта", "анечка"),
        setOf("мария", "маша", "маруся"),
        setOf("ольга", "оля"),
        setOf("татьяна", "таня", "танюша"),
        setOf("светлана", "света"),
        setOf("ирина", "ира", "иришка"),
        setOf("юлия", "юля"),
        setOf("анастасия", "настя", "настена"),
        setOf("дарья", "даша"),
        setOf("ксения", "ксюша"),
        setOf("виктория", "вика"),
        setOf("валерия", "лера"),
        setOf("гульнара", "гуля"),
        setOf("людмила", "люда", "мила"),
        setOf("галина", "галя"),
        setOf("надежда", "надя"),
        setOf("любовь", "люба")
    )

    fun handleCallOrContactMessage(context: Context, lower: String, original: String): String? {
        // 1. Check "напиши <Контакту> в ватсап <текст>"
        val waContactMsg = Regex(
            "^(?:напиши|отправь)\\s+(?:сообщение\\s+)?([а-яёa-z]+)\\s+в\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|whatsapp)\\s*(.*)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (waContactMsg != null) {
            val contactQuery = waContactMsg.groupValues[1].trim()
            val textBody = waContactMsg.groupValues[2].trim()
            return openWhatsAppForContact(context, contactQuery, textBody)
        }

        // 2. Check call phrasings:
        // "позвони ...", "набери ...", "включи звонок ...", "включи вызов ...", "сделай звонок ...", "вызови ...", "звякни ..."
        val isBareCallTrigger = lower in listOf(
            "позвони", "позвонить", "набери", "набрать", "включи звонок", "сделай звонок", "включи вызов", "позвони контакту"
        )
        if (isBareCallTrigger) {
            ConversationContext.setPendingSlot(PendingSlot.CALL_TARGET)
            return "Кому позвонить? Назовите имя контакта или номер телефона."
        }

        val callMatch = Regex(
            "^(?:позвони|позвонить|набери|набрать|звякни|включи звонок|включить звонок|включи вызов|сделай звонок|сделай вызов|вызови контакт|соедини с)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original) ?: return null

        var targetRaw = callMatch.groupValues[1]
            .replace(Regex("^(?:контакту|контакт|абоненту|на номер|номер|по номеру)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:пожалуйста|плиз|срочно|быстренько|прямо сейчас)$", RegexOption.IGNORE_CASE), "")
            .trim(' ', '.', ',', '!', '?')

        if (targetRaw.isEmpty()) {
            ConversationContext.setPendingSlot(PendingSlot.CALL_TARGET)
            return "Кому позвонить? Скажите имя из телефонной книги или номер."
        }

        val viaWhatsApp = Regex("\\s+(?:по|в|через)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|whatsapp)$", RegexOption.IGNORE_CASE)
            .containsMatchIn(targetRaw)
        if (viaWhatsApp) {
            targetRaw = targetRaw.replace(
                Regex("\\s+(?:по|в|через)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|whatsapp)$", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            return openWhatsAppForContact(context, targetRaw, "")
        }

        return placeCallToTarget(context, targetRaw)
    }

    fun placeCallToTarget(context: Context, targetRaw: String): String {
        // Check if target is a phone number (digits or spoken numbers like "8 777 ...")
        val normalizedDigitsCandidate = RussianNumberParser.normalizeNumbersAndTime(targetRaw)
        val digitsOnly = normalizedDigitsCandidate.filter { it.isDigit() || it == '+' }
        val lettersOnly = targetRaw.filter { it.isLetter() }

        if (digitsOnly.length >= 5 && lettersOnly.length <= 3) {
            return initiatePhoneCall(context, digitsOnly, displayLabel = digitsOnly)
        }

        // Otherwise resolve contact by name in Android Contacts!
        if (!hasReadContactsPermission(context)) {
            requestCallAndContactPermissionsIfPossible()
            return "Чтобы я могла звонить контактам по имени, разрешите доступ к Контактам и Телефону в появившемся окне, и повторите команду!"
        }

        val contacts = loadAllContacts(context)
        if (contacts.isEmpty()) {
            return "В телефонной книге пока нет сохранённых контактов с номерами."
        }

        val bestMatch = findBestMatchingContact(targetRaw, contacts)
        if (bestMatch == null) {
            return "Я не нашла контакт «$targetRaw» в вашей телефонной книге. Попробуйте назвать имя так, как оно записано в контактах."
        }

        return initiatePhoneCall(context, bestMatch.number, displayLabel = bestMatch.displayName)
    }

    private fun openWhatsAppForContact(context: Context, contactQuery: String, message: String): String {
        if (!hasReadContactsPermission(context)) {
            requestCallAndContactPermissionsIfPossible()
            return "Разрешите доступ к контактам на экране, чтобы я нашла номер «$contactQuery» для WhatsApp."
        }
        val contacts = loadAllContacts(context)
        val match = findBestMatchingContact(contactQuery, contacts)
            ?: return "Контакт «$contactQuery» не найден в телефонной книге."

        val intlDigits = normalizeInternationalPhoneForWa(match.number)
        if (intlDigits.length < 7) {
            return "У контакта ${match.displayName} некорректный номер для WhatsApp."
        }
        val textParam = if (message.isNotEmpty()) "?text=${URLEncoder.encode(message, "UTF-8")}" else ""
        val uri = Uri.parse("https://wa.me/$intlDigits$textParam")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                true
            } catch (_: Exception) {
                false
            }
        }
        return if (started) {
            "Открываю WhatsApp с контактом ${match.displayName}."
        } else {
            "Не удалось открыть WhatsApp."
        }
    }

    private fun normalizeInternationalPhoneForWa(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        // Convert local 87... / 89... (11 digits KZ/RU) to 77... / 79...
        return if (digits.length == 11 && digits.startsWith("8")) {
            "7" + digits.substring(1)
        } else {
            digits
        }
    }

    private fun initiatePhoneCall(context: Context, rawNumber: String, displayLabel: String): String {
        val cleanNumber = rawNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val encodedTel = Uri.parse("tel:${Uri.encode(cleanNumber)}")
        val canDirectCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        ConversationContext.lastAction = "CALL"

        if (canDirectCall) {
            // Brief 850ms delay so Nelly's voice confirmation ("Звоню: Мама") is heard before cellular call grabs audio focus
            mainHandler.postDelayed({
                val callIntent = Intent(Intent.ACTION_CALL, encodedTel).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(callIntent)
                } catch (_: Exception) {
                    val dialFallback = Intent(Intent.ACTION_DIAL, encodedTel).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(dialFallback)
                    } catch (_: Exception) {
                    }
                }
            }, 850L)
            return "Звоню: $displayLabel."
        } else {
            requestCallAndContactPermissionsIfPossible()
            val dialIntent = Intent(Intent.ACTION_DIAL, encodedTel).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(dialIntent)
                "Набрала номер контакта $displayLabel — нажмите кнопку вызова (или разрешите прямые звонки)."
            } catch (_: Exception) {
                "Не удалось запустить звонок для $displayLabel."
            }
        }
    }

    private fun loadAllContacts(context: Context): List<ContactPhoneItem> {
        val result = mutableListOf<ContactPhoneItem>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idxName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxNum = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxStar = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val idxType = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (cursor.moveToNext()) {
                    val name = if (idxName >= 0) cursor.getString(idxName)?.trim().orEmpty() else ""
                    val num = if (idxNum >= 0) cursor.getString(idxNum)?.trim().orEmpty() else ""
                    val starred = idxStar >= 0 && cursor.getInt(idxStar) == 1
                    val type = if (idxType >= 0) cursor.getInt(idxType) else -1
                    val isMobile = type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        result.add(ContactPhoneItem(name, num, starred, isMobile))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun findBestMatchingContact(spokenQuery: String, contacts: List<ContactPhoneItem>): ContactPhoneItem? {
        val queryNorm = PhoneticSimilarity.normalizeBasic(spokenQuery)
        val queryTokens = queryNorm.split(" ").filter { it.isNotEmpty() }
        if (queryTokens.isEmpty()) return null

        var bestItem: ContactPhoneItem? = null
        var bestScore = 0.0

        for (item in contacts) {
            val contactNorm = PhoneticSimilarity.normalizeBasic(item.displayName)
            val contactTokens = contactNorm.split(" ").filter { it.isNotEmpty() }
            if (contactTokens.isEmpty()) continue

            // Full string similarity
            var score = PhoneticSimilarity.wordSimilarity(queryNorm, contactNorm)

            // Token-level similarity (handles "позвони Саше" -> "Саша Работа" or "Александр Иванов")
            var tokenSum = 0.0
            for (qt in queryTokens) {
                val qtSynonyms = expandNameSynonyms(qt)
                var bestForToken = 0.0
                for (ct in contactTokens) {
                    val ctSynonyms = expandNameSynonyms(ct)
                    // Direct synonym match (e.g. "саше" -> stem "саш" -> synonym group matches "александр"!)
                    val synOverlap = qtSynonyms.intersect(ctSynonyms).isNotEmpty()
                    val pairScore = if (synOverlap) {
                        0.96
                    } else {
                        PhoneticSimilarity.wordSimilarity(qt, ct)
                    }
                    if (pairScore > bestForToken) bestForToken = pairScore
                }
                tokenSum += bestForToken
            }

            val avgTokenScore = tokenSum / queryTokens.size.toDouble()
            // Slightly reward exact multi-token match when contact has multiple words
            val multiBonus = if (queryTokens.size >= 2 && contactTokens.size >= 2) 0.03 else 0.0
            val combinedTokenScore = (avgTokenScore + multiBonus).coerceAtMost(1.0)

            if (combinedTokenScore > score) {
                score = combinedTokenScore
            }

            // Tie-breakers: prefer starred contacts & mobile phone numbers
            if (item.starred) score += 0.015
            if (item.isMobile) score += 0.008

            if (score > bestScore) {
                bestScore = score
                bestItem = item
            }
        }

        return if (bestScore >= 0.72) bestItem else null
    }

    private fun expandNameSynonyms(rawWord: String): Set<String> {
        val clean = rawWord.lowercase(ruLocale).replace('ё', 'е').trim()
        val stem = PhoneticSimilarity.stripRussianEndings(clean)
        val out = mutableSetOf(clean, stem)
        for (group in synonymGroups) {
            val matchesGroup = group.any { member ->
                member == clean || PhoneticSimilarity.stripRussianEndings(member) == stem
            }
            if (matchesGroup) {
                out.addAll(group)
                group.forEach { out.add(PhoneticSimilarity.stripRussianEndings(it)) }
            }
        }
        return out
    }

    private fun hasReadContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun requestCallAndContactPermissionsIfPossible() {
        val act = AssistantStateBus.getActiveActivity() ?: return
        mainHandler.post {
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_CONTACTS)
            }
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.CALL_PHONE)
            }
            if (perms.isNotEmpty()) {
                ActivityCompat.requestPermissions(act, perms.toTypedArray(), 1005)
            }
        }
    }
}
