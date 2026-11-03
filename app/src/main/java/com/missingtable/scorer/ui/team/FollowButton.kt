package com.missingtable.scorer.ui.team

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.FollowTeam
import com.missingtable.scorer.ui.common.rememberNotifPermissionGate
import kotlinx.coroutines.launch

/**
 * Follow / Following toggle for one team (SB-1464). Reads and writes the
 * shared follows cache, so every button for this team flips together; the
 * flip is optimistic and rolls back if the server refuses. The first follow
 * is where notification permission gets asked for.
 */
@Composable
fun FollowButton(container: AppContainer, teamId: Int, modifier: Modifier = Modifier) {
    val ids by container.teamFollows.ids.collectAsState()
    val loaded by container.teamFollows.loaded.collectAsState()
    val following = teamId in ids
    val gate = rememberNotifPermissionGate(container)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }

    val toggle: () -> Unit = {
        if (!busy) {
            busy = true
            val follow = !following
            scope.launch {
                val ok = container.teamFollows.setFollowing(teamId, follow)
                busy = false
                when {
                    !ok -> Toast.makeText(context, FOLLOW_FAILED, Toast.LENGTH_SHORT).show()
                    follow -> gate.onFollowed()
                }
            }
        }
    }

    // Until the follows load, "Follow" could be a lie about a team already
    // followed: hold a neutral placeholder, and retry a load that failed.
    LaunchedEffect(loaded) { if (!loaded) container.teamFollows.ensureLoaded() }

    val padding = ButtonDefaults.ButtonWithIconContentPadding
    if (!loaded) {
        OutlinedButton(onClick = {}, enabled = false, modifier = modifier, contentPadding = padding) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    } else if (following) {
        FilledTonalButton(onClick = toggle, modifier = modifier, contentPadding = padding) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Following")
        }
    } else {
        OutlinedButton(onClick = toggle, modifier = modifier, contentPadding = padding) {
            Icon(Icons.Filled.NotificationsNone, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Follow")
        }
    }
}

/**
 * "Club · League · Division" under a team name. The club is dropped when it
 * reads the same as the team, which for many clubs it does.
 */
fun teamSubtitle(teamName: String?, clubName: String?, leagueName: String?, divisionName: String?): String? =
    listOfNotNull(clubName?.takeIf { it.isNotBlank() && it != teamName }, leagueName, divisionName)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifEmpty { null }

fun FollowTeam.subtitle(): String? = teamSubtitle(name, club?.name, division?.leagueName, division?.name)

/** Shown when a follow or unfollow is refused — here and on Profile's Unfollow. */
const val FOLLOW_FAILED = "Couldn't update — try again"
