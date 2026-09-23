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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.MatchSummary
import com.missingtable.scorer.data.api.MatchTypeDto
import com.missingtable.scorer.data.api.Motw
import com.missingtable.scorer.ui.common.TeamCrest
import com.missingtable.scorer.domain.Competitions
import com.missingtable.scorer.domain.Conferences
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
    var divisions by remember { mutableStateOf<List<DivisionDto>>(emptyList()) }
    var leagues by remember { mutableStateOf<List<LeagueDto>>(emptyList()) }
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
    val savedConferences by container.uiPrefs.matchesConferences.collectAsState(initial = emptySet())
    var weekOffset by remember { mutableIntStateOf(0) }
    // Closed by default (SB-1118); survives rotation, resets with the session.
    var needsScoringExpanded by rememberSaveable { mutableStateOf(false) }
    var ageGroupTouched by remember { mutableStateOf(false) }
    var ageGroup by remember { mutableStateOf<Int?>(null) }
    // Adopt the persisted choice once, on first emission, then let taps win.
    LaunchedEffect(savedAgeGroup) {
        if (!ageGroupTouched) ageGroup = savedAgeGroup
    }

    // Competition metadata changes about once a season; a failure here is not
    // an error state — the chips fall back to the names the rows carry.
    // Divisions and leagues come along for the conference filter, which needs
    // each conference's league to know which competition it groups under.
    LaunchedEffect(Unit) {
        runCatching { container.api.matchTypes() }.onSuccess { matchTypes = it }
        runCatching { container.api.divisions() }.onSuccess { divisions = it }
        runCatching { container.api.leagues() }.onSuccess { leagues = it }
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

    // Conferences come from what this age group plays, so switching age group
    // re-offers them — and prunes a selection the new group does not play,
    // which would otherwise empty the list with no selected chip to explain it.
    val conferences = Conferences.visible(byAge, divisions)
    val conferenceGroups = Conferences.groups(conferences, leagues, matchTypes)
    val activeConferences = Conferences.prune(savedConferences, conferences)
    LaunchedEffect(activeConferences, savedConferences) {
        if (activeConferences != savedConferences && conferences.isNotEmpty()) {
            container.uiPrefs.setMatchesConferences(activeConferences)
        }
    }

    // Conference narrows BEFORE the competition chips are built, so their
    // counts describe the list underneath rather than contradicting it — the
    // same order the web applies (matchesBeforeTypeFilter, SB-1107).
    val byConference = Conferences.filter(byAge, activeConferences)

    val competitionChips = Competitions.chips(byConference, matchTypes)
    val activeChip = Competitions.resolve(competitionChips, savedType, typeChosen)
    val typeDropped = typeChosen && Competitions.savedChoiceUnavailable(competitionChips, savedType)
    val visible = Competitions.filter(byConference, activeChip)

    // Every section belongs to the week on screen, NEEDS SCORING included
    // (SB-1118). It used to keep the wider 60-day look-back so an overdue
    // match could not hide behind week navigation (SB-641) — but a row from
    // another week, sitting under a header that names this one, cost more
    // trust than the reminder was worth. It is reached by navigating to its
    // week, which is what the week control is for.
    val inWeek = visible.filter { it.matchDate in week }
    val buckets = MatchBucketing.bucket(inWeek, today)

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
            // "League + Flex", then All — the web's order (SB-1107).
            //
            // Shown from two chips, as the web does, not three. Hiding the row
            // at one competition made the control vanish the moment a
            // conference selection narrowed to a single competition — leaving
            // a dropped choice, an explanation, and no way to act on either
            // (SB-1110).
            if (competitionChips.size > 1) {
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

            // Conference (SB-1110). Multi-select, because conferences are
            // neighbours rather than alternatives — a club near a border plays
            // three of them in one season. "All" is the empty selection, so it
            // clears rather than adding a fourth value.
            if (conferences.size > 1) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = activeConferences.isEmpty(),
                            onClick = { scope.launch { container.uiPrefs.setMatchesConferences(emptySet()) } },
                            label = { Text("All conferences") },
                        )
                        conferenceGroups.forEach { group ->
                            // The heading disambiguates same-named conferences
                            // across competitions — Florida exists under both
                            // League and Flex, as different rows (SB-1040).
                            if (conferenceGroups.size > 1) {
                                Text(
                                    group.label.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            }
                            group.conferences.forEach { conference ->
                                FilterChip(
                                    selected = conference.id in activeConferences,
                                    onClick = {
                                        val next = if (conference.id in activeConferences) {
                                            activeConferences - conference.id
                                        } else {
                                            activeConferences + conference.id
                                        }
                                        scope.launch { container.uiPrefs.setMatchesConferences(next) }
                                    },
                                    label = { Text(conference.name) },
                                )
                            }
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
                        // message cannot name the competition. It cannot name
                        // the week either: a conference selection is just as
                        // likely to be what removed it (SB-1110).
                        "No matches for that competition in this selection — showing all.",
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
            // Still above TODAY, because an unscored match is the thing most
            // likely to need action (SB-641) — but collapsed, because this tab
            // is opened to read upcoming fixtures and scores far more often
            // than to clear a backlog (SB-1118). The count in the header is
            // what keeps a closed section honest about having something in it.
            section(
                "NEEDS SCORING",
                buckets.needsScoring,
                onOpenMatch,
                motwId,
                highlight = true,
                collapsed = !needsScoringExpanded,
                onToggle = { needsScoringExpanded = !needsScoringExpanded },
            )
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

/**
 * @param collapsed hides the rows but keeps the header, which then carries the
 *   count — a closed section that does not say how much it is hiding is worse
 *   than no section.
 * @param onToggle null for a section that is always open.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<MatchSummary>,
    onOpen: (MatchSummary) -> Unit,
    motwId: Int? = null,
    highlight: Boolean = false,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    // An empty week renders no header at all, collapsible or not.
    if (items.isEmpty()) return
    item {
        val color =
            if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier)
                .padding(top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (onToggle != null) "$title (${items.size})" else title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            if (onToggle != null) {
                Text(
                    if (collapsed) "▾" else "▴",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
        }
    }
    if (collapsed) return
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
