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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.missingtable.scorer.data.api.StandingRow

/**
 * League table (read-only) — server defaults to the admin-set current season
 * when no season_id is passed, so this needs no season logic of its own.
 */
@Composable
fun TableScreen(container: AppContainer) {
    var standings by remember { mutableStateOf<List<StandingRow>?>(null) }
    var ageGroups by remember { mutableStateOf<List<AgeGroupDto>>(emptyList()) }
    var selectedAgeGroup by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { container.api.ageGroups() }.onSuccess { ageGroups = it }
    }

    LaunchedEffect(selectedAgeGroup) {
        standings = null
        error = null
        runCatching { container.api.table(ageGroupId = selectedAgeGroup) }
            .onSuccess { standings = it.standings }
            .onFailure { error = "Couldn't load the table" }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (ageGroups.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedAgeGroup == null,
                    onClick = { selectedAgeGroup = null },
                    label = { Text("All") },
                )
                ageGroups.forEach { ag ->
                    FilterChip(
                        selected = selectedAgeGroup == ag.id,
                        onClick = { selectedAgeGroup = ag.id },
                        label = { Text(ag.name) },
                    )
                }
            }
        }

        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            standings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            standings!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No completed matches yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                HeaderRow()
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(standings!!.withIndex().toList(), key = { it.value.teamId ?: it.index }) { (idx, row) ->
                        StandingLine(position = idx + 1, row = row)
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
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
