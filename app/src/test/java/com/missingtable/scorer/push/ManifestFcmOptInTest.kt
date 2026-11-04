package com.missingtable.scorer.push

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SB-1531: FirebaseMessaging.register() (FID-based FCM) throws "API disabled"
 * unless the manifest opts in. Without it FCM answers UNREGISTERED and the
 * backend prunes the device, so pushes silently never arrive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestFcmOptInTest {

    @Test
    fun manifestOptsInToInstallationIdMessaging() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val meta = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
        assertTrue(
            "firebase_messaging_installation_id_enabled must be true in AndroidManifest.xml",
            meta?.getBoolean("firebase_messaging_installation_id_enabled", false) == true,
        )
    }
}
