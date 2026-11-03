package com.missingtable.scorer.domain

/**
 * What a live-score push carries and where tapping it goes (SB-1464).
 *
 * Pure, because the same payload reaches the app two ways: tapped from the
 * system tray while the app was killed or backgrounded (FCM hands the data
 * map to the launcher activity as String extras), or received in the
 * foreground, where we post the notification ourselves. Both must land on the
 * same match.
 */
object NotificationIntent {

    /** Must match the server's android.notification.channel_id. */
    const val CHANNEL_ID = "live_scores"
    const val CHANNEL_NAME = "Live scores"

    const val EXTRA_MATCH_ID = "matchId"
    const val EXTRA_EVENT_TYPE = "eventType"
    const val EXTRA_URL = "url"

    /**
     * The match a notification points at: the `matchId` extra, else the
     * `matchId` query parameter of `url` (the web deep link, "/?matchId=123").
     */
    fun matchIdFrom(extras: Map<String, String?>): Int? =
        positiveInt(extras[EXTRA_MATCH_ID]) ?: extras[EXTRA_URL]?.let(::matchIdFromUrl)

    fun matchIdFromUrl(url: String): Int? {
        val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            .firstOrNull { (k, _) -> k == EXTRA_MATCH_ID }
            ?.let { (_, v) -> positiveInt(v) }
    }

    /**
     * Same shape as the server's tag, for when a payload arrives without one:
     * a later event of the same kind replaces the earlier rather than stacking.
     */
    fun tagFor(matchId: Int, eventType: String?): String = "match-$matchId-${eventType ?: "update"}"

    /**
     * The tag a foreground notification is posted under: the server's, else
     * [tagFor] when the match is known, else the message id, else the title.
     */
    fun tagOrFallback(
        serverTag: String?,
        matchId: Int?,
        eventType: String?,
        messageId: String?,
        title: String,
    ): String = serverTag
        ?: matchId?.let { tagFor(it, eventType) }
        ?: messageId
        ?: title

    /** PendingIntent request code: one per tag, so a replaced notification reuses its slot. */
    fun requestCodeFor(tag: String): Int = tag.hashCode()

    private fun positiveInt(s: String?): Int? = s?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /** One notification-preference toggle. [key] is the server's. */
    data class Pref(val key: String, val label: String, val default: Boolean)

    /** In display order; defaults mirror the server's for a user with no row. */
    val PREFS = listOf(
        Pref("kickoff", "Kickoff", true),
        Pref("goal", "Goals", true),
        Pref("halftime", "Halftime", true),
        Pref("fulltime", "Full time", true),
        Pref("yellow_card", "Yellow cards", false),
        Pref("red_card", "Red cards", false),
    )

    /** Fill keys the server left out with their defaults; keep only known keys. */
    fun withDefaults(server: Map<String, Boolean>): Map<String, Boolean> =
        PREFS.associate { it.key to (server[it.key] ?: it.default) }
}
