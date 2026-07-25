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

    /** Crash recovery: anything stuck IN_FLIGHT from a dead process is pending again. */
    @Query("UPDATE pending_actions SET status = 'PENDING' WHERE status = 'IN_FLIGHT'")
    suspend fun resetInFlight()
}
