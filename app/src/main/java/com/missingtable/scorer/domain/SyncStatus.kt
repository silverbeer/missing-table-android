package com.missingtable.scorer.domain

import com.missingtable.scorer.data.db.PendingAction

/**
 * What the app-wide sync banner shows (SB-1592), from the whole pending queue.
 *
 * On 2026-11-07 a match was scored with nothing reaching the server and no
 * sign of it in the app: the only failure banner read the current match's
 * rows, so a stuck row from another match was invisible. This looks at every
 * row, on every screen.
 *
 * Null means stay quiet. A queue that is draining normally (rows a few
 * seconds old, no error yet) shows nothing, or every tap would flash a banner.
 */
data class SyncStatus(
    val kind: Kind,
    /** Every unsent row, all matches. */
    val unsent: Int,
    /** The row the banner is about: the blocker, the failed lineup, or the head. */
    val row: PendingAction,
    /** Matches with unsent rows, oldest first. */
    val matchIds: List<Int>,
) {
    enum class Kind {
        /** A rejected (4xx) action at the head: nothing behind it syncs until Retry or Discard. */
        BLOCKED,

        /** A rejected lineup save. It doesn't block scoring, but the server has no lineup. */
        LINEUP_FAILED,

        /** The head has been failing transiently (offline, 5xx, 401) for over [STALE_MS]. */
        RETRYING,

        /** Rows have waited longer than [STALE_MS] without an error: still unsent. */
        WAITING,
    }

    val canRetry get() = row.status == PendingAction.Status.FAILED
    val canDiscard get() = row.status == PendingAction.Status.FAILED

    companion object {
        const val STALE_MS = 30_000L

        fun from(rows: List<PendingAction>, nowMs: Long): SyncStatus? {
            // Rows arrive in queue order (the DAO sorts by id).
            if (rows.isEmpty()) return null
            val matchIds = rows.map { it.matchId }.distinct()
            fun status(kind: Kind, row: PendingAction) = SyncStatus(kind, rows.size, row, matchIds)

            // What the engine works on next — the same rule it uses.
            val next = rows.firstOrNull { !it.isSteppedOver() }
            if (next?.status == PendingAction.Status.FAILED) return status(Kind.BLOCKED, next)
            // A stuck head outranks a rejected lineup: "scoring still syncs"
            // must not be said while scoring is stuck. Both wait STALE_MS, so a
            // brief drop in signal doesn't flash a banner (and shift the
            // screen) mid-match.
            if (next != null && nowMs - next.createdAt >= STALE_MS) {
                return status(if (next.lastError != null) Kind.RETRYING else Kind.WAITING, next)
            }
            rows.firstOrNull { it.isSteppedOver() }?.let { return status(Kind.LINEUP_FAILED, it) }
            return null
        }
    }
}

/** A FAILED row the engine steps over rather than pausing on. */
fun PendingAction.isSteppedOver(): Boolean = status == PendingAction.Status.FAILED && skipsWhenFailed()

/**
 * A terminally rejected lineup save is stepped over rather than pausing the
 * queue (SB-1592). A lineup is a whole-resource overwrite that no scoring
 * event depends on server-side, so holding goals behind it only loses them.
 * The one place this rule lives: the engine and the banner both read it.
 */
fun PendingAction.skipsWhenFailed(): Boolean = actionType == PendingAction.ActionType.LINEUP_SAVE
