package com.missingtable.scorer.ui.table

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.missingtable.scorer.domain.StandingsSort
import com.missingtable.scorer.ui.common.TeamCrest
import com.missingtable.scorer.ui.common.findActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.AgeGroupDto
import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.SeasonDto
import com.missingtable.scorer.data.api.MatchTypeDto
import com.missingtable.scorer.data.api.StandingRow
import com.missingtable.scorer.data.api.TableCoverage
import com.missingtable.scorer.domain.TableCompetitions
import kotlinx.coroutines.CancellationException
import com.missingtable.scorer.domain.Leagues
import com.missingtable.scorer.domain.Seasons
import com.missingtable.scorer.domain.StandingsFormat
import com.missingtable.scorer.ui.team.TeamLink
import com.missingtable.scorer.ui.team.teamSubtitle

/**
 * League table with the web's four filters (SB-340), replicating
 * LeagueTable.vue exactly: Age-group pills → League pills → Season dropdown →
 * Division dropdown, plus the Competition row (SB-1596): League, Flex and the
 * combined "League + Flex", read from the API for the division — see
 * [TableCompetitions]. Conferences follow the competition (Flex has its own)
 * via `/api/divisions/available`; defaults U14 / Homegrown / current season /
 * the division's own competition / Northeast. `match_type` is always sent,
 * as the web does.
 *
 * The league pills are *divisions*, not every row `/api/leagues` returns —
 * Flex is a competition of Homegrown and Kick Futsal is retired (SB-1106).
 * See [Leagues.divisionChips].
 */
