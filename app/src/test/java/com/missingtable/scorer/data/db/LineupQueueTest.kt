package com.missingtable.scorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SB-1504: autosave queues a lineup save per edit. Only the newest per team
 * may stay queued, and reopening the lineup reads that one back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineupQueueTest {

    private lateinit var db: MtDatabase
    private lateinit var dao: PendingActionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MtDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.pendingActionDao()
    }

    @After
    fun tearDown() = db.close()

    private var n = 0
    private fun row(type: PendingAction.ActionType, teamId: Int?, payload: String = "{}", matchId: Int = 7) =
        PendingAction(
            clientEventId = "c${n++}",
            matchId = matchId,
            actionType = type,
            payloadJson = payload,
            teamId = teamId,
            createdAt = 0,
        )

    @Test
    fun `latest lineup save wins per team`() = runBlocking {
        dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 1, "first"))
        dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 2, "other team"))
        dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 1, "second"))

        assertEquals("second", dao.latestLineupSave(7, 1)?.payloadJson)
        assertEquals("other team", dao.latestLineupSave(7, 2)?.payloadJson)
        assertNull(dao.latestLineupSave(8, 1))
    }

    @Test
    fun `replacing rewrites the queued save in place, keeping its FIFO slot`() = runBlocking {
        val first = dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 1, "first"))
        val sub = dao.insert(row(PendingAction.ActionType.SUB, 1))

        assertEquals(1, dao.updateQueuedLineupSave(7, 1, "second"))

        // Still ahead of the sub queued after it.
        assertEquals(first, dao.head()?.id)
        assertEquals("second", dao.byId(first)?.payloadJson)
        assertEquals(PendingAction.ActionType.SUB, dao.byId(sub)?.actionType)
    }

    @Test
    fun `replacing a failed save resets it so the queue moves again`() = runBlocking {
        val failed = dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 1))
        dao.setStatus(failed, PendingAction.Status.FAILED, 3, "HTTP 422")

        assertEquals(1, dao.updateQueuedLineupSave(7, 1, "fixed"))

        val r = dao.byId(failed)!!
        assertEquals(PendingAction.Status.PENDING, r.status)
        assertEquals(0, r.attemptCount)
        assertNull(r.lastError)
    }

    @Test
    fun `other teams, other matches and saves in flight are untouched`() = runBlocking {
        val away = dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 2, "away"))
        val otherMatch = dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 1, "m8", matchId = 8))
        val inFlight = dao.insert(row(PendingAction.ActionType.LINEUP_SAVE, 1, "sending"))
        dao.setStatus(inFlight, PendingAction.Status.IN_FLIGHT, 0, null)

        // Only an in-flight save for (7, 1): nothing to rewrite, caller appends.
        assertEquals(0, dao.updateQueuedLineupSave(7, 1, "new"))
        assertEquals("sending", dao.byId(inFlight)?.payloadJson)
        assertEquals("away", dao.byId(away)?.payloadJson)
        assertEquals("m8", dao.byId(otherMatch)?.payloadJson)
    }
}
