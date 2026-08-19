package com.missingtable.scorer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * A club crest, or its initials when there is no logo or no signal (SB-651).
 *
 * The slot is a FIXED size whether or not an image ever arrives. This app is
 * used one-handed at a pitch: a crest that pops in late and reflows the
 * scoreboard mid-tap would cause mis-taps, which is worse than showing no
 * crest at all. Coil caches to disk, so a logo fetched once survives going
 * offline — the normal state at a field.
 */
@Composable
fun TeamCrest(
    logoUrl: String?,
    teamName: String,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null, // decorative; the name is alongside
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                initials(teamName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "TSC A-Team" -> "TA"; "IFA" -> "IF". Never empty, never more than 2 chars. */
internal fun initials(name: String): String {
    val words = name.split(' ', '-').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
