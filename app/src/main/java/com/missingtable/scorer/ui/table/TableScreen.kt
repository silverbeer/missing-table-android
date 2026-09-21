package com.missingtable.scorer.ui.table

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
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.AgeGroupDto
import com.missingtable.scorer.data.api.DivisionDto
import com.missingtable.scorer.data.api.LeagueDto
import com.missingtable.scorer.data.api.SeasonDto
import com.missingtable.scorer.data.api.StandingRow
import com.missingtable.scorer.domain.Leagues
import com.missingtable.scorer.domain.Seasons

/**
 * League table with the web's four filters (SB-340), replicating
 * LeagueTable.vue exactly: Age-group pills → League pills → Season dropdown →
 * Division dropdown. Division options are league-scoped (division.league_id);
 * defaults U14 / Homegrown / current season / Northeast. Request params match
 * the web: season_id + age_group_id + division_id (match_type stays the
 * server default "League").
 *
 * The league pills are *divisions*, not every row `/api/leagues` returns —
 * Flex is a competition of Homegrown and Kick Futsal is retired (SB-1106).
 * See [Leagues.divisionChips].
 */
@Composable
fun TableScreen(container: AppContainer) {
    var ageGroups by remember { mutableStateOf<List<AgeGroupDto>>(emptyList()) }
    var leagues by remember { mutableStateOf<List<LeagueDto>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<SeasonDto>>(emptyList()) }
    var allDivisions by remember { mutableStateOf<List<DivisionDto>>(emptyList()) }

    var selectedAgeGroup by remember { mutableStateOf<Int?>(null) }
    var selectedLeague by remember { mutableStateOf<Int?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var selectedDivision by remember { mutableStateOf<Int?>(null) }

    var standings by remember { mutableStateOf<List<StandingRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val divisionOptions = allDivisions.filter { it.leagueId == selectedLeague }

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

    // League drives division options; pick "Northeast" within the league on
    // first load, and auto-select the first division whenever the current one
    // falls outside the newly selected league (web behavior).
    LaunchedEffect(selectedLeague, allDivisions) {
        val options = allDivisions.filter { it.leagueId == selectedLeague }
        if (options.isEmpty()) return@LaunchedEffect
        if (options.none { it.id == selectedDivision }) {
            selectedDivision = (options.firstOrNull { it.name == "Northeast" } ?: options.first()).id
        }
    }

    LaunchedEffect(selectedSeason, selectedAgeGroup, selectedDivision) {
        val season = selectedSeason ?: return@LaunchedEffect
        val ageGroup = selectedAgeGroup ?: return@LaunchedEffect
        val division = selectedDivision ?: return@LaunchedEffect
        standings = null
        error = null
        runCatching {
            container.api.table(seasonId = season, ageGroupId = ageGroup, divisionId = division)
        }
            .onSuccess { standings = it.standings }
            .onFailure { error = "Couldn't load the table" }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (ageGroups.isNotEmpty()) {
            ChipRow(
                options = ageGroups.map { it.id to it.name },
                selectedId = selectedAgeGroup,
                onSelect = { selectedAgeGroup = it },
            )
        }
        if (leagues.isNotEmpty()) {
            ChipRow(
                options = leagues.map { it.id to it.name },
                selectedId = selectedLeague,
                onSelect = { selectedLeague = it },
            )
        }
        Row(
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
                    onSelect = { selectedDivision = it },
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

        when {
            message != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No completed matches yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                HeaderRow()
                HorizontalDivider()
                val numbered = rows.withIndex().toList()
                LazyColumn(Modifier.fillMaxSize()) {
                    // Keys must not collide either: a row with no team_id fell
                    // back to its index, which is the same Int space as a
                    // team_id, and Compose throws the moment the two meet.
                    items(
                        numbered,
                        key = { (idx, row) -> row.teamId?.let { "team-$it" } ?: "row-$idx" },
                    ) { (idx, row) ->
                        StandingLine(position = idx + 1, row = row)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

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

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Cell("#", 28.dp, bold = true)
        Text(
            "Team",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        listOf("P", "W", "D", "L", "GD", "Pts").forEach { Cell(it, 30.dp, bold = true) }
    }
}

@Composable
private fun StandingLine(position: Int, row: StandingRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell("$position", 28.dp)
        Text(
            row.team,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Cell("${row.played}", 30.dp)
        Cell("${row.wins}", 30.dp)
        Cell("${row.draws}", 30.dp)
        Cell("${row.losses}", 30.dp)
        Cell(if (row.goalDifference > 0) "+${row.goalDifference}" else "${row.goalDifference}", 30.dp)
        Cell("${row.points}", 30.dp, bold = true)
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
    )
}
