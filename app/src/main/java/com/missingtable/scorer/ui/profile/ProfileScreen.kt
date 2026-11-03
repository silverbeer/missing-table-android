package com.missingtable.scorer.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.BuildConfig
import com.missingtable.scorer.data.api.PlayerStatsResponse
import com.missingtable.scorer.data.auth.Session
import com.missingtable.scorer.ui.common.openNotificationSettings
import com.missingtable.scorer.ui.common.rememberNotificationsEnabled
import com.missingtable.scorer.ui.team.FOLLOW_FAILED
import com.missingtable.scorer.ui.team.subtitle
import kotlinx.coroutines.launch

/**
 * Read-only profile v1 (SB-325): cached session identity + this season's
 * player stats for linked players. Photo/social editing stays on web.
 * Followed teams and notification settings live here too (SB-1464).
 */
@Composable
fun ProfileScreen(
    container: AppContainer,
    onLogout: () -> Unit,
    onOpenTeam: (Int, String, String?) -> Unit = { _, _, _ -> },
    onOpenNotificationPrefs: () -> Unit = {},
) {
    val session by container.tokenStore.sessionFlow.collectAsState(initial = Session())
    var stats by remember { mutableStateOf<PlayerStatsResponse?>(null) }
    val follows by container.teamFollows.follows.collectAsState()
    val notificationsOn = rememberNotificationsEnabled()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Teams with an Unfollow in flight: one request per row.
    var unfollowing by remember { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(Unit) { container.teamFollows.refresh() }

    LaunchedEffect(Unit) {
        runCatching {
            val seasonId = requireNotNull(container.currentSeasonId()) { "no season" }
            container.api.myPlayerStats(seasonId)
        }.onSuccess { stats = it }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            session.displayName ?: session.username ?: "Profile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        session.role?.let {
            Text(
                it.replace('_', ' ').replace('-', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Scrolls between the pinned name and footer: the follow list can be long.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = stats
            if (s?.linked == true) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            buildString {
                                append("This season")
                                s.jerseyNumber?.let { append("  ·  #$it") }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Stat("Games", "${s.stats.gamesPlayed}")
                            Stat("Started", "${s.stats.gamesStarted}")
                            Stat("Minutes", "${s.stats.totalMinutes}")
                            Stat("Goals", "${s.stats.totalGoals}")
                        }
                    }
                }
            }


            if (!notificationsOn) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.NotificationsOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Notifications are off for Missing Table",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        TextButton(onClick = { openNotificationSettings(context) }) { Text("Turn on") }
                    }
                }
            }

            Text("Following", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (follows.isEmpty()) {
                Text(
                    "Follow a team from a match or the table to get its kickoff, goals and results.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            follows.forEach { f ->
                val name = f.team?.name ?: "Team ${f.teamId}"
                val sub = f.team?.subtitle()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenTeam(f.teamId, name, sub) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        sub?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        enabled = f.teamId !in unfollowing,
                        onClick = {
                            val id = f.teamId
                            unfollowing = unfollowing + id
                            scope.launch {
                                val ok = container.teamFollows.setFollowing(id, false)
                                unfollowing = unfollowing - id
                                if (!ok) {
                                    android.widget.Toast
                                        .makeText(context, FOLLOW_FAILED, android.widget.Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                    ) {
                        Text("Unfollow")
                    }
                }
            }

            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenNotificationPrefs)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Notification settings", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }

        HorizontalDivider()
        Text(
            "Missing Table ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text("Log out") }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
