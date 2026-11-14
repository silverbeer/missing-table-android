package com.missingtable.scorer.ui.matches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.missingtable.scorer.domain.Competitions
import com.missingtable.scorer.domain.Conferences
import com.missingtable.scorer.domain.Programs

/**
 * Everything the filter panel shows and every way it can be changed (SB-1680).
 *
 * The screen owns the state and the persistence; this only lays it out, so the
 * panel can be read top to bottom as the order the filters apply in.
 */
class MatchFilterState(
    val seasons: List<Pair<Int, String>>,
    val seasonId: Int?,
    val ageGroups: List<Pair<Int, String>>,
    val ageGroupId: Int?,
    val programs: List<Programs.Option>,
    val programId: Int?,
    val competitions: List<Competitions.Chip>,
    val competition: Competitions.Chip,
    val conferenceGroups: List<Conferences.Group>,
    val selectedConferences: Set<Int>,
)

class MatchFilterActions(
    val onSeason: (Int) -> Unit,
    val onAgeGroup: (Int?) -> Unit,
    val onProgram: (Int?) -> Unit,
    val onCompetition: (Competitions.Chip) -> Unit,
    /** Replaces the whole selection; a row's "All" clears only that row. */
    val onConferences: (Set<Int>) -> Unit,
    val onReset: () -> Unit,
)

@Composable
fun MatchFilterPanel(
    state: MatchFilterState,
    actions: MatchFilterActions,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // Closed, the header is the summary; open, the rows say it.
                if (open) "Filters" else summary(state),
                style = if (open) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (open) TextButton(onClick = actions.onReset) { Text("Reset") }
            IconButton(onClick = onToggle) {
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide filters" else "Show filters",
                )
            }
        }
        if (!open) return@Card
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            if (state.seasons.isNotEmpty()) {
                FilterRow("Season", scroll = false) {
                    SeasonDropdown(state.seasons, state.seasonId, actions.onSeason)
                }
            }
            // Only worth showing when there is more than one age group to
            // choose between (SB-642).
            if (state.ageGroups.size > 1 || state.ageGroupId != null) {
                FilterRow("Age") {
                    Chip("All", state.ageGroupId == null) { actions.onAgeGroup(null) }
                    state.ageGroups.forEach { (id, name) ->
                        Chip(name, state.ageGroupId == id) { actions.onAgeGroup(id) }
                    }
                }
            }
            // Every program stays on the row even when empty — see Programs.
            // The count says why one is greyed ("Academy 0") without a note.
            if (state.programs.size > 1) {
                FilterRow("Program") {
                    Chip(if (state.programs.size == 2) "Both" else "All", state.programId == null) {
                        actions.onProgram(null)
                    }
                    state.programs.forEach { p ->
                        Chip(
                            "${p.name} ${p.count}",
                            selected = state.programId == p.leagueId,
                            enabled = p.count > 0 || state.programId == p.leagueId,
                        ) { actions.onProgram(p.leagueId) }
                    }
                }
            }
            // Competition (SB-681/SB-1107): League, Flex, League + Flex, All.
            // Shown from two chips, never hidden at one (SB-1110).
            if (state.competitions.size > 1) {
                // Wraps rather than scrolls: League + Flex must never sit
                // off-screen. All first, as on every other row.
                FilterRow("Comp.", wrap = true) {
                    state.competitions.sortedBy { !it.isAll }.forEach { chip ->
                        Chip("${chip.label} ${chip.count}", state.competition.key == chip.key) {
                            actions.onCompetition(chip)
                        }
                    }
                }
            }
            // One row per league, so League and Flex conferences never share
            // a line (SB-1680). A row the competition chip has excluded goes.
            state.conferenceGroups
                .filter { g -> g.conferences.size > 1 || g.conferences.any { it.id in state.selectedConferences } }
                .forEach { group ->
                    val ids = group.conferences.map { it.id }.toSet()
                    val mine = state.selectedConferences intersect ids
                    FilterRow(group.label) {
                        Chip("All", mine.isEmpty()) {
                            actions.onConferences(state.selectedConferences - ids)
                        }
                        group.conferences.forEach { c ->
                            Chip(c.name, c.id in mine) {
                                actions.onConferences(
                                    if (c.id in mine) state.selectedConferences - c.id
                                    else state.selectedConferences + c.id,
                                )
                            }
                        }
                    }
                }
        }
    }
}

/** "‹  Nov 9 – Nov 15, 2026  ›  Today" — one line instead of two (SB-1680). */
@Composable
fun WeekNav(label: String, isThisWeek: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
        }
        TextButton(onClick = onToday, enabled = !isThisWeek) { Text("Today") }
    }
}

private val LabelWidth = 80.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    label: String,
    scroll: Boolean = true,
    wrap: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LabelWidth),
        )
        if (wrap) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
            return@Row
        }
        Row(
            Modifier
                .weight(1f)
                .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

@Composable
private fun SeasonDropdown(options: List<Pair<Int, String>>, selectedId: Int?, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(options.firstOrNull { it.first == selectedId }?.second ?: "Season") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

/** The closed panel's one line: only what is narrowing the list. */
private fun summary(state: MatchFilterState): String {
    val season = state.seasons.firstOrNull { it.first == state.seasonId }?.second
    val age = state.ageGroups.firstOrNull { it.first == state.ageGroupId }?.second ?: "All ages"
    val program = state.programs.firstOrNull { it.leagueId == state.programId }?.name
    val competition = state.competition.label.takeUnless { state.competition.isAll }
    val confs = state.conferenceGroups.flatMap { it.conferences }.filter { it.id in state.selectedConferences }
    val conferences = when {
        confs.isEmpty() -> null
        confs.size <= 2 -> confs.joinToString(", ") { it.name }
        else -> "${confs.size} conferences"
    }
    return listOfNotNull(season, age, program, competition, conferences).joinToString(" · ")
}
