package com.example.project12

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

data class ContactPhoneItem(
    val displayName: String,
    val number: String,
    val starred: Boolean,
    val isMobile: Boolean
)

object ContactCaller {

    private val ruLocale = Locale("ru", "RU")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

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

    /**
     * Handles voice calls AND hands-free automatic message sending (SMS, WhatsApp, Telegram, GPS share).
     */
    fun handleCallOrContactMessage(
        context: Context,
        lower: String,
        original: String,
        userDisplay: String,
        onAsyncReply: (String, String) -> Unit
    ): CommandResult? {

        // 0. Share My GPS Coordinates directly to a Contact!
        // e.g. "отправь мои координаты маме в ватсап", "скинь мою геолокацию Саше по смс", "отправь где я маме"
        val shareLocMatch = Regex(
            "^(?:отправь|скинь|пришли|передай)\\s+(?:мои координаты|мою геолокацию|мое местоположение|моё местоположение|где я)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (shareLocMatch != null) {
            val rest = shareLocMatch.groupValues[1].trim()
            val restLower = rest.lowercase(ruLocale).replace('ё', 'е')
            val channel = when {
                Regex("ватсап|вотсап|вацап|whatsapp").containsMatchIn(restLower) -> "WHATSAPP"
                Regex("телеграм|тилеграм|телегу|telegram").containsMatchIn(restLower) -> "TELEGRAM"
                else -> "SMS"
            }
            val contactName = rest
                .replace(Regex("\\s*(?:в|по|через|на)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|whatsapp|телеграм\\w*|тилеграм\\w*|телегу|telegram|смс|sms)\\s*", RegexOption.IGNORE_CASE), "")
                .trim(' ', '.', ',', '!', '?')

            bgExecutor.execute {
                val locText = LocationAnd2GisEngine.buildMyLocationShareTextBlocking(context.applicationContext)
                mainHandler.post {
                    if (locText == null) {
                        onAsyncReply(userDisplay, "Не удалось получить координаты GPS. Проверьте разрешение Геолокации.")
                    } else {
                        val sendReply = when (channel) {
                            "WHATSAPP" -> sendWhatsAppToContactAuto(context, contactName, locText)
                            "TELEGRAM" -> sendTelegramToContactAuto(context, contactName, locText)
                            else -> sendSmsAuto(context, contactName, locText)
                        }
                        onAsyncReply(userDisplay, sendReply)
                    }
                }
            }
            return CommandResult(spokenResponse = "Определяю ваши координаты для отправки…", asyncPending = true)
        }

        // 1. WhatsApp Auto-Send to Contact (supports BOTH word orders!):
        // Order A: "напиши / отправь <Контакту> в ватсап <текст>"
        val waOrderA = Regex(
            "^(?:напиши|отправь|скинь|отошли)\\s+(?:сообщение\\s+)?([а-яёa-z]+)\\s+(?:в|по|на)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|воцап\\w*|whatsapp)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (waOrderA != null) {
            return CommandResult(
                sendWhatsAppToContactAuto(context, waOrderA.groupValues[1].trim(), waOrderA.groupValues[2].trim())
            )
        }

        // Order B: "напиши / отправь в ватсап <Контакту> <текст>"
        val waOrderB = Regex(
            "^(?:напиши|отправь|скинь|отошли)\\s+(?:сообщение\\s+)?(?:в|по|на)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|воцап\\w*|whatsapp)\\s+([а-яёa-z]+)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (waOrderB != null) {
            val firstWord = waOrderB.groupValues[1].trim()
            val remainder = waOrderB.groupValues[2].trim()
            // Check if firstWord matches a real contact in the user's phonebook!
            val matchedContact = findContactIfGranted(context, firstWord)
            if (matchedContact != null) {
                return CommandResult(sendWhatsAppToResolvedContact(context, matchedContact, remainder))
            }
        }

        // 2. Telegram Auto-Send to Contact (BOTH word orders!):
        val tgOrderA = Regex(
            "^(?:напиши|отправь|скинь|отошли)\\s+(?:сообщение\\s+)?([а-яёa-z]+)\\s+(?:в|по|на)\\s+(?:телеграм\\w*|тилеграм\\w*|телегу|телеге|telegram)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (tgOrderA != null) {
            return CommandResult(
                sendTelegramToContactAuto(context, tgOrderA.groupValues[1].trim(), tgOrderA.groupValues[2].trim())
            )
        }

        val tgOrderB = Regex(
            "^(?:напиши|отправь|скинь|отошли)\\s+(?:сообщение\\s+)?(?:в|по|на)\\s+(?:телеграм\\w*|тилеграм\\w*|телегу|телеге|telegram)\\s+([а-яёa-z]+)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (tgOrderB != null) {
            val firstWord = tgOrderB.groupValues[1].trim()
            val remainder = tgOrderB.groupValues[2].trim()
            val matchedContact = findContactIfGranted(context, firstWord)
            if (matchedContact != null) {
                return CommandResult(sendTelegramToResolvedContact(context, matchedContact, remainder))
            }
        }

        // 3. Direct Automatic SMS Sending ("отправь смс маме скоро буду", "напиши сообщение Саше привет")!
        val smsToTargetMatch = Regex(
            "^(?:отправь|напиши|скинь|пошли)\\s+(?:смс|смску|sms|сообщение)\\s+(?:на номер\\s+|абоненту\\s+|контакту\\s+)?([+0-9а-яёa-z]+)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (smsToTargetMatch != null) {
            val recipient = smsToTargetMatch.groupValues[1].trim()
            val msgBody = smsToTargetMatch.groupValues[2].trim()
            // Avoid swallowing "напиши сообщение в ватсап ..."
            if (recipient.lowercase(ruLocale) !in listOf("в", "на", "по", "через")) {
                return CommandResult(sendSmsAuto(context, recipient, msgBody))
            }
        }

        // Also "напиши маме скоро буду" / "отправь Саше я выхожу" (when NOT inside an open chat, defaults to WhatsApp if Accessibility is on, or SMS!)
        val directPersonMsgMatch = Regex(
            "^(?:напиши|отправь)\\s+([а-яё]{3,20})\\s+(?:что\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        ).find(original)
        if (directPersonMsgMatch != null) {
            val candidatePerson = directPersonMsgMatch.groupValues[1].trim()
            val body = directPersonMsgMatch.groupValues[2].trim()
            val nonPersonWords = setOf(
                "заметку", "список", "задачу", "текст", "письмо", "смс", "сообщение",
                "сюда", "ответ", "комментарий", "в", "на", "по"
            )
            if (candidatePerson.lowercase(ruLocale) !in nonPersonWords && body.isNotEmpty()) {
                val matchedContact = findContactIfGranted(context, candidatePerson)
                if (matchedContact != null) {
                    return if (NellyAccessibilityService.isEnabled(context)) {
                        CommandResult(sendWhatsAppToResolvedContact(context, matchedContact, body))
                    } else {
                        CommandResult(sendSmsToResolvedNumber(context, matchedContact.number, matchedContact.displayName, body))
                    }
                }
            }
        }

        // 4. Voice Call Phrasings:
        val isBareCallTrigger = lower in listOf(
            "позвони", "позвонить", "набери", "набрать", "включи звонок", "сделай звонок", "включи вызов", "позвони контакту"
        )
        if (isBareCallTrigger) {
            ConversationContext.setPendingSlot(PendingSlot.CALL_TARGET)
            return CommandResult("Кому позвонить? Назовите имя контакта или номер телефона.")
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
            return CommandResult("Кому позвонить? Скажите имя из телефонной книги или номер.")
        }

        val viaWhatsApp = Regex("\\s+(?:по|в|через)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|whatsapp)$", RegexOption.IGNORE_CASE)
            .containsMatchIn(targetRaw)
        if (viaWhatsApp) {
            targetRaw = targetRaw.replace(
                Regex("\\s+(?:по|в|через)\\s+(?:ватсап\\w*|вотсап\\w*|вацап\\w*|whatsapp)$", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            return CommandResult(openWhatsAppChatCard(context, targetRaw))
        }

        return CommandResult(placeCallToTarget(context, targetRaw))
    }

    fun sendSmsAuto(context: Context, recipientRaw: String, messageBody: String): String {
        val digitsCandidate = recipientRaw.filter { it.isDigit() || it == '+' }
        if (digitsCandidate.length >= 5) {
            return sendSmsToResolvedNumber(context, digitsCandidate, digitsCandidate, messageBody)
        }

        if (!hasReadContactsPermission(context)) {
            requestCallSmsAndContactPermissionsIfPossible()
            return "Разрешите доступ к Контактам и СМС на экране, чтобы я сама отправляла сообщения по имени!"
        }

        val contacts = loadAllContacts(context)
        val match = findBestMatchingContact(recipientRaw, contacts)
            ?: return "Контакт «$recipientRaw» не найден в телефонной книге."

        return sendSmsToResolvedNumber(context, match.number, match.displayName, messageBody)
    }

    @Suppress("DEPRECATION")
    private fun sendSmsToResolvedNumber(
        context: Context,
        rawNumber: String,
        displayLabel: String,
        messageBody: String
    ): String {
        val cleanPhone = rawNumber.filter { it.isDigit() || it == '+' }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            return try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                } else {
                    SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(messageBody)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(cleanPhone, null, messageBody, null, null)
                }
                "СМС для $displayLabel отправлено автоматически: «$messageBody»."
            } catch (e: Exception) {
                fallbackSmsUiWithAutoClick(context, cleanPhone, displayLabel, messageBody)
            }
        } else {
            requestCallSmsAndContactPermissionsIfPossible()
            return fallbackSmsUiWithAutoClick(context, cleanPhone, displayLabel, messageBody)
        }
    }

