package com.missingtable.scorer.ui.common

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.domain.NotificationIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Asks for notification permission at the moment it means something — the
 * first Follow (SB-1464) — rather than at launch.
 *
 * Android 13+ shows the system prompt until the user refuses it for good;
 * after that, or wherever notifications are off at the OS level, the gate
 * explains and links to the app's notification settings, which is the only
 * place they can be turned back on.
 */
class NotifPermissionGate internal constructor(private val onFollow: () -> Unit) {
    /** Call after a successful follow. */
    fun onFollowed() = onFollow()
}

@Composable
fun rememberNotifPermissionGate(container: AppContainer): NotifPermissionGate {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied but still askable: leave it for the next follow. Only a
        // permanent denial needs the trip to Settings.
        if (!granted && !shouldShowRationale(context)) showSettings = true
    }

    if (showSettings) {
        NotificationsOffDialog(
            onOpenSettings = {
                showSettings = false
                openNotificationSettings(context)
            },
            onDismiss = { showSettings = false },
        )
    }

    return remember(container, launcher) {
        NotifPermissionGate {
            scope.launch {
                val action = permissionAction(
                    sdk = Build.VERSION.SDK_INT,
                    permissionGranted = permissionGranted(context),
                    notificationsEnabled = notificationsEnabled(context),
                    askedBefore = container.pushPrefs.askedNotifPermission.first(),
                    showRationale = shouldShowRationale(context),
                )
                when (action) {
                    PermissionAction.NONE -> Unit
                    // permissionAction only requests on 13+; the check is for lint.
                    PermissionAction.REQUEST -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        container.pushPrefs.setAskedNotifPermission()
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    PermissionAction.SETTINGS -> showSettings = true
                }
            }
        }
    }
}

internal enum class PermissionAction { NONE, REQUEST, SETTINGS }

/**
 * What a follow should do about notifications. The system prompt can be shown
 * again until the user has refused it for good — on 13+ that is "asked
 * before, and no rationale offered". Past that, or when notifications or our
 * channel are off at the OS level, only Settings can turn them back on.
 */
internal fun permissionAction(
    sdk: Int,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    askedBefore: Boolean,
    showRationale: Boolean,
): PermissionAction = when {
    notificationsEnabled -> PermissionAction.NONE
    sdk >= Build.VERSION_CODES.TIRAMISU && !permissionGranted ->
        if (!askedBefore || showRationale) PermissionAction.REQUEST else PermissionAction.SETTINGS
    else -> PermissionAction.SETTINGS
}

private fun permissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun shouldShowRationale(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = context.findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun NotificationsOffDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications are off") },
        text = {
            Text(
                "You're following this team, but Missing Table can't notify you. " +
                    "Turn on notifications in Settings to get kickoff, goals and results."
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * Whether a live-score push would actually show: app notifications on (which
 * on 13+ includes the runtime permission) and our channel not blocked.
 */
fun notificationsEnabled(context: Context): Boolean {
    val compat = NotificationManagerCompat.from(context)
    if (!compat.areNotificationsEnabled()) return false
    val channel = context.getSystemService(NotificationManager::class.java)
        ?.getNotificationChannel(NotificationIntent.CHANNEL_ID)
    return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
}

/** [notificationsEnabled], re-read every time the screen resumes (back from Settings). */
@Composable
fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(notificationsEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = notificationsEnabled(context)
        onPauseOrDispose { }
    }
    return enabled
}

fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
