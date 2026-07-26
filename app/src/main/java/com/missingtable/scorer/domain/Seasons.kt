package com.missingtable.scorer.domain

import com.missingtable.scorer.data.api.SeasonDto

object Seasons {
    /**
     * Same rule as the web app (`data.find(s => s.is_current) || seasons[0]`
     * with seasons sorted newest-first): the admin-set is_current season wins,
     * else the newest by start_date.
     */
    fun pickCurrent(seasons: List<SeasonDto>): SeasonDto? =
        seasons.firstOrNull { it.isCurrent }
            ?: seasons.maxByOrNull { it.startDate ?: "" }
}
