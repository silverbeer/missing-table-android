package com.missingtable.scorer.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.BuildConfig
import com.missingtable.scorer.data.api.PlayerStatsResponse
import com.missingtable.scorer.data.auth.Session

/**
 * Read-only profile v1 (SB-325): cached session identity + this season's
 * player stats for linked players. Photo/social editing stays on web.
 */
@Composable
fun ProfileScreen(container: AppContainer, onLogout: () -> Unit) {
    val session by container.tokenStore.sessionFlow.collectAsState(initial = Session())
    var stats by remember { mutableStateOf<PlayerStatsResponse?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            val season = container.api.currentSeason()
            container.api.myPlayerStats(season.id)
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

        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        Text(
            "MT Scorer ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
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
