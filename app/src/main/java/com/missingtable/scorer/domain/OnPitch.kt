package com.missingtable.scorer.domain

/**
 * Who is on the pitch right now, by position (SB-1228).
 *
 * The saved lineup is the starting XI and is never rewritten mid-match;
 * substitutions are events. The current XI is the starting XI with each
 * substitution applied oldest-first, the incoming player taking the slot of
 * the player they replaced. Rolling subs (off, then back on later) fall out
 * of the same rule.
 */
object OnPitch {

    data class Sub(val outId: Int?, val inId: Int?)

    /** One staged swap in sub mode: [inId] comes on for [outId] at [position]. */
    data class Swap(val position: String, val outId: Int, val inId: Int)

    /**
     * Apply [subs] (oldest first) to [starting] (position code -> player id).
     * A sub whose outgoing player isn't on the pitch (bad data, or a sub
     * recorded before the lineup was) is ignored rather than guessed at.
     */
    fun current(starting: Map<String, Int>, subs: List<Sub>): Map<String, Int> {
        val slots = starting.toMutableMap()
        subs.forEach { sub ->
            val out = sub.outId ?: return@forEach
            val inn = sub.inId ?: return@forEach
            val position = slots.entries.firstOrNull { it.value == out }?.key ?: return@forEach
            slots[position] = inn
        }
        return slots
    }

    /**
     * Stage [inId] at [position] on top of the already-staged [swaps].
     * Re-picking a staged slot replaces its incoming player; picking the
     * player who was there originally cancels the swap.
     */
    fun stage(swaps: List<Swap>, pitch: Map<String, Int>, position: String, inId: Int): List<Swap> {
        val existing = swaps.firstOrNull { it.position == position }
        val outId = existing?.outId ?: pitch[position] ?: return swaps
        val rest = swaps.filterNot { it.position == position }
        return if (inId == outId) rest else rest + Swap(position, outId, inId)
    }

    /** [pitch] with the staged [swaps] applied — what sub mode draws. */
    fun withSwaps(pitch: Map<String, Int>, swaps: List<Swap>): Map<String, Int> =
        pitch + swaps.associate { it.position to it.inId }
}
