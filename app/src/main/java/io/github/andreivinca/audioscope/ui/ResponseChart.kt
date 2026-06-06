package io.github.andreivinca.audioscope.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andreivinca.audioscope.dsp.Dsp
import io.github.andreivinca.audioscope.model.MeasurementPoint
import io.github.andreivinca.audioscope.model.TestMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10

private val GoodColor = ScopeAccent          // detected frequency matches the emitted one
private val PoorColor = Color(0xFFE5484D)    // wrong frequency reproduced
private val PerfectColor = ScopeAccent2      // the reference line

// How far the detected pitch is from the emitted one, in cents (100 = a semitone).
private fun cents(emitted: Double, detected: Double): Double =
    if (emitted <= 0.0 || detected <= 0.0) 0.0 else 1200.0 * ln(detected / emitted) / ln(2.0)

/**
 * Sweep / Multi-tone: for each emitted frequency, plot how far the loudest
 * frequency the mic heard (in its band) is off the emitted one, in cents. A
 * faithful speaker sits on the flat "perfect" line; a wrong/absent reproduction
 * departs from it. Faint volume bars give the level. Points quieter than the
 * volume gate are dropped, so silence/noise shows nothing.
 *
 * Live: the received volume per frequency (spectrum), with the gate line shown.
 */
