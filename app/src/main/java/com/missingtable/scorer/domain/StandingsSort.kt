package com.missingtable.scorer.domain

/**
 * Tap-to-sort for the Table's stat columns (SB-1651), as the web does it
 * (SB-427): a first tap sorts descending ("who has the most"), a second
 * ascending, a third goes back to the standings.
 *
 * Sorting never renumbers the table: each row keeps its league position, so
 * `#` still says where a team sits. Missing values (a per-match rate for a
 * team that has not played) sink to the bottom either way, and ties keep
 * league order so equal rows never shuffle.
 */
object StandingsSort {

    data class Sort(val key: String, val descending: Boolean = true)

    /** The sort after tapping [key]'s header. Null = standings order. */
    fun next(current: Sort?, key: String): Sort? = when {
        current?.key != key -> Sort(key, descending = true)
        current.descending -> Sort(key, descending = false)
        else -> null
    }

    /**
     * [rows] in standings order, re-ordered by [sort]. Each item carries its
     * league position (1-based) as `index + 1`.
     */
    fun <T> apply(rows: List<T>, sort: Sort?, value: (T) -> Double?): List<IndexedValue<T>> {
        val ranked = rows.withIndex().toList()
        if (sort == null) return ranked
        val sign = if (sort.descending) -1 else 1
        return ranked.sortedWith { a, b ->
            val av = value(a.value)?.takeUnless { it.isNaN() }
            val bv = value(b.value)?.takeUnless { it.isNaN() }
            when {
                (av == null) != (bv == null) -> if (av == null) 1 else -1
                av == null || bv == null || av == bv -> a.index - b.index
                else -> sign * av.compareTo(bv)
            }
        }
    }
}
