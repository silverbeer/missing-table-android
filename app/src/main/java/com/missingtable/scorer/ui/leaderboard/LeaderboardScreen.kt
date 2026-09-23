package com.missingtable.scorer.ui.leaderboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.AgeGroupDto
import com.missingtable.scorer.data.api.LeaderboardEntry
import com.missingtable.scorer.data.api.MatchTypeDto
import com.missingtable.scorer.domain.LeaderFilters
import kotlinx.coroutines.launch

/**
 * Top scorers (read-only). season_id is required server-side → current season
 * first.
 *
 * Filtered by age group and competition (SB-1119). Both are query params
 * rather than passes over a loaded list, which is why this tab has no "All"
 * competition and no "League + Flex" — see [LeaderFilters]. Every change
 * refetches; the list is small and this is not a match-day screen.
 */
@Composable
fun LeaderboardScreen(container: AppContainer) {
    var entries by remember { mutableStateOf<List<LeaderboardEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ageGroups by remember { mutableStateOf<List<AgeGroupDto>>(emptyList()) }
    var available by remember { mutableStateOf<List<MatchTypeDto>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val savedAgeGroup by container.uiPrefs.leadersAgeGroup.collectAsState(initial = null)
    val savedType by container.uiPrefs.leadersMatchType.collectAsState(initial = null)

    LaunchedEffect(Unit) {
        runCatching { container.api.ageGroups() }.onSuccess { ageGroups = it }
    }

    // Which competitions are played narrows with the age group, so this
    // refetches with it: U13 plays no Flex and must not be offered a Flex chip.
    //
    // `typesResolved` gates the leaderboard fetch below. Without it the first
    // request goes out before any competition is known — unfiltered — and the
    // tab flashes a leaderboard pooling every competition, which is the exact
    // ambiguity this filter exists to remove. A failed lookup still resolves,
    // so a lost connection degrades to the unfiltered list rather than a
    // spinner that never ends.
    var typesResolved by remember { mutableStateOf(false) }
    LaunchedEffect(savedAgeGroup) {
        typesResolved = false
        runCatching {
            container.api.availableMatchTypes(
                seasonId = container.currentSeasonId(),
                ageGroupId = savedAgeGroup,
            )
        }.onSuccess { available = it }
        typesResolved = true
    }

    val chips = LeaderFilters.chips(available)
    val activeType = LeaderFilters.resolve(chips, savedType)
    val typeDropped = LeaderFilters.savedChoiceUnavailable(chips, savedType)

    LaunchedEffect(savedAgeGroup, activeType?.id, typesResolved) {
        if (!typesResolved) return@LaunchedEffect
        entries = null
        error = null
        runCatching {
            val seasonId = requireNotNull(container.currentSeasonId()) { "no season" }
            container.api.goalsLeaderboard(
                seasonId = seasonId,
                ageGroupId = savedAgeGroup,
                matchTypeId = activeType?.id,
            )
        }
            .onSuccess { entries = it }
            .onFailure { error = "Couldn't load the leaderboard" }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (ageGroups.isNotEmpty()) {
            ChipRow {
                FilterChip(
                    selected = savedAgeGroup == null,
                    onClick = { scope.launch { container.uiPrefs.setLeadersAgeGroup(null) } },
                    label = { Text("All ages") },
                )
                ageGroups.forEach { group ->
                    FilterChip(
                        selected = savedAgeGroup == group.id,
                        onClick = { scope.launch { container.uiPrefs.setLeadersAgeGroup(group.id) } },
                        label = { Text(group.name) },
                    )
                }
            }
        }
        // No "All" chip: a leaderboard is always a leaderboard *of* something,
        // and pooling Friendlies with League is not a standing.
        if (chips.size > 1) {
            ChipRow {
                chips.forEach { type ->
                    FilterChip(
                        selected = activeType?.id == type.id,
                        onClick = { scope.launch { container.uiPrefs.setLeadersMatchType(type.id) } },
                        label = { Text(type.name) },
                    )
                }
            }
        }
        if (typeDropped) {
            Text(
                "Not played at this age group — showing ${activeType?.name ?: "everything"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The list is a ranking of something; saying what costs one line and
        // stops the numbers being ambiguous.
        Text(
            scopeLabel(activeType, ageGroups.firstOrNull { it.id == savedAgeGroup }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
        LeaderboardBody(entries, error, activeType, ageGroups.firstOrNull { it.id == savedAgeGroup })
    }
}

/** "Top scorers · League · U15" — every part that is known, none that is not. */
private fun scopeLabel(type: MatchTypeDto?, ageGroup: AgeGroupDto?): String =
    listOfNotNull("Top scorers", type?.name, ageGroup?.name ?: "all ages").joinToString(" · ")

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ColumnScope.LeaderboardBody(
    entries: List<LeaderboardEntry>?,
    error: String?,
    type: MatchTypeDto?,
    ageGroup: AgeGroupDto?,
) {
    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Name the filters rather than blaming the season: with a filter
            // on, "no goals yet this season" is simply false.
            Text(
                listOfNotNull(
                    "No goals yet",
                    type?.name?.let { "in $it" },
                    ageGroup?.name?.let { "for $it" },
                ).joinToString(" "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                HeaderCell("#", 32.dp)
                Text(
                    "Player",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                HeaderCell("GP", 36.dp)
                HeaderCell("Goals", 48.dp)
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.playerId ?: it.rank }) { e ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${e.rank}",
                            modifier = Modifier.width(32.dp),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.jerseyNumber?.let { "#$it ${e.playerLabel}" } ?: e.playerLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            e.teamName?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            "${e.gamesPlayed}",
                            modifier = Modifier.width(36.dp),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "${e.goals}",
                            modifier = Modifier.width(48.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}
