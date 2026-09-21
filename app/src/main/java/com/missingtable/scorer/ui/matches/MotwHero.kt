package com.missingtable.scorer.ui.matches

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.data.api.Motw
import com.missingtable.scorer.domain.MotwCopy
import com.missingtable.scorer.ui.common.TeamCrest
import java.time.Instant

/**
 * Match of the Week (SB-1108), the phone's answer to MotwHero.vue.
 *
 * Deliberately a strip and not a billboard. The pick crosses every age group,
 * so it is never the thing the person came to this tab for — it sits above
 * week navigation as one row and only opens when asked.
 *
 * Closed, it withholds the fixture on purpose: one pick a week is small
 * enough to be worth revealing rather than announcing. The cost is real — a
 * viewer who never taps never learns who was picked — which is why the teaser
 * still says there IS a pick, and why the picked row downstairs carries its
 * own marker.
 */
@Composable
fun MotwHero(motw: Motw, onOpenMatch: () -> Unit) {
    var expanded by remember(motw.match.id) { mutableStateOf(false) }
    val match = motw.match
    val accent = MaterialTheme.colorScheme.tertiary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        // Min intrinsic height, so the rail's fillMaxHeight has a height to
        // fill — in a wrap-content Row it resolves to nothing and the rail
        // never draws.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // The rail is the only thing the closed strip and the picked row
            // downstairs have in common — it is how they read as one pick.
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "◆ MATCH OF THE WEEK",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    // The age group rides in the closed strip, not just in the
                    // meta line: this banner renders under every age-group
                    // filter — the pick is global — so a U16 fixture teased
                    // under a U15 filter has to say so before anyone opens it.
                    MotwCopy.ageGroupLabel(match)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        if (expanded) "▴" else "▾",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                    )
                }

                if (!expanded) {
                    Text(
                        MotwCopy.teaser(match),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                AnimatedVisibility(expanded) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            MotwCopy.statusLabel(match, Instant.now()),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clickable { onOpenMatch() },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TeamCrest(match.homeTeamClub?.logoUrl, match.homeTeamName, size = 28.dp)
                            Text(
                                match.homeTeamName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            // A scheduled match must never render 0–0: that is
                            // a result nobody recorded.
                            Text(
                                if (MotwCopy.hasScore(match)) {
                                    "${match.homeScore}–${match.awayScore}"
                                } else {
                                    "vs"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                match.awayTeamName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.End,
                            )
                            TeamCrest(match.awayTeamClub?.logoUrl, match.awayTeamName, size = 28.dp)
                        }
                        MotwCopy.resultLine(match)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            MotwCopy.metaLine(match),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        // The only editorial writing in the product. Absent
                        // renders as absent — a placeholder would frame
                        // nothing.
                        motw.blurb?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
