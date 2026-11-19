package com.uchoose.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The restaurant wheel. [rotation] is in degrees; the pointer sits at 12 o'clock.
 * Segment i starts at -90 + i * sweep (before rotation).
 */
@Composable
fun RestaurantWheel(
    names: List<String>,
    rotation: Float,
    spinning: Boolean,
    onSpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(rotation)) {
            val n = names.size.coerceAtLeast(1)
            val sweep = 360f / n
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            for (i in 0 until n) {
                drawArc(
                    color = if (i % 2 == 0) Palette.Surface else Palette.SurfaceAlt,
                    startAngle = -90f + i * sweep,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
            }
            // thin gold dividers
            for (i in 0 until n) {
                rotate(i * sweep, center) {
                    drawLine(
                        Palette.Gold.copy(alpha = 0.45f),
                        start = center,
                        end = Offset(center.x, center.y - radius),
                        strokeWidth = 1.2f,
                    )
                }
            }
            drawCircle(Palette.Gold, radius - 1f, center, style = Stroke(width = 3f))

            // names, written outward along each segment's middle
            val textPaint = Paint().apply {
                isAntiAlias = true
                color = Palette.Text.toArgb()
                typeface = Typeface.SERIF
                textSize = (if (n > 8) 12.sp else 14.sp).toPx()
            }
            names.forEachIndexed { i, raw ->
                val label = if (raw.length > 18) raw.take(17) + "…" else raw
                val mid = -90f + (i + 0.5f) * sweep
                drawContext.canvas.nativeCanvas.apply {
                    save()
                    translate(center.x, center.y)
                    rotate(mid)
                    val innerGap = radius * 0.24f
                    val available = radius * 0.94f - innerGap
                    val w = textPaint.measureText(label).coerceAtMost(available)
                    drawText(label, innerGap + (available - w), textPaint.textSize / 3f, textPaint)
                    restore()
                }
            }
        }

        // pointer at top (does not rotate)
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val cx = size.width / 2f
            val top = size.height / 2f - radius
            val path = Path().apply {
                moveTo(cx - 12.dp.toPx(), top - 6.dp.toPx())
                lineTo(cx + 12.dp.toPx(), top - 6.dp.toPx())
                lineTo(cx, top + 16.dp.toPx())
                close()
            }
            drawPath(path, Palette.Text)
        }

        // center spin button
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Palette.Background)
                .border(1.5.dp, Palette.Gold, CircleShape)
                .clickable(enabled = !spinning, onClick = onSpin),
            contentAlignment = Alignment.Center,
        ) {
            Text("SPIN", style = Type.Label.copy(fontSize = 15.sp))
        }
    }
}