@Composable
fun ResponseChart(
    points: List<MeasurementPoint>,
    mode: TestMode,
    minHz: Double,
    maxHz: Double,
    gateVolume: Double,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp),
    ) {
        drawGrid(measurer, minHz, maxHz)

        val padL = 40f
        val padR = 12f
        val padT = 12f
        val padB = 24f
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB

        val logMin = log10(minHz)
        val logMax = log10(maxHz)
        fun xOf(hz: Double): Float =
            padL + ((log10(hz) - logMin) / (logMax - logMin)).toFloat() * plotW

        if (points.isEmpty()) return@Canvas

        val baseY = padT + plotH

        // ---- SWEEP / MULTITONE: how far the detected pitch is off the emitted ----
        if (mode != TestMode.LIVE) {
            // Perfect = detected equals emitted = 0 cents = a flat horizontal line.
            val refY = padT + plotH * 0.5f
            val spanCents = 1200.0 // ±1 octave reaches the top/bottom of the plot
            fun yDev(c: Double): Float =
                (refY - (c / spanCents).coerceIn(-1.0, 1.0).toFloat() * plotH * 0.45f)
                    .coerceIn(padT, baseY)
            fun loud(p: MeasurementPoint) = Dsp.levelPercent(p.levelDb) >= gateVolume

            // Volume bars (0..100) behind the curve — context for how loud each
            // frequency came back. Drawn first so the frequency line sits on top.
            val barW = (plotW / points.size * 0.55f).coerceIn(2f, 22f)
            for (p in points) if (loud(p)) {
                val h = (Dsp.levelPercent(p.levelDb) / 100.0).toFloat()
                drawLine(
                    ScopeAccent.copy(alpha = 0.20f),
                    Offset(xOf(p.frequencyHz), baseY),
                    Offset(xOf(p.frequencyHz), baseY - h * plotH),
                    strokeWidth = barW,
                )
            }

            for (i in 0 until points.size - 1) {
                if (!loud(points[i]) || !loud(points[i + 1])) continue
                val c1 = cents(points[i].frequencyHz, points[i].detectedHz)
                val c2 = cents(points[i + 1].frequencyHz, points[i + 1].detectedHz)
                drawLine(
                    if (maxOf(abs(c1), abs(c2)) <= 100.0) GoodColor else PoorColor,
                    Offset(xOf(points[i].frequencyHz), yDev(c1)),
                    Offset(xOf(points[i + 1].frequencyHz), yDev(c2)),
                    strokeWidth = 3f,
                )
            }
            for (p in points) if (loud(p)) {
                val c = cents(p.frequencyHz, p.detectedHz)
                drawCircle(
                    if (abs(c) <= 100.0) GoodColor else PoorColor,
                    radius = 3f,
                    center = Offset(xOf(p.frequencyHz), yDev(c)),
                )
            }
            // The flat "perfect" reference line.
            drawLine(
                PerfectColor,
                Offset(padL, refY), Offset(size.width - padR, refY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
            val tl = measurer.measure("perfect (right frequency)", TextStyle(color = PerfectColor, fontSize = 9.sp))
            drawText(tl, topLeft = Offset(padL + 6f, refY - 13f))
            return@Canvas
        }

        // ---- LIVE: volume per frequency (spectrum) ----
        fun yVol(level: Double): Float =
            padT + (1f - (Dsp.levelPercent(level) / 100.0).toFloat()) * plotH
        val xs = points.map { xOf(it.frequencyHz) }
        val gated = points.map { if (Dsp.levelPercent(it.levelDb) >= gateVolume) it.levelDb else Dsp.LEVEL_FLOOR_DB }
        val ys = gated.map { yVol(it) }

        val area = Path().apply {
            moveTo(xs.first(), baseY)
            for (i in xs.indices) lineTo(xs[i], ys[i])
            lineTo(xs.last(), baseY); close()
        }
        drawPath(area, ScopeAccent.copy(alpha = 0.18f))
        val line = Path().apply {
            xs.indices.forEach { if (it == 0) moveTo(xs[0], ys[0]) else lineTo(xs[it], ys[it]) }
        }
        drawPath(line, ScopeAccent, style = Stroke(width = 2.5f))

        // Loudest peak marker.
        val pk = points.indices.maxByOrNull { points[it].levelDb }
        if (pk != null && Dsp.levelPercent(points[pk].levelDb) >= gateVolume) {
            drawCircle(PerfectColor, radius = 5f, center = Offset(xs[pk], ys[pk]))
        }

        // Volume gate line.
        val gy = padT + (1f - (gateVolume / 100.0).toFloat()) * plotH
        drawLine(
            PerfectColor,
            Offset(padL, gy), Offset(size.width - padR, gy),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )
        val tl = measurer.measure("gate ${gateVolume.toInt()}", TextStyle(color = PerfectColor, fontSize = 9.sp))
        drawText(tl, topLeft = Offset(padL + 6f, gy - 13f))
    }
}

private fun DrawScope.drawGrid(measurer: TextMeasurer, minHz: Double, maxHz: Double) {
    val padL = 40f
    val padR = 12f
    val padT = 12f
    val padB = 24f
    val plotW = size.width - padL - padR
    val plotH = size.height - padT - padB
    val logMin = log10(minHz)
    val logMax = log10(maxHz)

    val labelStyle = TextStyle(color = ScopeMuted, fontSize = 9.sp)

    val decades = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
    for (hz in decades) {
        if (hz < minHz || hz > maxHz) continue
        val x = padL + ((log10(hz.toDouble()) - logMin) / (logMax - logMin)).toFloat() * plotW
        val major = hz == 100 || hz == 1000 || hz == 10000
        drawLine(
            if (major) ScopeGrid else ScopeGrid.copy(alpha = 0.5f),
            Offset(x, padT), Offset(x, padT + plotH), strokeWidth = 1f,
        )
        if (major || hz == 20 || hz == 20000) {
            val label = if (hz >= 1000) "${hz / 1000}k" else "$hz"
            val tl = measurer.measure(label, labelStyle)
            drawText(tl, topLeft = Offset(x - tl.size.width / 2f, size.height - padB + 4f))
        }
    }

    val rows = 4
    for (r in 0..rows) {
        val y = padT + plotH * r / rows
        drawLine(ScopeGrid.copy(alpha = 0.5f), Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 1f)
    }
    drawLine(Color(0xFF3A434F), Offset(padL, padT), Offset(padL, padT + plotH), strokeWidth = 1f)
}
