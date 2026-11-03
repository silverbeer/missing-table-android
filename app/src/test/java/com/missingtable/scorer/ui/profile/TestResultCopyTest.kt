package com.missingtable.scorer.ui.profile

import com.missingtable.scorer.data.api.TestNotificationResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/** SB-1464: the snackbar copy after "Send test notification". */
class TestResultCopyTest {

    @Test
    fun `no fcm devices means this phone is not registered`() {
        assertEquals(
            "This phone isn't registered for notifications yet",
            testResultCopy(TestNotificationResponse(sent = 2, fcmDevices = 0)),
        )
    }

    @Test
    fun `registered but nothing sent is a failure`() {
        assertEquals(
            "Test notification didn't go through",
            testResultCopy(TestNotificationResponse(sent = 0, failed = 1, fcmDevices = 1)),
        )
    }

    @Test
    fun `one device is singular`() {
        assertEquals("Test sent to 1 device", testResultCopy(TestNotificationResponse(sent = 1, fcmDevices = 1)))
    }

    @Test
    fun `several devices are plural`() {
        assertEquals("Test sent to 3 devices", testResultCopy(TestNotificationResponse(sent = 3, fcmDevices = 2)))
    }
}