@Composable
fun TableScreen(
    container: AppContainer,
    // A row opens its team at the table's age group (SB-1464, SB-1645).
    onOpenTeam: (TeamLink) -> Unit = {},
) {
    var ageGroups by remember { mutableStateOf<List<AgeGroupDto>>(emptyList()) }
    var leagues by remember { mutableStateOf<List<LeagueDto>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<SeasonDto>>(emptyList()) }
    var allDivisions by remember { mutableStateOf<List<DivisionDto>>(emptyList()) }

    var selectedAgeGroup by remember { mutableStateOf<Int?>(null) }
    var selectedLeague by remember { mutableStateOf<Int?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var selectedDivision by remember { mutableStateOf<Int?>(null) }

    // Competition (SB-1596): what this division plays at this age group, and
    // the chosen one as `/api/table` takes it ("League", "Flex", "qualifying").
    var competitions by remember { mutableStateOf<List<MatchTypeDto>>(emptyList()) }
    var selectedCompetition by remember { mutableStateOf<String?>(null) }
    // Conferences for the competition; null until read (or when the read
    // failed, which falls back to the division's static list).
    var conferences by remember { mutableStateOf<List<DivisionDto>?>(null) }
    // What stage 1 last settled, and what stage 2 last settled — see below.
    var competitionScope by remember { mutableStateOf<TableScope?>(null) }
    var tableKey by remember { mutableStateOf<TableKey?>(null) }

    var standings by remember { mutableStateOf<List<StandingRow>?>(null) }
    // Column sort (SB-1651); null = standings order. Kept across filter changes, as on the web.
    var sort by remember { mutableStateOf<StandingsSort.Sort?>(null) }
    var coverage by remember { mutableStateOf<TableCoverage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val divisionOptions = conferences ?: allDivisions.filter { it.leagueId == selectedLeague }
    val competitionChips = TableCompetitions.chips(competitions)

    LaunchedEffect(Unit) {
        runCatching { container.api.ageGroups() }.onSuccess { list ->
            ageGroups = list
            if (selectedAgeGroup == null) {
                selectedAgeGroup = (list.firstOrNull { it.name == "U14" } ?: list.firstOrNull())?.id
            }
        }
        runCatching { container.api.leagues() }.onSuccess { list ->
            // Chips are divisions, so Flex (a competition of Homegrown) and
            // retired leagues never become one — see Leagues.divisionChips.
            leagues = Leagues.divisionChips(list)
            if (selectedLeague == null) selectedLeague = Leagues.defaultChip(list)?.id
        }
        runCatching { container.api.seasons() }.onSuccess { list ->
            seasons = list
            if (selectedSeason == null) selectedSeason = Seasons.pickCurrent(list)?.id
        }
        runCatching { container.api.divisions() }.onSuccess { allDivisions = it }
    }

    // One pipeline, each stage keyed only on what the stage before it settled
    // (SB-1596): competitions → conferences → table. Keyed on the raw
    // selections instead, every stage raced the one before it — a table asked
    // for with the new competition and the old competition's conference, a
    // conference list asked for with the old competition.

    // 1. Division, season or age group → which competitions are played.
    // Homegrown at U15 is League and Flex; at U13 League alone, so no row
    // shows. A choice this selection doesn't play falls back to the division's
    // own.
    LaunchedEffect(selectedLeague, selectedSeason, selectedAgeGroup) {
        val league = selectedLeague ?: return@LaunchedEffect
        val season = selectedSeason ?: return@LaunchedEffect
        val ageGroup = selectedAgeGroup ?: return@LaunchedEffect
        val list = try {
            container.api.availableMatchTypes(seasonId = season, ageGroupId = ageGroup, leagueId = league)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        competitions = list
        selectedCompetition = TableCompetitions.resolve(list, selectedCompetition)
        competitionScope = TableScope(league, season, ageGroup)
    }

    // 2. Competition → its conferences (Flex has its own). Keep the current
    // one when still offered, else Northeast, else the first. A failed read
    // falls back to the division's static list, as before SB-1596.
    LaunchedEffect(competitionScope, selectedCompetition) {
        val scope = competitionScope ?: return@LaunchedEffect
        val competition = selectedCompetition ?: return@LaunchedEffect
        val read = try {
            container.api.availableDivisions(
                leagueId = scope.league,
                seasonId = scope.season,
                ageGroupId = scope.ageGroup,
                matchType = TableCompetitions.conferencesMatchType(competition),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        conferences = read
        val options = read ?: allDivisions.filter { it.leagueId == scope.league }
        val division = TableCompetitions.resolveConference(options.map { it.id to it.name }, selectedDivision)
        selectedDivision = division
        if (division == null) {
            // No conference has fixtures here: say so rather than keep the
            // last selection's table on screen, or spin forever.
            tableKey = null
            standings = emptyList()
            coverage = null
            error = null
        } else {
            tableKey = TableKey(scope.season, scope.ageGroup, division, competition)
        }
    }

    // 3. The table. match_type is always sent: the API defaults to League, and
    // a Flex conference asked for League renders an empty table (SB-835).
    LaunchedEffect(tableKey) {
        val key = tableKey ?: return@LaunchedEffect
        standings = null
        coverage = null
        error = null
        try {
            val response = container.api.table(
                seasonId = key.season, ageGroupId = key.ageGroup, divisionId = key.division, matchType = key.competition,
            )
            standings = response.standings
            coverage = response.coverage
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't load the table"
        }
    }

    // Landscape (SB-1651): the Table alone may rotate, and on its side the
    // short screen goes to the table: filters fold into a one-line summary.
    UnlockRotationWhileShown()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var filtersOpen by remember { mutableStateOf(false) }
    val showFilters = !landscape || filtersOpen

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (landscape) {
            val summary = listOfNotNull(
                ageGroups.firstOrNull { it.id == selectedAgeGroup }?.name,
                leagues.firstOrNull { it.id == selectedLeague }?.name,
                competitionChips.firstOrNull { it.value == selectedCompetition }?.label,
                divisionOptions.firstOrNull { it.id == selectedDivision }?.name,
            ).joinToString(" · ")
            TextButton(onClick = { filtersOpen = !filtersOpen }) {
                Text(if (filtersOpen) "Hide filters ▲" else "${summary.ifEmpty { "Filters" }} ▼")
            }
        }
        if (showFilters && ageGroups.isNotEmpty()) {
            ChipRow(
                options = ageGroups.map { it.id to it.name },
                selectedId = selectedAgeGroup,
                onSelect = { selectedAgeGroup = it },
            )
        }
        if (showFilters && leagues.isNotEmpty()) {
            ChipRow(
                options = leagues.map { it.id to it.name },
                selectedId = selectedLeague,
                onSelect = { selectedLeague = it },
            )
        }
        // Only when there's a choice: U13 Homegrown plays League alone.
        if (showFilters && competitionChips.size > 1) {
            val indexed = competitionChips.withIndex().toList()
            ChipRow(
                options = indexed.map { (i, chip) -> i to chip.label },
                selectedId = indexed.firstOrNull { it.value.value == selectedCompetition }?.index,
                onSelect = { i -> selectedCompetition = competitionChips[i].value },
            )
        }
        if (showFilters) Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (seasons.isNotEmpty()) {
                PickerDropdown(
                    options = seasons.map { it.id to (it.name ?: "Season ${it.id}") },
                    selectedId = selectedSeason,
                    placeholder = "Season",
                    onSelect = { selectedSeason = it },
                )
            }
            if (divisionOptions.isNotEmpty()) {
                PickerDropdown(
                    options = divisionOptions.map { it.id to it.name },
                    selectedId = selectedDivision,
                    placeholder = "Division",
                    onSelect = {
                        selectedDivision = it
                        tableKey = tableKey?.copy(division = it)
                    },
                )
            }
        }

        // Read the state ONCE into locals. `standings!!` inside the LazyColumn
        // used to crash the app (SB-1106): the `when` guards that it is
        // non-null, but LazyColumn does not build its interval content there
        // and then — it builds it from a snapshot observer, after the branch
        // was chosen. Switching league or division sets `standings` back to
        // null in between, and the `!!` ran on the new value.
        val rows = standings
        val message = error

        // Captions (SB-1043, SB-1027): what a combined view counted, and why
        // a Flex line can read 1W 2D = 7.
        listOf(TableCompetitions.coverageNote(coverage), TableCompetitions.shootoutNote(coverage))
            .filter { it.isNotEmpty() && !rows.isNullOrEmpty() }
            .forEach { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

        when {
            message != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(TableCompetitions.emptyNote(coverage), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                // One scroll state for the header and every row, so the stat
                // columns move together and stay aligned (SB-1623).
                val statsScroll = rememberScrollState()
                val teamWidth = if (landscape) TeamWidthWide else TeamWidth
                HeaderRow(statsScroll, sort, teamWidth) { key -> sort = StandingsSort.next(sort, key) }
                HorizontalDivider()
                // League position survives sorting (SB-1651): `index` is the standings row.
                val numbered = StandingsSort.apply(rows, sort, sort?.let { sortValueFor(it.key) } ?: { null })
                // A re-sort must start at the top: the list otherwise keeps the
                // old first row anchored, hiding whatever now sorts above it.
                val listState = rememberLazyListState()
                LaunchedEffect(sort) { listState.scrollToItem(0) }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    // Keys must not collide either: a row with no team_id fell
                    // back to its index, which is the same Int space as a
                    // team_id, and Compose throws the moment the two meet.
                    items(
                        numbered,
                        key = { (idx, row) -> row.teamId?.let { "team-$it" } ?: "row-$idx" },
                    ) { (idx, row) ->
                        StandingLine(position = idx + 1, row = row, statsScroll = statsScroll, sort = sort, teamWidth = teamWidth) { teamId ->
                            val sub = teamSubtitle(
                                row.team,
                                null,
                                leagues.firstOrNull { it.id == selectedLeague }?.name,
                                divisionOptions.firstOrNull { it.id == selectedDivision }?.name,
                            )
                            onOpenTeam(
                                TeamLink(
                                    teamId = teamId,
                                    name = row.team,
                                    subtitle = sub,
                                    ageGroupId = selectedAgeGroup,
                                    ageGroupName = ageGroups.firstOrNull { it.id == selectedAgeGroup }?.name,
                                ),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

private data class TableScope(val league: Int, val season: Int, val ageGroup: Int)

private data class TableKey(val season: Int, val ageGroup: Int, val division: Int, val competition: String)

@Composable
private fun ChipRow(
    options: List<Pair<Int, String>>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, name) ->
            FilterChip(
                selected = selectedId == id,
                onClick = { onSelect(id) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun PickerDropdown(
    options: List<Pair<Int, String>>,
    selectedId: Int?,
    placeholder: String,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(options.firstOrNull { it.first == selectedId }?.second ?: placeholder)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

// Pinned on the left; the stat columns scroll horizontally beside them.
private val PositionWidth = 28.dp
private val TeamWidth = 150.dp
private val TeamWidthWide = 220.dp
private val CrestSize = 18.dp
private val MoveWidth = 40.dp
private val FormDot = 18.dp

private val WinGreen = Color(0xFF22C55E)
private val LossRed = Color(0xFFEF4444)
private val DrawGrey = Color(0xFF9CA3AF)

/**
 * One stat column (SB-1623). Same order as the web: GP, Pts and PPM first so
 * a phone shows why a team ranks where it does (9 pts from 4 = 2.25 sits
 * above 11 from 5 = 2.20) without scrolling. PPM is the ranking column.
 * Tapping a header sorts by [sortValue] (SB-1651).
 */
private class StatColumn(
    val label: String,
    val width: Dp,
    val weight: FontWeight = FontWeight.Normal,
    val accent: Boolean = false,
    val sortValue: (StandingRow) -> Double?,
    val value: (StandingRow) -> String,
)

private val StatColumns = listOf(
    StatColumn("GP", 34.dp, sortValue = { it.played.toDouble() }) { "${it.played}" },
    StatColumn("PTS", 36.dp, FontWeight.Bold, accent = true, sortValue = { it.points.toDouble() }) { "${it.points}" },
    StatColumn("PPM", 46.dp, FontWeight.SemiBold, sortValue = { it.pointsPerMatch }) {
        StandingsFormat.perMatch(it.pointsPerMatch)
    },
    StatColumn("W", 30.dp, sortValue = { it.wins.toDouble() }) { "${it.wins}" },
    StatColumn("D", 30.dp, sortValue = { it.draws.toDouble() }) { "${it.draws}" },
    StatColumn("L", 30.dp, sortValue = { it.losses.toDouble() }) { "${it.losses}" },
    StatColumn("GF", 32.dp, sortValue = { it.goalsFor.toDouble() }) { "${it.goalsFor}" },
    StatColumn("GA", 32.dp, sortValue = { it.goalsAgainst.toDouble() }) { "${it.goalsAgainst}" },
    StatColumn("GD", 38.dp, sortValue = { it.goalDifference.toDouble() }) {
        StandingsFormat.goalDifference(it.goalDifference)
    },
    StatColumn("GD/M", 50.dp, sortValue = { it.gdPerMatch }) { StandingsFormat.signedPerMatch(it.gdPerMatch) },
    StatColumn("GF/M", 46.dp, sortValue = { it.gfPerMatch }) { StandingsFormat.perMatch(it.gfPerMatch) },
    StatColumn("GA/M", 46.dp, sortValue = { it.gaPerMatch }) { StandingsFormat.perMatch(it.gaPerMatch) },
)

private fun sortValueFor(key: String): (StandingRow) -> Double? =
    StatColumns.firstOrNull { it.label == key }?.sortValue ?: { null }

/** Last five results, five dots wide (SB-1651). */
private val FormWidth = (FormDot + 3.dp) * 5 + 8.dp

@Composable
private fun HeaderRow(
    statsScroll: ScrollState,
    sort: StandingsSort.Sort?,
    teamWidth: Dp,
    onSort: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cell("#", PositionWidth, FontWeight.Bold)
        Text(
            "TEAM",
            modifier = Modifier.width(teamWidth).padding(end = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(Modifier.weight(1f).horizontalScroll(statsScroll), verticalAlignment = Alignment.CenterVertically) {
            Cell("+/-", MoveWidth, FontWeight.Bold)
            StatColumns.forEach { col ->
                val caret = when {
                    sort?.key != col.label -> ""
                    sort.descending -> "▼"
                    else -> "▲"
                }
                Text(
                    col.label + caret,
                    modifier = Modifier
                        .width(col.width + if (caret.isEmpty()) 0.dp else 10.dp)
                        .clickable { onSort(col.label) }
                        .padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = if (caret.isEmpty()) Color.Unspecified else MaterialTheme.colorScheme.primary,
                )
            }
            Cell("FORM", FormWidth, FontWeight.Bold)
        }
    }
}

@Composable
private fun StandingLine(
    position: Int,
    row: StandingRow,
    statsScroll: ScrollState,
    sort: StandingsSort.Sort?,
    teamWidth: Dp,
    onOpenTeam: (Int) -> Unit,
) {
    val teamId = row.teamId
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (teamId != null) Modifier.clickable { onOpenTeam(teamId) } else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell("$position", PositionWidth)
        Row(
            Modifier.width(teamWidth).padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamCrest(row.logoUrl, row.team, size = CrestSize)
            Spacer(Modifier.width(6.dp))
            Text(
                row.team,
                style = MaterialTheme.typography.bodyMedium,
                color = if (teamId != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.weight(1f).horizontalScroll(statsScroll), verticalAlignment = Alignment.CenterVertically) {
            val move = row.positionChange
            Text(
                StandingsFormat.movement(move),
                modifier = Modifier.width(MoveWidth),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (move != 0) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = when {
                    move > 0 -> WinGreen
                    move < 0 -> LossRed
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            StatColumns.forEach { col ->
                val caretPad = if (sort?.key == col.label) 10.dp else 0.dp
                Text(
                    col.value(row),
                    modifier = Modifier.width(col.width + caretPad),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = col.weight,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = if (col.accent) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
            }
            FormDots(row.form)
        }
    }
}

/** Web's form column: a coloured dot per result, oldest first (SB-1651). */
@Composable
private fun FormDots(form: List<String>) {
    Row(
        Modifier.width(FormWidth),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
    ) {
        form.takeLast(5).forEach { r ->
            val bg = when (r) {
                "W" -> WinGreen
                "L" -> LossRed
                else -> DrawGrey
            }
            Box(
                Modifier.size(FormDot).clip(CircleShape).background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(r, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, fontWeight: FontWeight = FontWeight.Normal) {
    Text(
        text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = fontWeight,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/**
 * The app is portrait-only, but the Table is wide (SB-1651): while it is on
 * screen the phone may turn, following the user's own auto-rotate setting,
 * and leaving it puts the app back to portrait. The activity handles the
 * rotation itself (manifest configChanges), so no filter or sort is lost.
 */
@Composable
private fun UnlockRotationWhileShown() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
}