    private fun fallbackSmsUiWithAutoClick(
        context: Context,
        cleanPhone: String,
        displayLabel: String,
        messageBody: String
    ): String {
        NellyAccessibilityService.scheduleAutoSend(
            targetPackages = emptySet(),
            messageText = messageBody,
            needsTextInjection = false
        )
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
            putExtra("sms_body", messageBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            if (NellyAccessibilityService.isEnabled(context)) {
                "Отправляю СМС для $displayLabel…"
            } else {
                "Разрешите отправку СМС на экране, чтобы я отправляла их полностью сама без открытия окна!"
            }
        } catch (_: Exception) {
            "Не удалось отправить СМС."
        }
    }

    fun sendWhatsAppToContactAuto(context: Context, contactQuery: String, message: String): String {
        if (!hasReadContactsPermission(context)) {
            requestCallSmsAndContactPermissionsIfPossible()
            return "Разрешите доступ к контактам на экране, чтобы я нашла номер «$contactQuery» для отправки в WhatsApp."
        }
        val contacts = loadAllContacts(context)
        val match = findBestMatchingContact(contactQuery, contacts)
            ?: return "Контакт «$contactQuery» не найден в телефонной книге."

        return sendWhatsAppToResolvedContact(context, match, message)
    }

    private fun sendWhatsAppToResolvedContact(
        context: Context,
        match: ContactPhoneItem,
        message: String
    ): String {
        val intlDigits = normalizeInternationalPhone(match.number)
        if (intlDigits.length < 7) {
            return "У контакта ${match.displayName} некорректный номер для WhatsApp."
        }

        // Schedule AccessibilityService to automatically click WhatsApp's Send button as soon as chat opens!
        NellyAccessibilityService.scheduleAutoSend(
            targetPackages = setOf("com.whatsapp", "com.whatsapp.w4b"),
            messageText = message,
            needsTextInjection = false
        )

        val uri = Uri.parse("https://wa.me/$intlDigits?text=${URLEncoder.encode(message, "UTF-8")}")
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

        if (!started) return "Не удалось открыть WhatsApp."

        return if (NellyAccessibilityService.isEnabled(context)) {
            "Отправляю в WhatsApp контакту ${match.displayName}: «$message»!"
        } else {
            "Открыла чат с ${match.displayName}. Включите управление экраном для Нелли на главном экране, чтобы я сама нажимала кнопку Отправить!"
        }
    }

