package com.missingtable.scorer.ui.matches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.data.api.MatchTypeDto
import com.missingtable.scorer.data.api.Motw
import com.missingtable.scorer.ui.common.TeamCrest
import com.missingtable.scorer.domain.Competitions
import com.missingtable.scorer.domain.MatchBucketing
import com.missingtable.scorer.domain.MatchWeek
import java.time.LocalDate
import kotlinx.coroutines.launch

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
    var matchTypes by remember { mutableStateOf<List<MatchTypeDto>>(emptyList()) }
    var motw by remember { mutableStateOf<Motw?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // Age-group filter (SB-642). Applied client-side to the already-fetched
    // window rather than as a query param: instant, works with no signal, and
    // no refetch per tap — which matters at a pitch far more than it would on
    // a desk. The fetch is already bounded by season + a 90-day window.
    val scope = rememberCoroutineScope()
    val savedAgeGroup by container.uiPrefs.matchesAgeGroup.collectAsState(initial = null)
    val savedType by container.uiPrefs.matchesType.collectAsState(initial = null)
    val typeChosen by container.uiPrefs.matchesTypeChosen.collectAsState(initial = false)
    var weekOffset by remember { mutableIntStateOf(0) }
    var ageGroupTouched by remember { mutableStateOf(false) }
    var ageGroup by remember { mutableStateOf<Int?>(null) }
    // Adopt the persisted choice once, on first emission, then let taps win.
    LaunchedEffect(savedAgeGroup) {
        if (!ageGroupTouched) ageGroup = savedAgeGroup
    }

    // Competition metadata changes about once a season; a failure here is not
    // an error state — the chips fall back to the names the rows carry.
    LaunchedEffect(Unit) {
        runCatching { container.api.matchTypes() }.onSuccess { matchTypes = it }
    }

    // The pick belongs to the week on screen, so week navigation moves it.
    // No pick is the ordinary answer and renders as nothing; so does a failed
    // fetch — an error banner over a feature most weeks do not use would be
    // noise on the one screen that has to stay readable at a pitch.
    LaunchedEffect(reloadKey, weekOffset) {
        val monday = MatchWeek.of(LocalDate.now(), weekOffset).start
        motw = runCatching { container.api.motw(monday.toString()) }.getOrNull()?.motw
    }

    LaunchedEffect(reloadKey, weekOffset) {
        loading = true
        error = null
        val today = LocalDate.now()
        val week = MatchWeek.of(today, weekOffset)
        // On "This Week" reach further back so NEEDS SCORING keeps its
        // look-back (SB-641) — an unscored match from an earlier week must not
        // vanish just because the list is week-scoped. Other weeks fetch only
        // themselves.
        val from = if (weekOffset == 0) today.minusDays(60) else week.start
        runCatching {
            container.api.matches(
                // /api/matches has NO server-side season default — without
                // this, prior-season matches inside the window leak in
                // (SB-338). Null (offline) degrades to date-window-only.
                seasonId = container.currentSeasonId(),
                startDate = from.toString(),
                endDate = week.end.toString(),
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
    val week = MatchWeek.of(LocalDate.now(), weekOffset)

    // Chips are built from what's actually in the list, so a filter only
    // appears when there is something to show for it.
    val ageGroupOptions = matches
        .mapNotNull { m -> m.ageGroupId?.let { it to (m.ageGroupName ?: "U?") } }
        .distinct()
        .sortedBy { it.second }
    val byAge = ageGroup?.let { id -> matches.filter { it.ageGroupId == id } } ?: matches

    val competitionChips = Competitions.chips(byAge, matchTypes)
    val activeChip = Competitions.resolve(competitionChips, savedType, typeChosen)
    val typeDropped = typeChosen && Competitions.savedChoiceUnavailable(competitionChips, savedType)
    val visible = Competitions.filter(byAge, activeChip)

    // Week governs TODAY / UPCOMING / RECENT; NEEDS SCORING keeps the wider
    // look-back so an overdue match cannot hide behind week navigation.
    val inWeek = visible.filter { it.matchDate in week }
    val weekBuckets = MatchBucketing.bucket(inWeek, today)
    val buckets = weekBuckets.copy(needsScoring = MatchBucketing.bucket(visible, today).needsScoring)

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
            // Only worth showing when there is more than one age group to
            // choose between (SB-642).
            if (ageGroupOptions.size > 1) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = ageGroup == null,
                            onClick = {
                                ageGroupTouched = true
                                ageGroup = null
                                scope.launch { container.uiPrefs.setMatchesAgeGroup(null) }
                            },
                            label = { Text("All") },
                        )
                        ageGroupOptions.forEach { (id, name) ->
                            FilterChip(
                                selected = ageGroup == id,
                                onClick = {
                                    ageGroupTouched = true
                                    ageGroup = id
                                    scope.launch { container.uiPrefs.setMatchesAgeGroup(id) }
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                }
            }
            // Match type (SB-681). Chips come from the loaded rows, and the
            // default follows the data — a fixed "League" would show an empty
            // list in a preseason where every fixture is a Friendly.
            // One chip per competition actually present, then the combined
            // "League + Flex", then All — the web's order (SB-1107). A single
            // competition needs no row: there is nothing to choose between.
            if (competitionChips.size > 2) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        competitionChips.forEach { chip ->
                            FilterChip(
                                selected = activeChip.key == chip.key,
                                onClick = {
                                    scope.launch {
                                        container.uiPrefs.setMatchesType(
                                            chip.key.takeUnless { chip.isAll },
                                        )
                                    }
                                },
                                label = { Text("${chip.label} ${chip.count}") },
                            )
                        }
                    }
                }
            }

            // Match of the Week sits above week navigation, as on the web —
            // it belongs to the week being shown, not to the tab (SB-1108).
            motw?.let { pick ->
                item {
                    MotwHero(pick, onOpenMatch = { onOpenMatch(pick.match) })
                }
            }

            // Week navigation, Monday-Sunday, matching the web (SB-681).
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { weekOffset-- }, modifier = Modifier.weight(1f)) {
                        Text("←")
                    }
                    Button(
                        onClick = { weekOffset = 0 },
                        enabled = weekOffset != 0,
                        modifier = Modifier.weight(2f),
                    ) { Text(if (weekOffset == 0) "This week" else "Back to this week") }
                    OutlinedButton(onClick = { weekOffset++ }, modifier = Modifier.weight(1f)) {
                        Text("→")
                    }
                }
            }
            item {
                Text(
                    week.label(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (typeDropped) {
                item {
                    Text(
                        // The key is an id once chips are id-based, so the
                        // message names the week rather than the competition.
                        "That competition has no matches this week — showing all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
            // The hero withholds the fixture until tapped, so the picked row
            // downstairs carries its own marker — otherwise a viewer who
            // never opens the strip never learns which match it was.
            val motwId = motw?.match?.id
            section("LIVE NOW", buckets.live, onOpenMatch, motwId, highlight = true)
            // Above TODAY on purpose: an unscored match from a past date is
            // the thing most likely to need action right now (SB-641).
            section("NEEDS SCORING", buckets.needsScoring, onOpenMatch, motwId, highlight = true)
            section("TODAY", buckets.todays, onOpenMatch, motwId)
            section("UPCOMING", buckets.upcoming, onOpenMatch, motwId)
            section("RECENT", buckets.recent, onOpenMatch, motwId)
            section("POSTPONED / OTHER", buckets.other, onOpenMatch, motwId)
            // An active filter hiding everything must say so — otherwise an
            // empty list reads as "nothing loaded" (SB-642).
            if (buckets.size == 0 && matches.isNotEmpty() && ageGroup != null) {
                item {
                    Text(
                        "No matches for this age group. Tap All to see the other " +
                            "${matches.size} in this window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<MatchSummary>,
    onOpen: (MatchSummary) -> Unit,
    motwId: Int? = null,
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
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (m.id == motwId) {
                            Text(
                                "◆",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        TeamCrest(m.homeTeamClub?.logoUrl, m.homeTeamName, size = 20.dp)
                        TeamCrest(m.awayTeamClub?.logoUrl, m.awayTeamName, size = 20.dp)
                        Text(
                            "${m.homeTeamName} vs ${m.awayTeamName}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (m.homeScore != null && m.awayScore != null) {
                        Text(
                            "${m.homeScore}–${m.awayScore}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        m.matchDate,
                        m.ageGroupName,
                        m.matchTypeName,
                        // League fixtures span leagues and divisions; naming the
                        // competition stops two same-age rows looking identical.
                        m.leagueName?.takeIf { it != "Unknown" },
                        m.divisionName?.takeIf { it != "Unknown" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
