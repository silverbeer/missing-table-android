package com.missingtable.scorer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A queued live-scoring mutation awaiting sync. Autoincrement id doubles as
 * strict FIFO order. Rows are deleted once the server accepts them, so the
 * table only ever holds the outstanding tail.
 */
@Entity(
    tableName = "pending_actions",
    indices = [
        Index("match_id"),
        Index(value = ["client_event_id"], unique = true),
    ],
)
data class PendingAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Reused verbatim across retries — the server's idempotency key.
    @ColumnInfo(name = "client_event_id") val clientEventId: String,
    @ColumnInfo(name = "match_id") val matchId: Int,
    @ColumnInfo(name = "action_type") val actionType: ActionType,
    // The serialized request DTO, minute/extra_time/occurred_at baked in at tap time.
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    // Routing extras the path (not the body) needs.
    @ColumnInfo(name = "team_id") val teamId: Int? = null,
    @ColumnInfo(name = "server_event_id") val serverEventId: Int? = null,
    val status: Status = Status.PENDING,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    enum class ActionType { GOAL, CARD, SUB, CLOCK, DELETE_EVENT, REOPEN, LINEUP_SAVE, MESSAGE }

    // FAILED means a 4xx: the server rejected the action outright. The queue
    // pauses there (strict ordering) until the user retries or discards it.
    enum class Status { PENDING, IN_FLIGHT, FAILED }
}