    fun sendTelegramToContactAuto(context: Context, contactQuery: String, message: String): String {
        if (!hasReadContactsPermission(context)) {
            requestCallSmsAndContactPermissionsIfPossible()
            return "Разрешите доступ к контактам на экране, чтобы я нашла номер «$contactQuery» для Telegram."
        }
        val contacts = loadAllContacts(context)
        val match = findBestMatchingContact(contactQuery, contacts)
            ?: return "Контакт «$contactQuery» не найден в телефонной книге."

        return sendTelegramToResolvedContact(context, match, message)
    }

    private fun sendTelegramToResolvedContact(
        context: Context,
        match: ContactPhoneItem,
        message: String
    ): String {
        val intlDigits = normalizeInternationalPhone(match.number)
        if (intlDigits.length < 7) {
            return "У контакта ${match.displayName} некорректный номер для Telegram."
        }

        NellyAccessibilityService.scheduleAutoSend(
            targetPackages = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
            messageText = message,
            needsTextInjection = true
        )

        val tgUri = Uri.parse("tg://resolve?phone=$intlDigits")
        val intent = Intent(Intent.ACTION_VIEW, tgUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

        if (!started) return "Приложение Telegram не установлено."

        return if (NellyAccessibilityService.isEnabled(context)) {
            "Отправляю в Telegram контакту ${match.displayName}: «$message»!"
        } else {
            "Открыла чат Telegram с ${match.displayName}. Включите управление экраном на главном экране, чтобы я сама отправляла текст!"
        }
    }

    private fun openWhatsAppChatCard(context: Context, contactQuery: String): String {
        if (!hasReadContactsPermission(context)) {
            requestCallSmsAndContactPermissionsIfPossible()
            return "Разрешите доступ к контактам на экране."
        }
        val match = findBestMatchingContact(contactQuery, loadAllContacts(context))
            ?: return "Контакт «$contactQuery» не найден."
        val intlDigits = normalizeInternationalPhone(match.number)
        val uri = Uri.parse("https://wa.me/$intlDigits")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Открываю контакт ${match.displayName} в WhatsApp."
        } catch (_: Exception) {
            "Не удалось открыть WhatsApp."
        }
    }

