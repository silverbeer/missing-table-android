package com.missingtable.scorer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.domain.HalfDuration

/**
 * Half-length picker shown when a match starts (SB-645), shared by every
 * screen that can start one (SB-676).
 *
 * It lived only on LiveScreen at first, while the Lineup screen kept its own
 * START MATCH that sent no duration at all — so the path a scorer actually
 * takes (set the lineup, then start) always got the server's 45 default. One
 * dialog, used everywhere, is what stops that recurring.
 *
 * @param ageGroupName drives the pre-selected default; null falls back to 45.
 * @param onConfirm receives the chosen minutes per half.
 */
@Composable
fun StartMatchDialog(
    ageGroupName: String?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val default = HalfDuration.defaultFor(ageGroupName)
    var typed by remember { mutableStateOf(default.toString()) }
    val parsed = typed.toIntOrNull()
    val valid = HalfDuration.isValid(parsed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start match") },
        text = {
            Column {
                Text(
                    "Half length" + (ageGroupName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HalfDuration.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = parsed == preset,
                            onClick = { typed = preset.toString() },
                            label = { Text("$preset · ${HalfDuration.presetLabel(preset)}") },
                        )
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter(Char::isDigit).take(2) },
                    label = { Text("Minutes per half") },
                    singleLine = true,
                    isError = typed.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    if (valid) "= ${parsed!! * 2} min total" else "Must be ${HalfDuration.MIN}–${HalfDuration.MAX}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(parsed ?: default) },
            ) { Text("Start match") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
