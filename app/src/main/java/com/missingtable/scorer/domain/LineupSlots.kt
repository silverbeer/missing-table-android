package com.missingtable.scorer.domain

/**
 * The lineup builder works in slot indexes (one per marker on the pitch); the
 * backend stores position codes. These convert between the two, so loading a
 * saved lineup, a queued one (SB-1504) and a copied one all go one way.
 */
object LineupSlots {

    /** A saved or queued lineup in builder terms. */
    data class Loaded(val formation: String, val assignments: Map<Int, Int>)

    /**
     * Map saved (position code, player id) pairs onto [formation]'s slots.
     * Null when this app has no preset for the formation (saved on the web).
     */
    fun load(formation: String, positions: List<Pair<String, Int>>): Loaded? {
        val preset = Formations.presets[formation] ?: return null
        val byPosition = positions.toMap()
        val assignments = preset.withIndex().mapNotNull { (idx, slot) ->
            byPosition[slot.code]?.let { idx to it }
        }.toMap()
        return Loaded(formation, assignments)
    }

    /** Builder assignments back to (position code, player id), in slot order. */
    fun positions(formation: String, assignments: Map<Int, Int>): List<Pair<String, Int>> {
        val preset = Formations.presets[formation] ?: return emptyList()
        return assignments.entries.sortedBy { it.key }.mapNotNull { (idx, id) ->
            preset.getOrNull(idx)?.let { it.code to id }
        }
    }

    /**
     * Who is on the pitch now (SB-1504): the starting XI as the builder holds
     * it — the freshest copy, even mid-edit — with the match's subs and moves
     * replayed. Placeholder numbers (negative ids) have no roster row yet, so
     * no sub can name them; they're left out until they save.
     */
    fun currentPitch(
        formation: String,
        assignments: Map<Int, Int>,
        changes: List<OnPitch.Change>,
    ): Map<String, Int> {
        val starting = positions(formation, assignments.filterValues { it > 0 }).toMap()
        return OnPitch.current(starting, changes)
    }
}
