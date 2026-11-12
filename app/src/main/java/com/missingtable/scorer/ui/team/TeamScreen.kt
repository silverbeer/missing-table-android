package com.missingtable.scorer.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer

/**
 * A team, minimally (SB-1464): who it is and a Follow button. There is no
 * team-detail endpoint, so the name and subtitle arrive with the navigation;
 * a followed team's embed fills in whatever the caller did not know.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    container: AppContainer,
    teamId: Int,
    name: String?,
    subtitle: String?,
    onBack: () -> Unit,
    ageGroupId: Int? = null,
    ageGroupName: String? = null,
) {
    val follows by container.teamFollows.follows.collectAsState()
    val embed = follows.firstOrNull { it.teamId == teamId }?.team
    val title = name ?: embed?.name ?: "Team"
    val sub = subtitle ?: embed?.subtitle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            sub?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            FollowButton(container, teamId, ageGroupId)
            Text(
                followHint(ageGroupId, ageGroupName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Under the Follow button: says which age groups a follow here covers (SB-1645). */
internal fun followHint(ageGroupId: Int?, ageGroupName: String?): String = when {
    ageGroupId == null -> "Follow to get kickoff, goals and results for every age group of this team."
    !ageGroupName.isNullOrBlank() -> "Follow to get kickoff, goals and results for this team's $ageGroupName matches."
    else -> "Follow to get kickoff, goals and results for this age group of the team."
}
