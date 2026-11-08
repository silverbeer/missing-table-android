package com.missingtable.scorer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.db.PendingAction
import com.missingtable.scorer.domain.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-wide "not on the server yet" banner (SB-1592). Shown above every
 * screen, for any match, whenever [SyncStatus.from] says the queue needs
 * attention: a rejected action, a retrying head, or rows left unsent.
 */
@Composable
fun SyncStatusBanner(
    container: AppContainer,
    status: SyncStatus,
    onOpenMatch: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmDiscard by remember { mutableStateOf<PendingAction?>(null) }
    val row = status.row
    val matchLabel by produceState("match #${row.matchId}", row.matchId) {
        runCatching { container.api.match(row.matchId) }
            .onSuccess { value = "${it.homeTeamName} v ${it.awayTeamName}" }
    }
    val error = status.kind == SyncStatus.Kind.BLOCKED || status.kind == SyncStatus.Kind.LINEUP_FAILED
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer

    Surface(color = bg, contentColor = fg, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(headline(status), style = MaterialTheme.typography.titleSmall)
            Text(
                detail(status, matchLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { onOpenMatch(row.matchId) }) { Text("Open match") }
                if (status.canRetry) {
                    TextButton(onClick = { scope.launch { container.liveRepo.retryFailed(row.id) } }) {
                        Text("Retry")
                    }
                } else {
                    TextButton(onClick = { container.syncEngine.kick() }) { Text("Retry now") }
                }
                if (status.canDiscard) {
                    TextButton(onClick = { confirmDiscard = row }) { Text("Discard") }
                }
            }
        }
    }

    confirmDiscard?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this ${label(target.actionType)}?") },
            text = {
                Text(
                    "It will never reach the server and can't be recovered. " +
                        "Re-enter it afterwards if it should count."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = null
                    scope.launch { container.liveRepo.discardFailed(target.id) }
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Keep") } },
        )
    }
}

/** Re-evaluates [SyncStatus.from] as rows age, so WAITING appears without a new write. */
@Composable
fun rememberSyncStatus(container: AppContainer): SyncStatus? {
    val rows by container.liveRepo.pendingAll().collectAsState(initial = emptyList())
    // Tick only while something is queued: an empty queue has nothing to age.
    val hasRows = rows.isNotEmpty()
    val now by produceState(System.currentTimeMillis(), hasRows) {
        value = System.currentTimeMillis()
        while (hasRows) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    return remember(rows, now) { SyncStatus.from(rows, now) }
}

internal fun headline(status: SyncStatus): String {
    val n = status.unsent
    val unsent = if (n == 1) "1 action not on the server" else "$n actions not on the server"
    return when (status.kind) {
        SyncStatus.Kind.BLOCKED -> "Sync stopped — $unsent"
        SyncStatus.Kind.LINEUP_FAILED -> "Lineup not saved"
        SyncStatus.Kind.RETRYING -> "Not syncing — $unsent"
        SyncStatus.Kind.WAITING -> "Waiting to sync — $unsent"
    }
}

internal fun detail(status: SyncStatus, matchLabel: String): String {
    val row = status.row
    val why = row.lastError?.let { " ($it)" } ?: ""
    val others = status.matchIds.size - 1
    val more = if (others > 0) " · $others other match${if (others == 1) "" else "es"} waiting" else ""
    return when (status.kind) {
        SyncStatus.Kind.BLOCKED ->
            "Server rejected a ${label(row.actionType)} for $matchLabel$why. Nothing after it syncs until you Retry or Discard.$more"
        SyncStatus.Kind.LINEUP_FAILED ->
            "Server rejected the lineup for $matchLabel$why. Scoring still syncs.$more"
        SyncStatus.Kind.RETRYING ->
            "Retrying a ${label(row.actionType)} for $matchLabel$why, ${row.attemptCount} attempts.$more"
        SyncStatus.Kind.WAITING ->
            "Oldest is a ${label(row.actionType)} for $matchLabel.$more"
    }
}

internal fun label(type: PendingAction.ActionType): String = when (type) {
    PendingAction.ActionType.GOAL -> "goal"
    PendingAction.ActionType.CARD -> "card"
    PendingAction.ActionType.SUB -> "substitution"
    PendingAction.ActionType.CLOCK -> "clock change"
    PendingAction.ActionType.DELETE_EVENT -> "event delete"
    PendingAction.ActionType.REOPEN -> "reopen"
    PendingAction.ActionType.LINEUP_SAVE -> "lineup"
    PendingAction.ActionType.MESSAGE -> "chat message"
    PendingAction.ActionType.POSITION -> "position change"
}
