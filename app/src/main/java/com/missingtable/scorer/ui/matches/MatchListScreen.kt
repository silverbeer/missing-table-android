package com.missingtable.scorer.ui.matches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.domain.MatchBucketing
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchListScreen(
    container: AppContainer,
    onOpenMatch: (MatchSummary) -> Unit,
    // Update state lives in HomeShell (SB-322/SB-328) — it also owns the
    // force-upgrade gate, so there's a single apkUrl() check per session.
    updateAvailable: Boolean = false,
    onDownloadUpdate: () -> Unit = {},
) {
    var matches by remember { mutableStateOf<List<MatchSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        val today = LocalDate.now()
        runCatching {
            container.api.matches(
                // /api/matches has NO server-side season default — without
                // this, prior-season matches inside the window leak in
                // (SB-338). Null (offline) degrades to date-window-only.
                seasonId = container.currentSeasonId(),
                // Wide window so the list isn't empty off-season
                startDate = today.minusDays(60).toString(),
                endDate = today.plusDays(30).toString(),
            )
        }.onSuccess {
            matches = it
            loading = false
        }.onFailure {
            error = "Could not load matches"
            loading = false
        }
    }

    val today = LocalDate.now().toString()
    val buckets = MatchBucketing.bucket(matches, today)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Matches") },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (updateAvailable) {
                item {
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDownloadUpdate() },
                    ) {
                        Text(
                            "Update available — tap to download the new version",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            if (error != null) {
                item { Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            }
            section("LIVE NOW", buckets.live, onOpenMatch, highlight = true)
            // Above TODAY on purpose: an unscored match from a past date is
            // the thing most likely to need action right now (SB-641).
            section("NEEDS SCORING", buckets.needsScoring, onOpenMatch, highlight = true)
            section("TODAY", buckets.todays, onOpenMatch)
            section("UPCOMING", buckets.upcoming, onOpenMatch)
            section("RECENT", buckets.recent, onOpenMatch)
            section("POSTPONED / OTHER", buckets.other, onOpenMatch)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<MatchSummary>,
    onOpen: (MatchSummary) -> Unit,
    highlight: Boolean = false,
) {
    if (items.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
    }
    items(items, key = { "${title}-${it.id}" }) { m ->
        Card(modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(m) }) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${m.homeTeamName} vs ${m.awayTeamName}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (m.homeScore != null && m.awayScore != null) {
                        Text(
                            "${m.homeScore}–${m.awayScore}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    listOfNotNull(m.matchDate, m.ageGroupName, m.matchTypeName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
