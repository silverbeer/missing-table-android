package com.missingtable.scorer.ui.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.MatchEvent
import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.domain.MatchDetail
import com.missingtable.scorer.domain.MotwCopy
import com.missingtable.scorer.ui.common.TeamCrest
import com.missingtable.scorer.ui.preview.MatchPreviewSection
import java.time.Instant

/**
 * Where every tap on a match lands (SB-1455) — fans and managers alike.
 *
 * Header and result for everyone; the preview (SB-1456) before kickoff,
 * Head-to-Head after it; the goal and card timeline once there is one. The
 * scoring entry points are buttons here, shown only when the backend says
 * this viewer may manage this match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(
    container: AppContainer,
    matchId: Int,
    onBack: () -> Unit,
    onAction: (MatchSummary, MatchDetail.Action) -> Unit,
) {
    var match by remember { mutableStateOf<MatchSummary?>(null) }
    var events by remember { mutableStateOf<List<MatchEvent>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    // Bumped by Retry so the load runs again.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(matchId, attempt) {
        failed = false
        val loaded = runCatching { container.api.match(matchId) }.getOrNull()
        if (loaded == null) {
            failed = true
            return@LaunchedEffect
        }
        match = loaded
        // A fixture has no events; anything else may.
        if (!MatchDetail.isUpcoming(loaded.matchStatus)) {
            runCatching { container.api.matchEvents(matchId) }
                .onSuccess { all -> events = all.filter { MatchDetail.isTimelineEvent(it.eventType) } }
        }
    }

    val m = match
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(m?.let { "${it.homeTeamName} v ${it.awayTeamName}" } ?: "Match") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (m == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load this match.")
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(m)

            val actions = MatchDetail.actions(m.matchStatus, m.canManage)
            if (actions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { i, action ->
                        if (i == 0) {
                            Button(onClick = { onAction(m, action) }, modifier = Modifier.fillMaxWidth()) {
                                Text(action.label)
                            }
                        } else {
                            OutlinedButton(onClick = { onAction(m, action) }, modifier = Modifier.fillMaxWidth()) {
                                Text(action.label)
                            }
                        }
                    }
                }
            }

            if (events.isNotEmpty()) {
                Timeline(m, events)
            }

            HorizontalDivider()
            MatchPreviewSection(
                container = container,
                homeTeamId = m.homeTeamId,
                homeTeamName = m.homeTeamName,
                awayTeamId = m.awayTeamId,
                awayTeamName = m.awayTeamName,
                seasonId = m.seasonId,
                ageGroupId = m.ageGroupId,
                matchTypeId = m.matchTypeId,
                headToHeadOnly = MatchDetail.previewHeadToHeadOnly(m.matchStatus),
            )
        }
    }
}

/** Crests and names either side of the score (or kickoff), status underneath. */
@Composable
private fun Header(m: MatchSummary) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Side(m.homeTeamClub?.logoUrl, m.homeTeamName, Modifier.weight(1f))
            Text(
                if (MotwCopy.hasScore(m)) "${m.homeScore} – ${m.awayScore}" else "v",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Side(m.awayTeamClub?.logoUrl, m.awayTeamName, Modifier.weight(1f))
        }
        Text(
            MotwCopy.statusLabel(m, Instant.now()),
            style = MaterialTheme.typography.titleSmall,
            color = if (m.matchStatus == "live") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(MotwCopy.metaLine(m), m.matchTypeName).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Side(logoUrl: String?, name: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamCrest(logoUrl, name, size = 48.dp)
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Goals and cards, oldest first, home on the left and away on the right. */
@Composable
private fun Timeline(m: MatchSummary, events: List<MatchEvent>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        events.sortedWith(compareBy({ it.matchMinute ?: 0 }, { it.extraTime ?: 0 }, { it.id })).forEach { e ->
            val minute = e.matchMinute?.let { min -> e.extraTime?.takeIf { it > 0 }?.let { "$min+$it'" } ?: "$min'" } ?: ""
            val home = e.teamId == m.homeTeamId
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    minute,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    "${icon(e.eventType)} ${e.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = if (home) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun icon(eventType: String): String = when (eventType) {
    "goal" -> "⚽"
    "yellow_card" -> "🟨"
    "red_card" -> "🟥"
    else -> ""
}
