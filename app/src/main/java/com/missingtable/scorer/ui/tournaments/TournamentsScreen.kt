package com.missingtable.scorer.ui.tournaments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.TournamentDetail
import com.missingtable.scorer.data.api.TournamentMatch
import com.missingtable.scorer.data.api.TournamentSummary
import com.missingtable.scorer.domain.TournamentStandings

/**
 * Tournaments tab (SB-324, read-only): list → detail with per-group
 * standings (computed client-side) + matches by group/round. Bracket
 * rendering deliberately deferred.
 */
@Composable
fun TournamentsScreen(container: AppContainer) {
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }

    val id = selectedId
    if (id == null) {
        TournamentList(container) { selectedId = it }
    } else {
        TournamentDetailView(container, id) { selectedId = null }
    }
}

@Composable
private fun TournamentList(container: AppContainer, onOpen: (Int) -> Unit) {
    var tournaments by remember { mutableStateOf<List<TournamentSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { container.api.tournaments() }
            .onSuccess { tournaments = it }
            .onFailure { error = "Couldn't load tournaments" }
    }

    when {
        error != null -> Center(error!!)
        tournaments == null -> Loading()
        tournaments!!.isEmpty() -> Center("No tournaments yet")
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tournaments!!, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(t.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val dates = listOfNotNull(t.startDate, t.endDate).distinct().joinToString(" → ")
                        val sub = listOfNotNull(
                            dates.ifBlank { null },
                            t.location,
                            t.ageGroups.joinToString(", ") { it.name }.ifBlank { null },
                            "${t.matchCount} matches",
                        ).joinToString("  ·  ")
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TournamentDetailView(container: AppContainer, tournamentId: Int, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<TournamentDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tournamentId) {
        runCatching { container.api.tournament(tournamentId) }
            .onSuccess { detail = it }
            .onFailure { error = "Couldn't load tournament" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to tournaments")
            }
            Text(
                detail?.name ?: "Tournament",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            error != null -> Center(error!!)
            detail == null -> Loading()
            else -> {
                val matches = detail!!.matches
                // Group stage first (alphabetical groups), then knockout rounds
                // in round order, then anything unclassified.
                val byGroup = matches.filter { it.tournamentGroup != null }
                    .groupBy { it.tournamentGroup!! }
                    .toSortedMap()
                val byRound = matches.filter { it.tournamentGroup == null && it.tournamentRound != null }
                    .sortedBy { it.tournamentRoundOrder ?: Int.MAX_VALUE }
                    .groupBy { it.tournamentRound!! }
                val rest = matches.filter { it.tournamentGroup == null && it.tournamentRound == null }

                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    byGroup.forEach { (group, groupMatches) ->
                        item(key = "group-$group") {
                            Column {
                                SectionTitle("Group $group")
                                StandingsTable(TournamentStandings.compute(groupMatches))
                            }
                        }
                        items(groupMatches, key = { "gm-${it.id}" }) { MatchLine(it) }
                    }
                    byRound.forEach { (round, roundMatches) ->
                        item(key = "round-$round") { SectionTitle(round) }
                        items(roundMatches, key = { "rm-${it.id}" }) { MatchLine(it) }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "rest") { SectionTitle("Matches") }
                        items(rest, key = { "xm-${it.id}" }) { MatchLine(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun StandingsTable(rows: List<TournamentStandings.Row>) {
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row {
                Text(
                    "Team",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                listOf("P", "W", "D", "L", "GD", "Pts").forEach { NumCell(it, bold = true) }
            }
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            rows.forEach { r ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        r.teamName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NumCell("${r.played}")
                    NumCell("${r.wins}")
                    NumCell("${r.draws}")
                    NumCell("${r.losses}")
                    NumCell(if (r.goalDifference > 0) "+${r.goalDifference}" else "${r.goalDifference}")
                    NumCell("${r.points}", bold = true)
                }
            }
        }
    }
}

@Composable
private fun NumCell(text: String, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(28.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MatchLine(m: TournamentMatch) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${m.homeTeam?.name ?: "TBD"} v ${m.awayTeam?.name ?: "TBD"}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(m.matchDate, m.ageGroup?.name).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            val score = if (m.homeScore != null && m.awayScore != null) {
                val pens = if (m.homePenaltyScore != null && m.awayPenaltyScore != null) {
                    " (${m.homePenaltyScore}–${m.awayPenaltyScore} pens)"
                } else ""
                "${m.homeScore}–${m.awayScore}$pens"
            } else {
                m.scheduledKickoff ?: ""
            }
            Text(score, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
