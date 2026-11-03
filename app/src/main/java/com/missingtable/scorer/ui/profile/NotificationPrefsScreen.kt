package com.missingtable.scorer.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.NotificationPrefs
import com.missingtable.scorer.data.api.TestNotificationResponse
import com.missingtable.scorer.domain.NotificationIntent
import com.missingtable.scorer.ui.common.openNotificationSettings
import com.missingtable.scorer.ui.common.rememberNotificationsEnabled
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Which match events push for followed teams (SB-1464). Each toggle saves its
 * one key on its own and shows the server's merged answer, so what is on
 * screen is always what the server will act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPrefsScreen(container: AppContainer, onBack: () -> Unit) {
    var prefs by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationsOn = rememberNotificationsEnabled()

    LaunchedEffect(attempt) {
        failed = false
        runCatching { container.api.notificationPrefs() }
            .onSuccess { prefs = NotificationIntent.withDefaults(it.preferences) }
            .onFailure { failed = true }
    }

    fun toggle(key: String, value: Boolean) {
        val before = prefs ?: return
        prefs = before + (key to value)
        scope.launch {
            runCatching { container.api.putNotificationPrefs(NotificationPrefs(mapOf(key to value))) }
                .onSuccess { prefs = NotificationIntent.withDefaults(it.preferences) }
                .onFailure {
                    prefs = prefs?.plus(key to !value)
                    snackbar.showSnackbar("Couldn't save — try again")
                }
        }
    }

    fun sendTest() {
        if (sending) return
        sending = true
        scope.launch {
            // Make sure this phone is on the account before asking the server
            // to reach it.
            container.pushRegistrar.register()
            val msg = runCatching { container.api.testNotification() }.fold(
                onSuccess = ::testResultCopy,
                onFailure = { e ->
                    when ((e as? HttpException)?.code()) {
                        429 -> "Too many tests — wait a minute and try again"
                        503 -> "Notifications aren't set up on the server yet"
                        else -> "Couldn't send a test notification"
                    }
                },
            )
            sending = false
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = prefs
        if (current == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load your notification settings.")
                        TextButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (!notificationsOn) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Notifications are off for Missing Table, so none of these will reach you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { openNotificationSettings(context) }) { Text("Turn on") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "For the teams you follow, notify me about:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            NotificationIntent.PREFS.forEach { pref ->
                val checked = current[pref.key] ?: pref.default
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Switch) { toggle(pref.key, it) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pref.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = null)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { sendTest() },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (sending) "Sending…" else "Send test notification") }
        }
    }
}

internal fun testResultCopy(r: TestNotificationResponse): String = when {
    r.fcmDevices == 0 -> "This phone isn't registered for notifications yet"
    r.sent == 0 -> "Test notification didn't go through"
    else -> "Test sent to ${r.sent} device${if (r.sent == 1) "" else "s"}"
}
