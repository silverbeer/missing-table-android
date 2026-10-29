package com.missingtable.scorer.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.MatchPreviewResponse
import com.missingtable.scorer.data.api.PreviewMatch
import com.missingtable.scorer.domain.MatchPreviewModel
import com.missingtable.scorer.domain.MatchPreviewModel.Outcome
import com.missingtable.scorer.domain.MatchPreviewModel.Tab as PreviewTab

private val WinGreen = Color(0xFF16A34A)

/**
 * The fan's view of a fixture (SB-1456), ported from MatchPreview.vue:
 * recent form for both sides, common opponents, and head-to-head history.
 *
 * Self-contained so the Match Detail screen can drop it in — it fetches on its
 * own and owns its loading/error/retry states. It is plain Column/Row, never a
 * LazyColumn, because the host scrolls and nested vertical scrollers crash.
 *
 * @param headToHeadOnly for a live or completed match, where "how have they
 *   been playing lately" no longer matters but past meetings still do: only
 *   the Head-to-Head panel, no tab row.
 */
@Composable
fun MatchPreviewSection(
    container: AppContainer,
    homeTeamId: Int,
    homeTeamName: String,
    awayTeamId: Int,
    awayTeamName: String,
    seasonId: Int?,
    ageGroupId: Int?,
    matchTypeId: Int?,
    headToHeadOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var preview by remember { mutableStateOf<MatchPreviewResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(PreviewTab.FORM) }
    // Bumped by Retry; part of the fetch key so the same inputs refetch.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(homeTeamId, awayTeamId, seasonId, ageGroupId, matchTypeId, attempt) {
        preview = null
        error = null
        runCatching {
            container.api.matchPreview(
                homeTeamId = homeTeamId,
                awayTeamId = awayTeamId,
                seasonId = seasonId,
                ageGroupId = ageGroupId,
                matchTypeId = matchTypeId,
            )
        }
            .onSuccess {
                selected = MatchPreviewModel.defaultTab(it)
                preview = it
            }
            .onFailure { error = "Couldn't load the match preview" }
    }

    val data = preview
    val message = error
    Column(modifier.fillMaxWidth()) {
        when {
            message != null -> Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { attempt++ }) { Text("Retry") }
            }
            data == null -> Box(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            headToHeadOnly -> HeadToHeadPanel(data, homeTeamId, homeTeamName, awayTeamName)
            else -> {
                val tabs = MatchPreviewModel.tabs(data)
                val active = MatchPreviewModel.resolveTab(data, selected)
                ScrollableTabRow(
                    selectedTabIndex = tabs.indexOfFirst { it.tab == active }.coerceAtLeast(0),
                    edgePadding = 0.dp,
                ) {
                    tabs.forEach { spec ->
                        Tab(
                            selected = spec.tab == active,
                            onClick = { selected = spec.tab },
                            text = {
                                Text(spec.count?.let { "${spec.tab.label} ($it)" } ?: spec.tab.label)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (active) {
                    PreviewTab.FORM -> FormPanel(data, homeTeamId, homeTeamName, awayTeamId, awayTeamName)
                    PreviewTab.COMMON -> CommonPanel(data, homeTeamId, homeTeamName, awayTeamId, awayTeamName)
                    PreviewTab.HEAD_TO_HEAD -> HeadToHeadPanel(data, homeTeamId, homeTeamName, awayTeamName)
                }
            }
        }
    }
}

// ── Recent Form ───────────────────────────────────────────────────────────────

@Composable
private fun FormPanel(
    data: MatchPreviewResponse,
    homeTeamId: Int,
    homeTeamName: String,
    awayTeamId: Int,
    awayTeamName: String,
) {
    if (data.homeTeamRecent.isEmpty() && data.awayTeamRecent.isEmpty()) {
        EmptyPanel("No recent form data available.")
        return
    }
    // Stacked, not the web's two columns: each row carries six pieces of
    // information, and half a phone width truncates the opponent to nothing.
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FormColumn(
            teamName = homeTeamName,
            teamId = homeTeamId,
            matches = data.homeTeamRecent,
            qopRank = data.homeQopRank.takeIf { MatchPreviewModel.showsQopRank(data.hasQopData, it) },
            qopChange = data.homeQopRankChange,
        )
        FormColumn(
            teamName = awayTeamName,
            teamId = awayTeamId,
            matches = data.awayTeamRecent,
            qopRank = data.awayQopRank.takeIf { MatchPreviewModel.showsQopRank(data.hasQopData, it) },
            qopChange = data.awayQopRankChange,
        )
    }
}

@Composable
private fun FormColumn(
    teamName: String,
    teamId: Int,
    matches: List<PreviewMatch>,
    qopRank: Int?,
    qopChange: Int?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                teamName.uppercase(),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (qopRank != null) QopRank(qopRank, qopChange)
        }
        if (matches.isEmpty()) {
            NoMatchesOnRecord()
        } else {
            matches.forEach { m ->
                ResultRow(
                    match = m,
                    teamId = teamId,
                    label = MatchPreviewModel.opponentName(m, teamId),
                    competition = m.matchTypeName?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}

@Composable
private fun QopRank(rank: Int, change: Int?) {
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("QoP #$rank", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MatchPreviewModel.rankMove(change)?.let { move ->
            Text(
                move.label,
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (move.up) WinGreen else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NoMatchesOnRecord() {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No matches on record", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "We haven't tracked this club's results yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Common Opponents ──────────────────────────────────────────────────────────

@Composable
private fun CommonPanel(
    data: MatchPreviewResponse,
    homeTeamId: Int,
    homeTeamName: String,
    awayTeamId: Int,
    awayTeamName: String,
) {
    if (data.commonOpponents.isEmpty()) {
        EmptyPanel("No common opponents found this season.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        data.commonOpponents.forEach { opp ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "VS ${opp.opponentName.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // Home side's results first, then the away side's, each
                // labelled with the previewed team so the pair reads as a
                // comparison against the same opponent.
                opp.homeTeamMatches.forEach { m ->
                    ResultRow(
                        match = m,
                        teamId = homeTeamId,
                        label = homeTeamName,
                        competition = MatchPreviewModel.exceptionalCompetition(m.matchTypeName),
                    )
                }
                opp.awayTeamMatches.forEach { m ->
                    ResultRow(
                        match = m,
                        teamId = awayTeamId,
                        label = awayTeamName,
                        competition = MatchPreviewModel.exceptionalCompetition(m.matchTypeName),
                        muted = true,
                    )
                }
                if (opp.homeTeamMatches.isEmpty() && opp.awayTeamMatches.isEmpty()) {
                    Text(
                        "No match data",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Head-to-Head ──────────────────────────────────────────────────────────────

@Composable
private fun HeadToHeadPanel(
    data: MatchPreviewResponse,
    homeTeamId: Int,
    homeTeamName: String,
    awayTeamName: String,
) {
    if (data.headToHead.isEmpty()) {
        EmptyPanel("No previous meetings found.")
        return
    }
    val tally = MatchPreviewModel.headToHeadTally(data.headToHead, homeTeamId)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            TallyCell(tally.homeWins, homeTeamName, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            TallyCell(tally.draws, "Draws", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            TallyCell(tally.awayWins, awayTeamName, MaterialTheme.colorScheme.error, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        data.headToHead.forEach { HeadToHeadRow(it) }
    }
}

@Composable
private fun TallyCell(value: Int, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeadToHeadRow(m: PreviewMatch) {
    RowCard {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SideName(m.homeTeamName, MatchPreviewModel.outcome(m, m.homeTeamId), TextAlign.End, Modifier.weight(1f))
                Text(
                    "${m.homeScore ?: "-"} – ${m.awayScore ?: "-"}",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                SideName(m.awayTeamName, MatchPreviewModel.outcome(m, m.awayTeamId), TextAlign.Start, Modifier.weight(1f))
            }
            // Season and date on their own line: on a phone they would
            // otherwise squeeze both club names to a couple of letters.
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    listOfNotNull(MatchPreviewModel.longDate(m.matchDate).ifBlank { null }, m.seasonName)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MatchPreviewModel.exceptionalCompetition(m.matchTypeName)?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

/** The winner bold, the loser muted, a draw (or unscored meeting) plain. */
@Composable
private fun SideName(name: String, outcome: Outcome, align: TextAlign, modifier: Modifier) {
    Text(
        name,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (outcome == Outcome.WIN) FontWeight.Bold else FontWeight.Normal,
        color = if (outcome == Outcome.LOSS) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── Shared ────────────────────────────────────────────────────────────────────

/**
 * One result read from [teamId]'s side: W/D/L chip, own-score-first
 * scoreline, H/A, [label], an optional competition badge, and the date.
 */
@Composable
private fun ResultRow(
    match: PreviewMatch,
    teamId: Int,
    label: String,
    competition: String?,
    muted: Boolean = false,
) {
    val outcome = MatchPreviewModel.outcome(match, teamId)
    RowCard(muted = muted) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutcomeChip(outcome)
            Text(
                MatchPreviewModel.scoreline(match, teamId) ?: "—",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                MatchPreviewModel.venue(match, teamId),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (competition != null) CompetitionBadge(competition)
            Text(
                MatchPreviewModel.shortDate(match.matchDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeChip(outcome: Outcome) {
    val (bg, fg) = when (outcome) {
        Outcome.WIN -> WinGreen to Color.White
        Outcome.LOSS -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        Outcome.DRAW, Outcome.UNKNOWN ->
            MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier.width(22.dp).background(bg, RoundedCornerShape(3.dp)).padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(outcome.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** League reads neutral; anything else (Flex, a tournament) stands out. */
@Composable
private fun CompetitionBadge(name: String) {
    val league = MatchPreviewModel.isLeague(name)
    Text(
        name,
        modifier = Modifier
            .background(
                if (league) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiaryContainer,
                RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (league) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiaryContainer,
        maxLines = 1,
    )
}

@Composable
private fun RowCard(muted: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (muted) 0.5f else 1f),
                shape,
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) { content() }
}

@Composable
private fun EmptyPanel(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
