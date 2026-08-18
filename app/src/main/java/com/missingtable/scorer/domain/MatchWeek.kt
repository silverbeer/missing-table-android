package com.missingtable.scorer.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Monday–Sunday week windows for the Matches tab (SB-681).
 *
 * Mirrors the web's getWeekBoundaries(offset) in MatchesView.vue so both apps
 * agree on what "this week" means — a scorer checking the phone and the laptop
 * must not see different fixtures.
 */
object MatchWeek {

    private val header = DateTimeFormatter.ofPattern("MMM d")
    private val headerWithYear = DateTimeFormatter.ofPattern("MMM d, yyyy")

    data class Window(val start: LocalDate, val end: LocalDate) {
        operator fun contains(date: String): Boolean =
            date >= start.toString() && date <= end.toString()

        /** "Aug 17 – Aug 23, 2026" */
        fun label(): String = "${start.format(header)} – ${end.format(headerWithYear)}"
    }

    /**
     * @param offset 0 = the week containing [today], -1 = previous, +1 = next.
     */
    fun of(today: LocalDate, offset: Int = 0): Window {
        // Monday = 1 … Sunday = 7, so Sunday must step back 6 days, not 0.
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).plusWeeks(offset.toLong())
        return Window(monday, monday.plusDays(6))
    }
}
