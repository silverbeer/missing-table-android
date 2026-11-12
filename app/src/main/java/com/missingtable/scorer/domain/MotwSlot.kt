package com.missingtable.scorer.domain

/**
 * Which Match of the Week the Matches tab asks for (SB-1641).
 *
 * A pick belongs to a slot — (week, pool, age group). The age group comes
 * straight from the age-group chip; the pool is `pathway` only when the
 * conference filter is narrowed to the Pro Player Pathway division and
 * nothing else. A mixed selection still reads as the main pool, which is what
 * the server assumes when no pool is named.
 */
object MotwSlot {

    const val PATHWAY_DIVISION = "Northeast (Pro Player Pathway)"

    /** `pathway`, or null to leave the server's default (`main`). */
    fun pool(selectedConferenceNames: Collection<String>): String? =
        "pathway".takeIf {
            selectedConferenceNames.isNotEmpty() &&
                selectedConferenceNames.all { it == PATHWAY_DIVISION }
        }
}
