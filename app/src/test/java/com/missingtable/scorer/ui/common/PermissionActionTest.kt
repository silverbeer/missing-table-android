package com.missingtable.scorer.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** SB-1464: when a follow asks, re-asks, or sends the user to Settings. */
class PermissionActionTest {

    private fun action(
        sdk: Int = 34,
        granted: Boolean = false,
        enabled: Boolean = false,
        asked: Boolean = false,
        rationale: Boolean = false,
    ) = permissionAction(sdk, granted, enabled, asked, rationale)

    @Test
    fun `notifications already on - nothing to do`() {
        assertEquals(PermissionAction.NONE, action(granted = true, enabled = true))
        assertEquals(PermissionAction.NONE, action(sdk = 30, granted = true, enabled = true))
    }

    @Test
    fun `13+ never asked - request`() {
        assertEquals(PermissionAction.REQUEST, action())
    }

    @Test
    fun `13+ denied once, still askable - request again rather than Settings`() {
        assertEquals(PermissionAction.REQUEST, action(asked = true, rationale = true))
    }

    @Test
    fun `13+ permanently denied - Settings`() {
        assertEquals(PermissionAction.SETTINGS, action(asked = true, rationale = false))
    }

    @Test
    fun `granted but switched off at the OS or channel level - Settings`() {
        assertEquals(PermissionAction.SETTINGS, action(granted = true, enabled = false))
    }

    @Test
    fun `pre-13 with notifications off - Settings, there is no prompt`() {
        assertEquals(PermissionAction.SETTINGS, action(sdk = 30, granted = true, enabled = false))
    }
}
