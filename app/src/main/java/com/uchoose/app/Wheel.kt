package com.uchoose.app

import android.text.TextPaint
import android.text.TextUtils
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.sqrt

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
    onLongPressSegment: (Int) -> Unit = {},
) {
    // Press and hold a slice to act on that restaurant. The touch angle is turned back by
    // the wheel's current rotation to find which slice is under the finger.
    val pressModifier = Modifier.pointerInput(names, rotation, spinning) {
        if (spinning || names.isEmpty()) return@pointerInput
        detectTapGestures(onLongPress = { pos ->
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dx = pos.x - cx
            val dy = pos.y - cy
            val dist = sqrt(dx * dx + dy * dy)
            val radius = minOf(size.width, size.height) / 2f
            val hub = 42.dp.toPx()
            if (dist < hub || dist > radius) return@detectTapGestures
            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
            val sweep = 360f / names.size
            val unrotated = ((angle - rotation + 90f) % 360f + 360f) % 360f
            val index = (unrotated / sweep).toInt().coerceIn(0, names.size - 1)
            onLongPressSegment(index)
        })
    }

    Box(modifier.then(pressModifier), contentAlignment = Alignment.Center) {
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

            // names, written outward along each segment's middle. Each name must fit between
            // the SPIN button and the rim: one line if it fits, otherwise two lines, and
            // anything still too long is cut off with "…".
            val textPaint = TextPaint().apply {
                isAntiAlias = true
                color = Palette.Text.toArgb()
                typeface = Typeface.SERIF
                textSize = (if (n > 8) 12.sp else 14.sp).toPx()
            }
            val innerGap = maxOf(radius * 0.27f, 50.dp.toPx())   // just outside the SPIN button
            val outerEdge = radius - 10.dp.toPx()                // a little inside the gold rim
            val available = outerEdge - innerGap
            // Two lines need room across the slice; on crowded wheels keep them a bit smaller.
            val twoLineSize = textPaint.textSize * (if (n > 8) 0.9f else 0.95f)

            names.forEachIndexed { i, raw ->
                val mid = -90f + (i + 0.5f) * sweep
                val lines = fitLabel(raw, textPaint, available, twoLineSize)
                drawContext.canvas.nativeCanvas.apply {
                    save()
                    translate(center.x, center.y)
                    rotate(mid)
                    val paint = if (lines.size == 1) textPaint else TextPaint(textPaint).apply { textSize = twoLineSize }
                    val lineHeight = paint.textSize * 1.08f
                    val firstBaseline = paint.textSize / 3f - lineHeight * (lines.size - 1) / 2f
                    lines.forEachIndexed { li, line ->
                        val w = paint.measureText(line)
                        // right-aligned against the rim
                        drawText(line, outerEdge - w, firstBaseline + li * lineHeight, paint)
                    }
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

/**
 * Splits a restaurant name into one or two lines that each fit in [available] pixels.
 * Falls back to cutting the text off with "…" when even two lines aren't enough.
 */
private fun fitLabel(name: String, paint: TextPaint, available: Float, twoLineSize: Float): List<String> {
    val text = name.trim()
    if (paint.measureText(text) <= available) return listOf(text)

    val small = TextPaint(paint).apply { textSize = twoLineSize }
    fun cut(s: String) = TextUtils.ellipsize(s, small, available, TextUtils.TruncateAt.END).toString()

    val words = text.split(Regex("\\s+"))
    if (words.size < 2) return listOf(TextUtils.ellipsize(text, paint, available, TextUtils.TruncateAt.END).toString())

    // Pick the break that makes the longer of the two lines as short as possible.
    var best: Pair<String, String>? = null
    var bestWidth = Float.MAX_VALUE
    for (k in 1 until words.size) {
        val a = words.subList(0, k).joinToString(" ")
        val b = words.subList(k, words.size).joinToString(" ")
        val w = maxOf(small.measureText(a), small.measureText(b))
        if (w < bestWidth) {
            bestWidth = w
            best = a to b
        }
    }
    val (first, second) = best!!
    return listOf(cut(first), cut(second))
}
