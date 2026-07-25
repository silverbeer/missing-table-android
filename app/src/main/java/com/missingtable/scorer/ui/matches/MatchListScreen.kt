package com.missingtable.scorer.ui.matches

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.MatchSummary
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchListScreen(
    container: AppContainer,
    onOpenMatch: (MatchSummary) -> Unit,
) {
    var matches by remember { mutableStateOf<List<MatchSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var updateAvailable by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // In-app update check (SB-322): the APK is sideloaded, so this banner is
    // the only update channel. version_code comes from R2 object metadata via
    // the backend; null (old backend/release) just means no banner.
    LaunchedEffect(Unit) {
        runCatching { container.api.apkUrl() }.onSuccess { resp ->
            val latest = resp.versionCode ?: return@onSuccess
            updateAvailable = latest > com.missingtable.scorer.BuildConfig.VERSION_CODE
        }
    }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        val today = LocalDate.now()
        runCatching {
            container.api.matches(
                // Wide window so the list isn't empty off-season
                startDate = today.minusDays(60).toString(),
                endDate = today.plusDays(30).toString(),
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
    val live = matches.filter { it.matchStatus == "live" }
    val todays = matches.filter { it.matchStatus == "scheduled" && it.matchDate == today }
    val upcoming = matches.filter { it.matchStatus == "scheduled" && it.matchDate > today }.sortedBy { it.matchDate }
    val recent = matches.filter { it.matchStatus == "completed" }.sortedByDescending { it.matchDate }.take(10)

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
            if (updateAvailable) {
                item {
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Mint a fresh presigned URL at tap time (they
                                // expire in 5 min) and hand it to the browser.
                                scope.launch {
                                    runCatching { container.api.apkUrl() }.onSuccess { resp ->
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(resp.downloadUrl),
                                            )
                                        )
                                    }
                                }
                            },
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
            section("LIVE NOW", live, onOpenMatch, highlight = true)
            section("TODAY", todays, onOpenMatch)
            section("UPCOMING", upcoming, onOpenMatch)
            section("RECENT", recent, onOpenMatch)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<MatchSummary>,
    onOpen: (MatchSummary) -> Unit,
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
                    Text(
                        "${m.homeTeamName} vs ${m.awayTeamName}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (m.homeScore != null && m.awayScore != null) {
                        Text(
                            "${m.homeScore}–${m.awayScore}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    listOfNotNull(m.matchDate, m.ageGroupName, m.matchTypeName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
