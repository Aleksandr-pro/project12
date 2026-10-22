package com.example.project12

enum class PendingSlot {
    TIMER_DURATION,
    ALARM_TIME,
    NOTE_TEXT,
    SHOPPING_ITEM,
    TASK_ITEM,
    SEARCH_QUERY,
    TRANSLATE_PHRASE,
    OPEN_APP_NAME,
    CALL_TARGET,
    ROUTE_DESTINATION,
    MESSAGE_BODY
}

data class RiddleItem(
    val question: String,
    val canonicalAnswer: String,
    val acceptedSubstrings: List<String>,
    val hint: String
)

object ConversationContext {

    private const val SLOT_TIMEOUT_MS = 45_000L

    @Volatile
    var lastAction: String = ""

    @Volatile
    var lastSpokenReply: String = ""

    @Volatile
    var lastMathValue: Double? = null

    @Volatile
    var lastWeatherCity: String? = null

    @Volatile
    var flashlightOn: Boolean = false

    @Volatile
    var stopwatchStartElapsedMs: Long? = null

    @Volatile
    var lastRouteDestLat: Double? = null

    @Volatile
    var lastRouteDestLon: Double? = null

    @Volatile
    var lastRouteDestName: String? = null

    @Volatile
    var lastRouteTransport: String = "car"

    @Volatile
    private var pendingSlotInternal: PendingSlot? = null

    @Volatile
    private var pendingSlotExtra: String = ""

    @Volatile
    private var pendingSlotSetAt: Long = 0L

    @Volatile
    var activeRiddle: RiddleItem? = null

    @Volatile
    var citiesGameActive: Boolean = false

    val usedCities: MutableSet<String> = LinkedHashSet()

    @Volatile
    var expectedCityLetter: Char? = null

    @Volatile
    var lastJokeIndex: Int = -1

    @Volatile
    var lastFactIndex: Int = -1

    private val recentTurns = ArrayDeque<Pair<String, String>>()

    fun setPendingSlot(slot: PendingSlot, extra: String = "") {
        pendingSlotInternal = slot
        pendingSlotExtra = extra
        pendingSlotSetAt = System.currentTimeMillis()
    }

    fun consumePendingSlot(): Pair<PendingSlot, String>? {
        val s = pendingSlotInternal ?: return null
        if (System.currentTimeMillis() - pendingSlotSetAt > SLOT_TIMEOUT_MS) {
            pendingSlotInternal = null
            pendingSlotExtra = ""
            return null
        }
        val extra = pendingSlotExtra
        pendingSlotInternal = null
        pendingSlotExtra = ""
        return s to extra
    }

    fun peekPendingSlot(): PendingSlot? {
        val s = pendingSlotInternal ?: return null
        if (System.currentTimeMillis() - pendingSlotSetAt > SLOT_TIMEOUT_MS) {
            pendingSlotInternal = null
            pendingSlotExtra = ""
            return null
        }
        return s
    }

    fun clearPendingSlot() {
        pendingSlotInternal = null
        pendingSlotExtra = ""
    }

    fun recordTurn(userText: String, assistantReply: String) {
        if (assistantReply.isNotBlank()) {
            lastSpokenReply = assistantReply
        }
        synchronized(recentTurns) {
            recentTurns.addLast(userText to assistantReply)
            while (recentTurns.size > 4) {
                recentTurns.removeFirst()
            }
        }
    }

    fun getRecentContextSummary(): String {
        synchronized(recentTurns) {
            if (recentTurns.isEmpty()) return ""
            return recentTurns.joinToString("\n") { (u, a) ->
                "Пользователь: $u\nНелли: $a"
            }
        }
    }

    fun resetCitiesGame() {
        citiesGameActive = false
        usedCities.clear()
        expectedCityLetter = null
    }
}