    fun placeCallToTarget(context: Context, targetRaw: String): String {
        val normalizedDigitsCandidate = RussianNumberParser.normalizeNumbersAndTime(targetRaw)
        val digitsOnly = normalizedDigitsCandidate.filter { it.isDigit() || it == '+' }
        val lettersOnly = targetRaw.filter { it.isLetter() }

        if (digitsOnly.length >= 5 && lettersOnly.length <= 3) {
            return initiatePhoneCall(context, digitsOnly, displayLabel = digitsOnly)
        }

        if (!hasReadContactsPermission(context)) {
            requestCallSmsAndContactPermissionsIfPossible()
            return "Чтобы я могла звонить контактам по имени, разрешите доступ к Контактам и Телефону в появившемся окне!"
        }

        val contacts = loadAllContacts(context)
        if (contacts.isEmpty()) {
            return "В телефонной книге пока нет сохранённых контактов с номерами."
        }

        val bestMatch = findBestMatchingContact(targetRaw, contacts)
            ?: return "Я не нашла контакт «$targetRaw» в вашей телефонной книге."

        return initiatePhoneCall(context, bestMatch.number, displayLabel = bestMatch.displayName)
    }

    private fun findContactIfGranted(context: Context, query: String): ContactPhoneItem? {
        if (!hasReadContactsPermission(context)) return null
        val contacts = loadAllContacts(context)
        return findBestMatchingContact(query, contacts)
    }

    private fun normalizeInternationalPhone(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
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
            requestCallSmsAndContactPermissionsIfPossible()
            val dialIntent = Intent(Intent.ACTION_DIAL, encodedTel).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(dialIntent)
                "Набрала номер контакта $displayLabel — нажмите кнопку вызова."
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

            var score = PhoneticSimilarity.wordSimilarity(queryNorm, contactNorm)

            var tokenSum = 0.0
            for (qt in queryTokens) {
                val qtSynonyms = expandNameSynonyms(qt)
                var bestForToken = 0.0
                for (ct in contactTokens) {
                    val ctSynonyms = expandNameSynonyms(ct)
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
            val multiBonus = if (queryTokens.size >= 2 && contactTokens.size >= 2) 0.03 else 0.0
            val combinedTokenScore = (avgTokenScore + multiBonus).coerceAtMost(1.0)

            if (combinedTokenScore > score) {
                score = combinedTokenScore
            }

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

    private fun requestCallSmsAndContactPermissionsIfPossible() {
        val act = AssistantStateBus.getActiveActivity() ?: return
        mainHandler.post {
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_CONTACTS)
            }
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.CALL_PHONE)
            }
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.SEND_SMS)
            }
            if (perms.isNotEmpty()) {
                ActivityCompat.requestPermissions(act, perms.toTypedArray(), 1005)
            }
        }
    }
}
