package com.missingtable.scorer.domain

/**
 * Which clock action the match is waiting for (SB-653).
 *
 * Every clock control used to live behind the overflow menu, offered all at
 * once regardless of state — so "Halftime" sat next to "Back to 1st half" and
 * nothing indicated which was valid. At a pitch, with the whistle going, the
 * next action needs to be the obvious one.
 *
 * Derived purely from the clock timestamps, the same inputs [LiveClock] uses,
 * so the button and the clock can never disagree.
 */
enum class ClockStage(val label: String) {
    /** Kickoff hasn't happened. */
    NOT_STARTED("START MATCH"),

    /** First half running. */
    FIRST_HALF("HALFTIME"),

    /** At the interval. */
    HALFTIME("START 2ND HALF"),

    /** Second half running. */
    SECOND_HALF("END MATCH"),

    /** Full time — no forward action; reopening is a correction, not a step. */
    ENDED("");

    /** Full time has no primary button. */
    val hasPrimaryAction: Boolean get() = this != ENDED
}

object MatchClock {

    /**
     * The stage a match is in, from its clock timestamps.
     *
     * Checked most-final first: an ended match is ended whatever else is set,
     * and a running second half outranks the halftime that preceded it.
     */
    fun stage(
        kickoffTime: String?,
        halftimeStart: String?,
        secondHalfStart: String?,
        matchEndTime: String?,
    ): ClockStage = when {
        matchEndTime != null -> ClockStage.ENDED
        secondHalfStart != null -> ClockStage.SECOND_HALF
        halftimeStart != null -> ClockStage.HALFTIME
        kickoffTime != null -> ClockStage.FIRST_HALF
        else -> ClockStage.NOT_STARTED
    }

    /**
     * The clock action a stage's primary button performs, as the backend names
     * it. Null when there is nothing to offer.
     *
     * NOT_STARTED is absent on purpose: starting a match needs the half-length
     * dialog (SB-645), so it opens that rather than posting an action directly.
     */
    fun primaryAction(stage: ClockStage): String? = when (stage) {
        ClockStage.NOT_STARTED -> null
        ClockStage.FIRST_HALF -> "start_halftime"
        ClockStage.HALFTIME -> "start_second_half"
        ClockStage.SECOND_HALF -> "end_match"
        ClockStage.ENDED -> null
    }

    /**
     * The ways back from a stage, nearest first (SB-1450). A clock tapped on
     * by mistake must always have an obvious undo: #10106 went to half time
     * 14' in and to the second half 34s later, and nothing could reverse it.
     */
    fun corrections(stage: ClockStage): List<ClockCorrection> = when (stage) {
        ClockStage.HALFTIME -> listOf(ClockCorrection.BACK_TO_FIRST_HALF)
        ClockStage.SECOND_HALF -> listOf(ClockCorrection.BACK_TO_HALF_TIME, ClockCorrection.BACK_TO_FIRST_HALF)
        else -> emptyList()
    }

    /** The correction that undoes a clock action just taken, if it has one. */
    fun undoOf(action: String): ClockCorrection? = when (action) {
        "start_halftime" -> ClockCorrection.BACK_TO_FIRST_HALF
        "start_second_half" -> ClockCorrection.BACK_TO_HALF_TIME
        else -> null
    }
}

/**
 * Stepping the clock back (SB-1449/SB-1450). The backend clears the undone
 * timestamps and re-times events logged on them.
 */
enum class ClockCorrection(val label: String, val action: String) {
    /** Clears half time and any second half: the clock reads time since kickoff. */
    BACK_TO_FIRST_HALF("Back to 1st half", "cancel_halftime"),

    /** Clears the second half: the half-time countdown resumes. */
    BACK_TO_HALF_TIME("Back to half time", "cancel_second_half"),
}
