package com.missingtable.scorer.domain

/**
 * What the Match Detail screen offers for a match (SB-1455).
 *
 * A tap on a match used to drop straight into scoring — lineup, live or
 * post-match editor — or, for a fan, into an empty read-only live view. Now
 * every tap lands on the detail screen, and scoring is a button there, shown
 * only when the backend says this viewer may manage this match (`can_manage`,
 * SB-1454). Guessing from the role sent a club manager into another club's
 * match, where the server then refused every action.
 */
object MatchDetail {

    enum class Action(val label: String) {
        /** Lineup first: kickoff lives on the lineup screen (SB-645). */
        LINEUP_AND_KICKOFF("Lineup & start scoring"),
        SCORE_LIVE("Score live"),
        WATCH_LIVE("Watch live"),
        EDIT_RESULT("Edit result"),
    }

    /** Before kickoff: the match the preview is about. */
    fun isUpcoming(status: String?): Boolean = status == null || status == "scheduled" || status == "tbd"

    /**
     * Recent Form and Common Opponents describe the build-up to a fixture;
     * once it has kicked off only Head-to-Head still says something.
     */
    fun previewHeadToHeadOnly(status: String?): Boolean = !isUpcoming(status)

    /** The buttons for this viewer, primary first. */
    fun actions(status: String?, canManage: Boolean): List<Action> = when {
        isUpcoming(status) -> if (canManage) listOf(Action.LINEUP_AND_KICKOFF) else emptyList()
        status == "live" -> if (canManage) listOf(Action.SCORE_LIVE) else listOf(Action.WATCH_LIVE)
        status == "completed" -> if (canManage) listOf(Action.EDIT_RESULT) else emptyList()
        // Postponed, cancelled, forfeit: nothing to score or watch.
        else -> emptyList()
    }

    /** Events worth listing on the detail screen: the ones that change a game. */
    private val TIMELINE_TYPES = setOf("goal", "red_card", "yellow_card")

    fun isTimelineEvent(eventType: String): Boolean = eventType in TIMELINE_TYPES
}
