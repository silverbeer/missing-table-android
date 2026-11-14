package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.SeasonDto
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Seasons {
    /**
     * Same rule as the web app (`data.find(s => s.is_current) || seasons[0]`
     * with seasons sorted newest-first): the admin-set is_current season wins,
     * else the newest by start_date.
     */
    fun pickCurrent(seasons: List<SeasonDto>): SeasonDto? =
        seasons.firstOrNull { it.isCurrent }
            ?: seasons.maxByOrNull { it.startDate ?: "" }

    /**
     * The week offset (for [MatchWeek.of]) to open [season] on (SB-1680).
     *
     * A season running today opens on this week; a finished one on its last
     * week, so picking last season shows its final fixtures rather than an
     * empty present; a future one on its first week. A season with no
     * end_date ends the day before the next one starts.
     */
    fun weekOffsetFor(season: SeasonDto, seasons: List<SeasonDto>, today: LocalDate): Int {
        val start = season.startDate?.let(::parse)
        val end = season.endDate?.let(::parse)
            ?: seasons
                .mapNotNull { it.startDate?.let(::parse) }
                .filter { start != null && it > start }
                .minOrNull()
                ?.minusDays(1)
        val target = when {
            end != null && end < today -> end
            start != null && start > today -> start
            else -> return 0
        }
        val thisMonday = MatchWeek.of(today).start
        val targetMonday = MatchWeek.of(target).start
        return ChronoUnit.WEEKS.between(thisMonday, targetMonday).toInt()
    }

    private fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date.take(10)) }.getOrNull()
}
