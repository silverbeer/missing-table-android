package com.missingtable.scorer.domain

/**
 * Who is on the pitch right now, by position (SB-1228).
 *
 * The saved lineup is the starting XI and is never rewritten mid-match;
 * substitutions and position changes are events. The current XI is the
 * starting XI with each change applied oldest-first: an incoming player takes
 * the slot of the player they replaced, and a player moving into an occupied
 * slot swaps with whoever is there (SB-1352). Rolling subs (off, then back on
 * later) fall out of the same rule.
 */
object OnPitch {

    sealed interface Change

    data class Sub(val outId: Int?, val inId: Int?) : Change

    /** [playerId] moves to [position], swapping with its occupant (SB-1352). */
    data class Move(val playerId: Int?, val position: String?) : Change

    /**
     * Apply [changes] (oldest first) to [starting] (position code -> player
     * id). A change naming a player who isn't on the pitch (bad data, or one
     * recorded before the lineup was) is ignored rather than guessed at.
     */
    fun current(starting: Map<String, Int>, changes: List<Change>): Map<String, Int> {
        val slots = starting.toMutableMap()
        fun slotOf(id: Int) = slots.entries.firstOrNull { it.value == id }?.key
        changes.forEach { change ->
            when (change) {
                is Sub -> {
                    val out = change.outId ?: return@forEach
                    val inn = change.inId ?: return@forEach
                    val position = slotOf(out) ?: return@forEach
                    slots[position] = inn
                }
                is Move -> {
                    val id = change.playerId ?: return@forEach
                    val to = change.position ?: return@forEach
                    val from = slotOf(id) ?: return@forEach
                    if (from == to) return@forEach
                    val other = slots[to]
                    slots[to] = id
                    if (other != null) slots[from] = other else slots.remove(from)
                }
            }
        }
        return slots
    }

    /**
     * Stage [inId] coming on at [position], on top of the changes already
     * staged in sub mode. If whoever is at [position] came on in a staged sub,
     * that sub is re-picked instead — and picking the player it replaced
     * cancels it.
     */
    fun stageSub(staged: List<Change>, pitch: Map<String, Int>, position: String, inId: Int): List<Change> {
        val here = current(pitch, staged)[position] ?: return staged
        val idx = stagedSubOf(staged, here)
        if (idx < 0) return staged + Sub(here, inId)
        val outId = (staged[idx] as Sub).outId
        return replaceIncoming(staged, idx, if (inId == outId) null else inId)
    }

    /** Drop the staged sub that brought on whoever is at [position]. */
    fun unstageSub(staged: List<Change>, pitch: Map<String, Int>, position: String): List<Change> {
        val here = current(pitch, staged)[position] ?: return staged
        val idx = stagedSubOf(staged, here)
        return if (idx < 0) staged else replaceIncoming(staged, idx, null)
    }

    /** The staged sub that brought [playerId] on, or null. */
    fun stagedSubFor(staged: List<Change>, playerId: Int): Sub? =
        stagedSubOf(staged, playerId).takeIf { it >= 0 }?.let { staged[it] as Sub }

    /** Stage the players at [a] and [b] swapping positions. */
    fun stageSwap(staged: List<Change>, pitch: Map<String, Int>, a: String, b: String): List<Change> {
        val id = current(pitch, staged)[a] ?: return staged
        return staged + Move(id, b)
    }

    /**
     * What sub mode actually posts: the staged subs in order, then one move
     * per player who ends up somewhere other than the subs alone would put
     * them. Swapping two players and back again nets to nothing.
     *
     * Each move puts its player in their final slot and only ever displaces
     * someone not yet in theirs, so replaying them in any order lands on the
     * same XI as the staged sequence.
     */
    fun net(pitch: Map<String, Int>, staged: List<Change>): List<Change> {
        val subs = staged.filterIsInstance<Sub>()
        val afterSubs = current(pitch, subs).entries.associate { (pos, id) -> id to pos }
        val moves = current(pitch, staged)
            .filter { (pos, id) -> afterSubs[id] != pos }
            .map { (pos, id) -> Move(id, pos) }
        return subs + moves
    }

    private fun stagedSubOf(staged: List<Change>, playerId: Int): Int =
        staged.indexOfLast { it is Sub && it.inId == playerId }

    /**
     * Swap the incoming player of the staged sub at [idx] for [newIn] (null
     * drops the sub), carrying any later staged moves of that player across.
     */
    private fun replaceIncoming(staged: List<Change>, idx: Int, newIn: Int?): List<Change> {
        val sub = staged[idx] as Sub
        val heir = newIn ?: sub.outId
        return staged.mapIndexedNotNull { i, c ->
            when {
                i == idx -> newIn?.let { Sub(sub.outId, it) }
                i > idx && c is Move && c.playerId == sub.inId -> Move(heir, c.position)
                else -> c
            }
        }
    }
}
