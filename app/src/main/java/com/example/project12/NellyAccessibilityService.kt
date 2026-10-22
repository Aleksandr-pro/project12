package com.example.project12

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

enum class TwoGisUiAction {
    START_OR_CONTINUE,
    STOP_ROUTE,
    ZOOM_IN,
    ZOOM_OUT,
    MY_LOCATION
}

enum class SwipeDir {
    UP_NEXT,      // Scroll down / Next Reel or Short
    DOWN_PREV,    // Scroll up / Previous
    LEFT,
    RIGHT
}

/** Состояние экрана OlympTrade, считанное через спецвозможности. */
data class OlympScreenSnapshot(
    val packageName: String,
    val isForeground: Boolean,
    val isDemoAccount: Boolean,
    val isRealAccount: Boolean,
    val detectedAsset: String?,
    val visibleTexts: List<String>,
    val tradeButtonLabels: List<String>,
    val hasSearchField: Boolean,
    val hasExpiry15: Boolean
)

/** Действия внутри OlympTrade. */
enum class OlympUiAction {
    TRADE_UP,
    TRADE_DOWN,
    SET_EXPIRY_15,
    OPEN_ASSET_SELECTOR,
    CLOSE_POPUP
}

class NellyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        instance = this
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && !isIgnoredOverlayPackage(pkg)) {
            currentWindowPackage = pkg
            if (pkg != packageName && !isLauncherPackage(pkg)) {
                lastExternalAppPackage = pkg
                lastExternalAppTimestampMs = System.currentTimeMillis()
            }
        }

        checkPendingJobs()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun checkPendingJobs() {
        val now = System.currentTimeMillis()

        // 1. Pending Messenger Auto-Send
        val job = pendingAutoSend
        if (job != null) {
            if (now > job.expiresAtMs || job.completed) {
                pendingAutoSend = null
            } else {
                val root = rootInActiveWindow
                val rootPkg = root?.packageName?.toString().orEmpty()
                if (root != null && (job.targetPackages.isEmpty() || rootPkg in job.targetPackages)) {
                    tryExecuteAutoSendOnRoot(root, job)
                }
            }
        }

        // 2. Pending 2GIS Auto-Start ("В путь" / "Поехали")
        val navJob = pending2GisAutoStartUntilMs
        if (navJob > 0L) {
            if (now > navJob) {
                pending2GisAutoStartUntilMs = 0L
            } else {
                val root = rootInActiveWindow
                val rootPkg = root?.packageName?.toString().orEmpty()
                if (root != null && isNavigatorPackage(rootPkg)) {
                    if (findAndClickByTargets(root, startRouteLabels, startRouteViewIdHints)) {
                        pending2GisAutoStartUntilMs = 0L
                    }
                }
            }
        }
    }

    private fun tryExecuteAutoSendOnRoot(root: AccessibilityNodeInfo, job: AutoSendJob) {
        if (job.completed) return

        if (job.needsTextInjection && !job.textInjected) {
            val editNode = findEditableNode(root)
            if (editNode != null) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        job.messageText
                    )
                }
                editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val setOk = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setOk) {
                    job.textInjected = true
                    // Wait 280ms so WhatsApp/Telegram swaps the microphone icon to the Send button
                    mainHandler.postDelayed({
                        val freshRoot = rootInActiveWindow ?: return@postDelayed
                        if (!job.completed && clickMessengerSendButton(freshRoot)) {
                            job.completed = true
                            pendingAutoSend = null
                        }
                    }, 280L)
                    return
                }
            }
            return
        }

        // Compose box was already populated (e.g. via wa.me/?text=...) or textInjected == true
        // Verify compose box isn't empty or Send button is already visible
        if (clickMessengerSendButton(root)) {
            job.completed = true
            pendingAutoSend = null
        } else if (!job.textInjected && job.messageText.isNotBlank()) {
            // Fallback: if URL didn't prefill text in WhatsApp, inject it directly!
            val editNode = findEditableNode(root)
            if (editNode != null) {
                val existing = editNode.text?.toString().orEmpty().trim()
                if (existing.isEmpty()) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            job.messageText
                        )
                    }
                    if (editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        job.textInjected = true
                        mainHandler.postDelayed({
                            val freshRoot = rootInActiveWindow ?: return@postDelayed
                            if (!job.completed && clickMessengerSendButton(freshRoot)) {
                                job.completed = true
                                pendingAutoSend = null
                            }
                        }, 280L)
                    }
                }
            }
        }
    }

    data class AutoSendJob(
        val targetPackages: Set<String>,
        val messageText: String,
        val needsTextInjection: Boolean,
        val expiresAtMs: Long,
        @Volatile var textInjected: Boolean = false,
        @Volatile var completed: Boolean = false
    )

    companion object {
        private val ruLocale = Locale("ru", "RU")
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var instance: NellyAccessibilityService? = null
            private set

        @Volatile
        var currentWindowPackage: String = ""
            private set

        @Volatile
        var lastExternalAppPackage: String = ""
            private set

        @Volatile
        var lastExternalAppTimestampMs: Long = 0L
            private set

        @Volatile
        private var pendingAutoSend: AutoSendJob? = null

        @Volatile
        private var pending2GisAutoStartUntilMs: Long = 0L

        private val sendButtonViewIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/send_container",
            "org.telegram.messenger:id/chat_send_button",
            "org.telegram.messenger.web:id/chat_send_button",
            "org.thunderdog.challegram:id/btn_send",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.android.mms:id/send_button",
            "com.instagram.android:id/row_thread_composer_button_send",
            "com.vkontakte.android:id/writebar_send"
        )

        private val sendButtonLabels = listOf(
            "отправить", "отправить сообщение", "отправить смс", "send", "send message", "жіберу"
        )

        private val startRouteLabels = listOf(
            "в путь", "поехали", "продолжить", "продолжить маршрут", "начать", "начать движение",
            "старт", "вести по маршруту", "бастау", "жолға шығу", "go", "start"
        )

        private val startRouteViewIdHints = listOf(
            "lets_go", "start_navigation", "go_button", "btn_go", "start_route", "continue_route"
        )

        private val stopRouteLabels = listOf(
            "завершить", "завершить маршрут", "сбросить", "остановить", "закрыть маршрут",
            "выход", "отмена", "стоп", "аяқтау", "stop", "exit"
        )

        private val stopRouteViewIdHints = listOf(
            "finish_route", "stop_navigation", "close_route", "exit_navigation", "cancel_route"
        )

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                enabledServices.contains("${context.packageName}/${NellyAccessibilityService::class.java.name}") ||
                    enabledServices.contains("${context.packageName}/.NellyAccessibilityService")
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Returns the active foreground app package, or if the user just tapped Nelly / Speech Dialog
         * within the last 12 seconds, the app they were working in right before that!
         */
        fun getEffectiveForegroundApp(ownPackageName: String): String {
            val liveRootPkg = instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
            if (liveRootPkg.isNotEmpty() &&
                liveRootPkg != ownPackageName &&
                !isIgnoredOverlayPackage(liveRootPkg) &&
                !isLauncherPackage(liveRootPkg)
            ) {
                return liveRootPkg
            }
            if (currentWindowPackage.isNotEmpty() &&
                currentWindowPackage != ownPackageName &&
                !isIgnoredOverlayPackage(currentWindowPackage) &&
                !isLauncherPackage(currentWindowPackage)
            ) {
                return currentWindowPackage
            }
            if (lastExternalAppPackage.isNotEmpty() &&
                System.currentTimeMillis() - lastExternalAppTimestampMs < 20_000L
            ) {
                return lastExternalAppPackage
            }
            return ""
        }

        fun is2GisActiveOrRecent(ownPackageName: String): Boolean {
            val pkg = getEffectiveForegroundApp(ownPackageName)
            return pkg == "ru.dublgis.dgismobile" || pkg.contains("dublgis") || pkg.contains("2gis")
        }

        fun isNavigatorActiveOrRecent(ownPackageName: String): Boolean {
            return isNavigatorPackage(getEffectiveForegroundApp(ownPackageName))
        }

        fun isMessengerActive(ownPackageName: String): Pair<Boolean, String> {
            val liveRootPkg = instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
            val pkg = if (liveRootPkg.isNotEmpty() && liveRootPkg != ownPackageName && !isIgnoredOverlayPackage(liveRootPkg)) {
                liveRootPkg
            } else {
                getEffectiveForegroundApp(ownPackageName)
            }
            val isMsg = pkg in setOf(
                "com.whatsapp", "com.whatsapp.w4b",
                "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
                "com.instagram.android", "com.viber.voip", "com.vkontakte.android",
                "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"
            )
            return isMsg to pkg
        }

        fun getFriendlyAppName(context: Context, pkg: String): String {
            if (pkg.isEmpty()) return "главный экран"
            val knownMap = mapOf(
                "ru.dublgis.dgismobile" to "2ГИС",
                "com.whatsapp" to "WhatsApp",
                "com.whatsapp.w4b" to "WhatsApp Business",
                "org.telegram.messenger" to "Telegram",
                "com.google.android.youtube" to "YouTube",
                "com.instagram.android" to "Instagram",
                "kz.kaspi.mobile" to "Kaspi.kz",
                "com.zhiliaoapp.musically" to "TikTok",
                "com.ss.android.ugc.trill" to "TikTok",
                "ru.yandex.yandexmaps" to "Яндекс Карты",
                "ru.yandex.yandexnavi" to "Яндекс Навигатор",
                "com.google.android.apps.maps" to "Google Карты",
                "com.android.chrome" to "Chrome"
            )
            knownMap[pkg]?.let { return it }
            return try {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
        }

        fun scheduleAutoSend(
            targetPackages: Set<String>,
            messageText: String,
            needsTextInjection: Boolean
        ) {
            val job = AutoSendJob(
                targetPackages = targetPackages,
                messageText = messageText,
                needsTextInjection = needsTextInjection,
                expiresAtMs = System.currentTimeMillis() + 14_000L
            )
            pendingAutoSend = job

            // Poll periodically as well in case window events settle silently
            val delays = listOf(600L, 1200L, 2000L, 3000L, 4500L, 6500L)
            for (d in delays) {
                mainHandler.postDelayed({
                    val svc = instance ?: return@postDelayed
                    if (pendingAutoSend === job && !job.completed) {
                        svc.checkPendingJobs()
                    }
                }, d)
            }
        }

        fun scheduleAutoStart2GisNavigation() {
            val until = System.currentTimeMillis() + 12_000L
            pending2GisAutoStartUntilMs = until
            val delays = listOf(1200L, 2200L, 3500L, 5000L, 7000L)
            for (d in delays) {
                mainHandler.postDelayed({
                    val svc = instance ?: return@postDelayed
                    if (pending2GisAutoStartUntilMs == until) {
                        svc.checkPendingJobs()
                    }
                }, d)
            }
        }

        fun sendInCurrentlyOpenChat(messageText: String): Pair<Boolean, String> {
            val svc = instance ?: return false to "Служба управления экраном Нелли ещё не включена в Спецвозможностях."
            val root = svc.rootInActiveWindow ?: return false to "Не удалось прочитать открытое окно чата."
            val editNode = findEditableNode(root)
                ?: return false to "В открытом окне не найдено поле ввода сообщения. Откройте нужный чат."

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageText)
            }
            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val ok = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) {
                return false to "Не удалось вставить текст в поле чата."
            }

            mainHandler.postDelayed({
                val freshRoot = svc.rootInActiveWindow ?: return@postDelayed
                clickMessengerSendButton(freshRoot)
            }, 280L)

            return true to "Отправляю сообщение: «$messageText»."
        }

        fun perform2GisAction(action: TwoGisUiAction): Pair<Boolean, String> {
            val svc = instance ?: return false to "Чтобы я нажимала кнопки внутри открытого 2ГИС, включите «Помощница Нелли» в настройках Специальных возможностей (кнопка есть на главном экране)."
            val root = svc.rootInActiveWindow ?: return false to "Не вижу активного окна 2ГИС на экране."

            return when (action) {
                TwoGisUiAction.START_OR_CONTINUE -> {
                    // First look for "В путь" / "Поехали" / "Продолжить"
                    if (findAndClickByTargets(root, startRouteLabels, startRouteViewIdHints)) {
                        true to "Нажала «В путь» — продолжаем движение по маршруту!"
                    } else if (findAndClickByTargets(root, listOf("маршрут", "проехать", "добраться"), listOf("route"))) {
                        // If a place card is open in 2GIS, click "Маршрут" first, then auto-click "В путь" 1.2s later!
                        scheduleAutoStart2GisNavigation()
                        true to "Строю маршрут к выбранному месту в 2ГИС и запускаю ведение!"
                    } else {
                        false to "На экране 2ГИС сейчас не видно кнопки «В путь» или «Маршрут». Назовите место, куда проложить маршрут!"
                    }
                }
                TwoGisUiAction.STOP_ROUTE -> {
                    if (findAndClickByTargets(root, stopRouteLabels, stopRouteViewIdHints)) {
                        true to "Маршрут в навигаторе завершён."
                    } else {
                        svc.performGlobalAction(GLOBAL_ACTION_BACK)
                        true to "Закрываю режим маршрута."
                    }
                }
                TwoGisUiAction.ZOOM_IN -> {
                    val ok = findAndClickByTargets(root, listOf("приблизить", "увеличить", "+", "zoom in"), listOf("zoom_in", "plus"))
                    if (ok) true to "Приблизила карту." else false to "Кнопка приближения не найдена."
                }
                TwoGisUiAction.ZOOM_OUT -> {
                    val ok = findAndClickByTargets(root, listOf("отдалить", "уменьшить", "-", "zoom out"), listOf("zoom_out", "minus"))
                    if (ok) true to "Отдалила карту." else false to "Кнопка отдаления не найдена."
                }
                TwoGisUiAction.MY_LOCATION -> {
                    val ok = findAndClickByTargets(
                        root,
                        listOf("моё местоположение", "мое местоположение", "где я", "геолокация", "локация"),
                        listOf("my_location", "location_btn", "locate")
                    )
                    if (ok) true to "Центрирую карту по вашему местоположению." else false to "Кнопка геолокации на карте не найдена."
                }
            }
        }

        fun clickAnyScreenButton(targetSpokenLabel: String): Pair<Boolean, String> {
            val svc = instance ?: return false to "Включите спецвозможности Нелли на главном экране, чтобы я могла нажимать кнопки на экране."
            val root = svc.rootInActiveWindow ?: return false to "Экран сейчас недоступен."
            val cleanTarget = targetSpokenLabel.lowercase(ruLocale).replace('ё', 'е').trim()
            if (cleanTarget.isEmpty()) return false to "Скажите название кнопки, которую нужно нажать."

            val best = findBestMatchingNode(root, cleanTarget)
            if (best != null && clickNodeOrParent(best.first)) {
                return true to "Нажала «${best.second}»."
            }
            return false to "Кнопка «$targetSpokenLabel» не найдена на текущем экране."
        }

        fun swipeScreen(dir: SwipeDir): Boolean {
            val svc = instance ?: return false
            val metrics = svc.resources.displayMetrics
            val w = metrics.widthPixels.coerceAtLeast(400).toFloat()
            val h = metrics.heightPixels.coerceAtLeast(800).toFloat()

            val path = Path()
            when (dir) {
                SwipeDir.UP_NEXT -> {
                    path.moveTo(w * 0.5f, h * 0.76f)
                    path.lineTo(w * 0.5f, h * 0.22f)
                }
                SwipeDir.DOWN_PREV -> {
                    path.moveTo(w * 0.5f, h * 0.24f)
                    path.lineTo(w * 0.5f, h * 0.78f)
                }
                SwipeDir.LEFT -> {
                    path.moveTo(w * 0.82f, h * 0.5f)
                    path.lineTo(w * 0.18f, h * 0.5f)
                }
                SwipeDir.RIGHT -> {
                    path.moveTo(w * 0.18f, h * 0.5f)
                    path.lineTo(w * 0.82f, h * 0.5f)
                }
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }

        fun performSystemNavigation(action: Int): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(action)
        }

        // ------------------------------------------------------- OlympTrade UI

        val OLYMP_PACKAGES = listOf(
            "com.ticno.olymptrade",
            "com.otapp.android",
            "com.olymptrade.flex"
        )

        fun isOlympPackage(pkg: String): Boolean {
            val p = pkg.lowercase(Locale.US)
            return OLYMP_PACKAGES.any { it.equals(p, ignoreCase = true) } || p.contains("olymptrade")
        }

        fun isOlympForeground(): Boolean {
            val pkg = getEffectiveForegroundApp("")
            if (isOlympPackage(pkg)) return true
            val live = instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
            return isOlympPackage(live)
        }

        private val olympUpLabels = listOf(
            "вверх", "выше", "up", "buy", "call", "рост", "больше", "higher", "long", "купить", "вгору"
        )
        private val olympDownLabels = listOf(
            "вниз", "ниже", "down", "sell", "put", "падение", "меньше", "lower", "short", "продать", "вниз"
        )
        private val olympUpIdHints = listOf("trade_up", "btn_up", "button_up", "buy", "call", "higher", "increase")
        private val olympDownIdHints = listOf("trade_down", "btn_down", "button_down", "sell", "put", "lower", "decrease")
        private val olympExpirySelectorLabels = listOf(
            "экспирация", "время сделки", "время", "срок", "expiration", "expiry", "time", "duration"
        )
        private val olympAssetSelectorLabels = listOf(
            "выбрать актив", "сменить актив", "выбор актива", "актив", "asset", "assets", "add asset", "поиск"
        )

        fun olympSnapshot(context: Context): OlympScreenSnapshot {
            val svc = instance
            val root = svc?.rootInActiveWindow
            val pkg = root?.packageName?.toString().orEmpty()
            val foreground = isOlympPackage(pkg) || isOlympForeground()
            if (root == null) {
                return OlympScreenSnapshot(
                    packageName = pkg,
                    isForeground = foreground,
                    isDemoAccount = false,
                    isRealAccount = false,
                    detectedAsset = null,
                    visibleTexts = emptyList(),
                    tradeButtonLabels = emptyList(),
                    hasSearchField = false,
                    hasExpiry15 = false
                )
            }

            val nodes = collectAllNodes(root, 1200)
            val compactAssets = MarketAssets.ALL.map { a ->
                a to a.olymptradeNames.map { compactLabel(it) }
            }
            val texts = ArrayList<String>()
            var demo = false
            var real = false
            var hasSearch = false
            var hasExpiry15 = false
            val tradeLabels = ArrayList<String>()
            var asset: String? = null

            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                val labels = readLabels(n)
                for (raw in labels) {
                    val low = raw.lowercase(ruLocale).replace('ё', 'е').trim()
                    if (low.isEmpty()) continue
                    if (texts.size < 60 && texts.none { it.equals(raw, ignoreCase = true) }) {
                        texts.add(raw)
                    }
                    if (low == "демо" || low == "demo" || low.contains("демо-счет") ||
                        low.contains("демо счет") || low.contains("demo account") || low.contains("тренировочный")
                    ) {
                        demo = true
                    }
                    if (low == "реальный" || low == "live" || low.contains("реальный счет") ||
                        low.contains("real account") || low.contains("real money")
                    ) {
                        real = true
                    }
                    if (low.contains("поиск") || low.contains("search") || low.contains("найти актив")) {
                        hasSearch = true
                    }
                    if (isExpiry15Label(low)) hasExpiry15 = true

                    if (isTradeLabel(low, olympUpLabels) || isTradeLabel(low, olympDownLabels)) {
                        if (tradeLabels.size < 12 && tradeLabels.none { it.equals(raw, ignoreCase = true) }) {
                            tradeLabels.add(raw)
                        }
                    }

                    val assetMatch = Regex("[A-Z]{3}\\s?/\\s?[A-Z]{3}").find(raw)
                    if (assetMatch != null && asset == null) {
                        asset = assetMatch.value
                    }
                }
                if (n.isEditable && n.isVisibleToUser) hasSearch = true
                if (asset == null && labels.isNotEmpty()) {
                    val joinedCompact = compactLabel(labels.joinToString(" "))
                    if (joinedCompact.length in 4..40) {
                        for ((marketAsset, compactNames) in compactAssets) {
                            if (compactNames.any { it.length >= 4 && (joinedCompact == it || joinedCompact.contains(it)) }) {
                                asset = marketAsset.olymptradeNames.first()
                                break
                            }
                        }
                    }
                }
            }

            return OlympScreenSnapshot(
                packageName = pkg,
                isForeground = foreground,
                isDemoAccount = demo,
                isRealAccount = real,
                detectedAsset = asset,
                visibleTexts = texts,
                tradeButtonLabels = tradeLabels,
                hasSearchField = hasSearch,
                hasExpiry15 = hasExpiry15
            )
        }

        fun olympPressTrade(direction: TradeDirection): Pair<Boolean, String> {
            val svc = instance ?: return false to
                "Включите спецвозможности Нелли, чтобы я могла нажимать «Вверх» и «Вниз» в OlympTrade."
            val root = svc.rootInActiveWindow ?: return false to "Экран OlympTrade сейчас недоступен."
            val pkg = root.packageName?.toString().orEmpty()
            if (!isOlympPackage(pkg)) {
                return false to "OlympTrade сейчас не открыт на экране."
            }

            val labels = if (direction == TradeDirection.DOWN) olympDownLabels else olympUpLabels
            val ids = if (direction == TradeDirection.DOWN) olympDownIdHints else olympUpIdHints
            val found = findOlympTarget(root, labels, ids)
            if (found == null) {
                val visible = collectAllNodes(root, 400)
                    .filter { it.isVisibleToUser }
                    .mapNotNull { readLabels(it).firstOrNull() }
                    .filter { it.length in 2..24 }
                    .distinct()
                    .take(10)
                val hint = if (visible.isEmpty()) "" else " Видимые надписи: ${visible.joinToString(", ")}."
                return false to "Не нашла кнопку «${if (direction == TradeDirection.DOWN) "Вниз" else "Вверх"}» в OlympTrade.$hint"
            }
            val ok = clickNodeOrParent(found.first)
            val word = if (direction == TradeDirection.DOWN) "ВНИЗ" else "ВВЕРХ"
            return if (ok) {
                true to "Нажала в OlympTrade кнопку «$word» (${found.second})."
            } else {
                false to "Нашла кнопку «$word», но нажать не удалось — попробуйте ещё раз."
            }
        }

        fun olympSetExpiry(minutes: Int = 15): Pair<Boolean, String> {
            val svc = instance ?: return false to "Включите спецвозможности Нелли для управления экспирацией."
            val root = svc.rootInActiveWindow ?: return false to "Экран OlympTrade сейчас недоступен."

            // 1. Прямой поиск чипа «15 мин»
            val direct = findOlympTarget(root, expiryLabelsFor(minutes), listOf("expiry", "expiration", "duration"))
            if (direct != null && clickNodeOrParent(direct.first)) {
                return true to "Экспирация выставлена на $minutes минут."
            }

            // 2. Сначала открыть селектор времени, затем (со следующего шага) выбрать 15 минут
            val selector = findOlympTarget(root, olympExpirySelectorLabels, listOf("expiry", "expiration", "duration", "time"))
            if (selector != null && clickNodeOrParent(selector.first)) {
                return false to "Открываю выбор времени сделки."
            }
            return false to "Не вижу переключатель времени сделки в OlympTrade."
        }

        fun olympOpenAssetSelector(): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val pkg = root.packageName?.toString().orEmpty()
            if (!isOlympPackage(pkg)) return false

            val nodes = collectAllNodes(root, 500)
            // Заголовок с текущим активом (EUR/USD) — обычно кликабельный
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                val labels = readLabels(n)
                if (labels.any { Regex("[A-Z]{3}\\s?/\\s?[A-Z]{3}").containsMatchIn(it) }) {
                    if (clickNodeOrParent(n)) return true
                }
            }
            val selector = findOlympTarget(root, olympAssetSelectorLabels, listOf("asset", "symbol", "instrument"))
            return selector != null && clickNodeOrParent(selector.first)
        }

        fun olympTypeAssetQuery(query: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val nodes = collectAllNodes(root, 700)
            val editable = nodes.firstOrNull { it.isVisibleToUser && it.isEditable }
                ?: nodes.firstOrNull {
                    it.isVisibleToUser && it.className?.toString()?.contains("EditText", true) == true
                }
                ?: return false
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun olympClickAssetResult(asset: MarketAsset): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            val nodes = collectAllNodes(root, 800)
            val targets = asset.olymptradeNames.map { compactLabel(it) }.filter { it.length >= 4 }
            for (n in nodes) {
                if (!n.isVisibleToUser || n.isEditable) continue
                val labels = readLabels(n)
                for (raw in labels) {
                    val compact = compactLabel(raw)
                    val hit = targets.any { t -> compact == t || compact.startsWith(t) }
                    if (hit) {
                        if (clickNodeOrParent(n)) return true
                    }
                }
            }
            return false
        }

        fun olympClosePopups(): Int {
            val svc = instance ?: return 0
            val root = svc.rootInActiveWindow ?: return 0
            var closed = 0
            val labels = listOf("закрыть", "позже", "не сейчас", "отмена", "close", "later", "not now", "cancel", "×", "х")
            repeat(3) {
                val target = findOlympTarget(root, labels, listOf("close", "dismiss", "cancel"))
                if (target != null && clickNodeOrParent(target.first)) closed++ else return closed
            }
            return closed
        }

        private fun expiryLabelsFor(minutes: Int): List<String> = listOf(
            "$minutes мин", "$minutes минут", "${minutes}m", "$minutes m", "$minutes min", "${minutes}м", "$minutes"
        )

        private fun isExpiry15Label(low: String): Boolean {
            val compact = low.replace(" ", "")
            return compact == "15мин" || compact == "15минут" || compact == "15m" ||
                compact == "15min" || compact == "15м" || compact == "15"
        }

        private fun isTradeLabel(low: String, targets: List<String>): Boolean =
            targets.any { t -> low == t }

        private fun compactLabel(s: String): String =
            s.lowercase(ruLocale).replace('ё', 'е').replace(Regex("[^a-zа-я0-9]"), "")

        private fun readLabels(n: AccessibilityNodeInfo): List<String> {
            val out = ArrayList<String>(3)
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }?.let { out.add(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }?.let { out.add(it) }
            }
            return out
        }

        private fun collectAllNodes(root: AccessibilityNodeInfo, limit: Int): List<AccessibilityNodeInfo> {
            val out = ArrayList<AccessibilityNodeInfo>(minOf(limit, 256))
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)
            var guard = 0
            while (queue.isNotEmpty() && guard < limit) {
                guard++
                val n = queue.removeFirst()
                out.add(n)
                for (i in 0 until n.childCount) {
                    try {
                        n.getChild(i)?.let { queue.addLast(it) }
                    } catch (_: Exception) {
                    }
                }
            }
            return out
        }

        private fun findOlympTarget(
            root: AccessibilityNodeInfo,
            exactLabels: List<String>,
            idHints: List<String>
        ): Pair<AccessibilityNodeInfo, String>? {
            val nodes = collectAllNodes(root, 900)
            var best: AccessibilityNodeInfo? = null
            var bestLabel = ""
            var bestScore = 0.0

            for (n in nodes) {
                if (!n.isVisibleToUser || n.isEditable) continue
                val viewId = n.viewIdResourceName?.lowercase(Locale.US).orEmpty()
                val labels = readLabels(n)
                var localScore = 0.0
                var localLabel = ""

                for (raw in labels) {
                    val low = raw.lowercase(ruLocale).replace('ё', 'е').trim()
                    val compact = compactLabel(low)
                    val score = when {
                        exactLabels.any { it == low } -> 100.0
                        exactLabels.any { it.length >= 2 && compact == compactLabel(it) } -> 96.0
                        exactLabels.any { it.length >= 3 && compact.startsWith(compactLabel(it)) } -> 82.0
                        exactLabels.any { it.length >= 3 && compact.contains(compactLabel(it)) } -> 70.0
                        else -> 0.0
                    }
                    if (score > localScore) {
                        localScore = score
                        localLabel = raw
                    }
                }

                if (viewId.isNotEmpty()) {
                    for (hint in idHints) {
                        if (viewId.contains(hint)) {
                            val idScore = 55.0
                            if (idScore > localScore) {
                                localScore = idScore
                                localLabel = localLabel.ifEmpty { hint }
                            }
                        }
                    }
                }

                if (localScore > bestScore) {
                    bestScore = localScore
                    best = n
                    bestLabel = localLabel
                }
            }

            return if (best != null && bestScore >= 55.0) best to bestLabel else null
        }

        fun describeCurrentScreen(context: Context): String {
            val svc = instance
            if (svc == null) {
                return "Чтобы я видела, какое приложение открыто, включите «Помощница Нелли» в Специальных возможностях кнопкой на главном экране."
            }
            val pkg = getEffectiveForegroundApp(context.packageName)
            val appName = getFriendlyAppName(context, pkg)
            val root = svc.rootInActiveWindow
            if (root == null) {
                return "Сейчас открыто: $appName."
            }
            val texts = mutableListOf<String>()
            collectVisibleTexts(root, texts, limit = 5)
            return if (texts.isEmpty()) {
                "Сейчас открыто приложение $appName."
            } else {
                "Сейчас открыто приложение $appName. На экране: ${texts.joinToString(", ")}."
            }
        }

        private fun collectVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, limit: Int) {
            if (node == null || out.size >= limit) return
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.length in 2..45 && out.none { it.equals(t, ignoreCase = true) }) {
                out.add(t)
            }
            for (i in 0 until node.childCount) {
                if (out.size >= limit) break
                collectVisibleTexts(node.getChild(i), out, limit)
            }
        }

        private fun clickMessengerSendButton(root: AccessibilityNodeInfo): Boolean {
            // 1. Exact known View IDs
            for (id in sendButtonViewIds) {
                val nodes = try {
                    root.findAccessibilityNodeInfosByViewId(id)
                } catch (_: Exception) {
                    null
                }
                if (!nodes.isNullOrEmpty()) {
                    for (n in nodes) {
                        if (n.isVisibleToUser && clickNodeOrParent(n)) return true
                    }
                }
            }

            // 2. ContentDescription / Text match ("Отправить", "Send", "Жіберу")
            return findAndClickByTargets(root, sendButtonLabels, listOf("send", "btn_send"))
        }

        private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.isVisibleToUser) return node
            if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true && node.isVisibleToUser) {
                return node
            }
            for (i in 0 until node.childCount) {
                val found = findEditableNode(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        private fun findAndClickByTargets(
            root: AccessibilityNodeInfo,
            labelsLower: List<String>,
            viewIdSubstrings: List<String>
        ): Boolean {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)

            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (n.isVisibleToUser) {
                    val txt = n.text?.toString()?.lowercase(ruLocale)?.replace('ё', 'е')?.trim().orEmpty()
                    val desc = n.contentDescription?.toString()?.lowercase(ruLocale)?.replace('ё', 'е')?.trim().orEmpty()
                    val viewId = n.viewIdResourceName?.lowercase(Locale.US).orEmpty()

                    val labelHit = labelsLower.any { target ->
                        txt == target || desc == target ||
                            (target.length >= 4 && (txt.contains(target) || desc.contains(target)))
                    }
                    val idHit = viewId.isNotEmpty() && viewIdSubstrings.any { sub -> viewId.contains(sub) }

                    if (labelHit || idHit) {
                        if (clickNodeOrParent(n)) return true
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
            }
            return false
        }

        private fun findBestMatchingNode(
            root: AccessibilityNodeInfo,
            targetLower: String
        ): Pair<AccessibilityNodeInfo, String>? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)

            var bestNode: AccessibilityNodeInfo? = null
            var bestLabel = ""
            var bestScore = 0.0

            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (n.isVisibleToUser && !n.isEditable) {
                    val candidates = listOfNotNull(
                        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 45 },
                        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 45 }
                    )
                    for (cand in candidates) {
                        val candLower = cand.lowercase(ruLocale).replace('ё', 'е')
                        val score = when {
                            candLower == targetLower -> 1.0
                            candLower.contains(targetLower) && targetLower.length >= 3 -> 0.93
                            else -> PhoneticSimilarity.wordSimilarity(targetLower, candLower)
                        }
                        if (score > bestScore) {
                            bestScore = score
                            bestNode = n
                            bestLabel = cand
                        }
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.addLast(it) }
                }
            }

            return if (bestNode != null && bestScore >= 0.72) (bestNode to bestLabel) else null
        }

        private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
            var cur = node
            var depth = 0
            while (cur != null && depth < 5) {
                if (cur.isClickable && cur.isEnabled) {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                cur = cur.parent
                depth++
            }
            return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        private fun isNavigatorPackage(pkg: String): Boolean {
            return pkg == "ru.dublgis.dgismobile" ||
                pkg.contains("dublgis") ||
                pkg.contains("2gis") ||
                pkg == "ru.yandex.yandexmaps" ||
                pkg == "ru.yandex.yandexnavi" ||
                pkg == "com.google.android.apps.maps"
        }

        private fun isIgnoredOverlayPackage(pkg: String): Boolean {
            val p = pkg.lowercase(Locale.US)
            return p == "com.android.systemui" ||
                p == "com.google.android.tts" ||
                p.contains("inputmethod") ||
                p.contains("keyboard") ||
                p.contains("honeyboard") ||
                p.contains("latinime") ||
                p.contains("permissioncontroller")
        }

        private fun isLauncherPackage(pkg: String): Boolean {
            val p = pkg.lowercase(Locale.US)
            return p.contains("launcher") || p.contains("trebuchet") || p == "com.sec.android.app.launcher" || p == "com.miui.home"
        }
    }
}
