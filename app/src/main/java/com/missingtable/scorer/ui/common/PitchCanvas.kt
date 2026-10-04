package com.missingtable.scorer.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

private val PitchGreen = Color(0xFF2E7D46)
private val PitchLine = Color(0xCCFFFFFF)

/**
 * The pitch markings behind a formation: outline, halfway line, centre circle
 * and both boxes, attacking half up top. Shared by the lineup builder and the
 * live sub mode (SB-1228) so the two screens look the same.
 */
@Composable
fun PitchCanvas(modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        drawRect(PitchGreen)
        val stroke = 3f
        drawRect(PitchLine, style = Stroke(stroke))
        drawLine(PitchLine, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), stroke)
        drawCircle(
            PitchLine,
            radius = size.width * 0.12f,
            center = Offset(size.width / 2, size.height / 2),
            style = Stroke(stroke),
        )
        drawRect(
            PitchLine,
            topLeft = Offset(size.width * 0.22f, size.height * 0.88f),
            size = Size(size.width * 0.56f, size.height * 0.12f),
            style = Stroke(stroke),
        )
        drawRect(
            PitchLine,
            topLeft = Offset(size.width * 0.22f, 0f),
            size = Size(size.width * 0.56f, size.height * 0.12f),
            style = Stroke(stroke),
        )
    }
}
