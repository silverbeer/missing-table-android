package com.missingtable.scorer.domain

import java.util.Locale

/**
 * Per-match standings cells (SB-1623). MLS NEXT ranks by points per match,
 * so the table shows PPM, GD/M, GF/M and GA/M next to the totals.
 *
 * The backend sends each as a number rounded to 2 decimals, or null when the
 * team has not played (and older backends omit them entirely). Missing is
 * never shown as zero: null renders as an en dash.
 */
object StandingsFormat {

    const val MISSING = "–"

    /** Exactly two decimals, always with a '.' separator. Null → [MISSING]. */
    fun perMatch(value: Double?): String {
        if (value == null || value.isNaN()) return MISSING
        return normalizeZero(String.format(Locale.US, "%.2f", value))
    }

    /**
     * Goal difference per match: like [perMatch], plus an explicit '+' on a
     * positive value, matching how the GD column shows "+3". Zero is "0.00".
     */
    fun signedPerMatch(value: Double?): String {
        val text = perMatch(value)
        if (text == MISSING) return text
        return if (text.first() != '-' && text != "0.00") "+$text" else text
    }

    /**
     * The +/- column (SB-1651): movement in the table since last week, as the
     * web shows it. Positive moved up.
     */
    fun movement(change: Int): String = when {
        change > 0 -> "▲ $change"
        change < 0 -> "▼ ${-change}"
        else -> "—"
    }

    /** Whole-number goal difference: "+3", "0", "-2". */
    fun goalDifference(value: Int): String = if (value > 0) "+$value" else "$value"

    // -0.0 (or a tiny negative that rounds away) would otherwise read "-0.00".
    private fun normalizeZero(text: String): String = if (text == "-0.00") "0.00" else text
}
