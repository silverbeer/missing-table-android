package com.missingtable.scorer.ui.lineup

import com.missingtable.scorer.data.db.PendingAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** SB-1592: "Saved ✓" means the server has it, not that it's queued. */
class LineupSaveLabelTest {

    private fun queued(status: PendingAction.Status = PendingAction.Status.PENDING, error: String? = null) =
        PendingAction(
            id = 1, clientEventId = "l1", matchId = 7, actionType = PendingAction.ActionType.LINEUP_SAVE,
            payloadJson = "{}", teamId = 3, status = status, lastError = error, createdAt = 0,
        )

    @Test
    fun `nothing queued reads saved`() {
        assertEquals(SaveLabel("Saved ✓", false), lineupSaveLabel(false, false, null, null))
    }

    @Test
    fun `a queued save reads queued, not saved`() {
        assertEquals(
            SaveLabel("Queued — not on the server yet", false),
            lineupSaveLabel(false, false, null, queued()),
        )
    }

    @Test
    fun `a save in flight still reads queued`() {
        assertEquals(
            "Queued — not on the server yet",
            lineupSaveLabel(false, false, null, queued(PendingAction.Status.IN_FLIGHT)).text,
        )
    }

    @Test
    fun `a rejected save is an error with the reason`() {
        assertEquals(
            SaveLabel("Not saved — server rejected it (HTTP 400)", true),
            lineupSaveLabel(false, false, null, queued(PendingAction.Status.FAILED, "HTTP 400")),
        )
    }

    @Test
    fun `local states win over the queue`() {
        assertEquals(SaveLabel("Saving…", false), lineupSaveLabel(false, true, null, queued()))
        assertEquals(SaveLabel("Numbers", true), lineupSaveLabel(true, false, "Numbers", queued()))
    }
}
