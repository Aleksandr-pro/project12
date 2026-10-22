package com.example.project12

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
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
