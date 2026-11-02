package com.missingtable.scorer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingActionDao {

    @Insert
    suspend fun insert(action: PendingAction): Long

    /** Head of the queue — the row every sync attempt must clear first. */
    @Query("SELECT * FROM pending_actions ORDER BY id LIMIT 1")
    suspend fun head(): PendingAction?

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun byId(id: Long): PendingAction?

    @Query("SELECT * FROM pending_actions WHERE match_id = :matchId ORDER BY id")
    fun observeForMatch(matchId: Int): Flow<List<PendingAction>>

    @Query("SELECT COUNT(*) FROM pending_actions")
    fun observeCount(): Flow<Int>

    @Query("UPDATE pending_actions SET status = :status, attempt_count = :attempts, last_error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: PendingAction.Status, attempts: Int, error: String?)

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun delete(id: Long)

    /** Local delete of a not-yet-synced action; refuses rows already in flight. */
    @Query("DELETE FROM pending_actions WHERE id = :id AND status != 'IN_FLIGHT'")
    suspend fun deleteIfNotInFlight(id: Long): Int

    /**
     * The newest queued lineup save for one team (SB-1504): what the user last
     * entered, even before the server has it. Reopening the lineup must show
     * this, not the server's older copy.
     */
    @Query(
        "SELECT * FROM pending_actions WHERE match_id = :matchId AND team_id = :teamId " +
            "AND action_type = 'LINEUP_SAVE' ORDER BY id DESC LIMIT 1"
    )
    suspend fun latestLineupSave(matchId: Int, teamId: Int): PendingAction?

    /**
     * Rewrite the queued lineup save for one team in place (SB-1504). Autosave
     * saves on every edit; a lineup save is a whole-lineup overwrite, so only
     * the newest matters. Updating keeps the row's place in the FIFO (a
     * delete + append would move the lineup behind subs queued since) and is
     * one statement, so a crash can't leave the team with no save queued.
     * A FAILED row is reset to PENDING: a fresh edit retries it. A row in
     * flight is left alone (returns 0) — the caller appends a new one.
     */
    @Query(
        "UPDATE pending_actions SET payload_json = :payloadJson, status = 'PENDING', " +
            "attempt_count = 0, last_error = NULL WHERE id = (SELECT id FROM pending_actions " +
            "WHERE match_id = :matchId AND team_id = :teamId AND action_type = 'LINEUP_SAVE' " +
            "AND status != 'IN_FLIGHT' ORDER BY id DESC LIMIT 1)"
    )
    suspend fun updateQueuedLineupSave(matchId: Int, teamId: Int, payloadJson: String): Int

    /** Crash recovery: anything stuck IN_FLIGHT from a dead process is pending again. */
    @Query("UPDATE pending_actions SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun resetInFlight()
}
