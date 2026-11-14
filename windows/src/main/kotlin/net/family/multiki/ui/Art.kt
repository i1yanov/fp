package net.family.multiki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Flat poster-style pictures drawn in code (no network, no copyrighted artwork).
 * FILM — film frame, used as a thumbnail placeholder.
 * PALETTE — cartoons, CAMERA — films, TV — TV films, RECORD — audio tales, RADIO — radio theatre, CAN — downloads,
 * HISTORY — viewing history.
 */
enum class Placeholder { FILM, PALETTE, CAMERA, TV, RECORD, RADIO, CAN, HISTORY }

private val Wood = Color(0xFFC8955A)
private val Steel = Color(0xFF9A968C)
private val SteelLight = Color(0xFFC9C4B6)

@Composable
fun PlaceholderArt(kind: Placeholder, modifier: Modifier = Modifier) {
    Canvas(modifier.clipToBounds().background(Palette.PaperDark)) {
        when (kind) {
            Placeholder.FILM -> drawFilmFrame()
            Placeholder.PALETTE -> drawPalette()
            Placeholder.CAMERA -> drawCamera()
            Placeholder.TV -> drawTv()
            Placeholder.RECORD -> drawRecord()
            Placeholder.RADIO -> drawRadio()
            Placeholder.CAN -> drawFilmCan()
            Placeholder.HISTORY -> drawClock()
        }
    }
}

/** Film reel: disc with five holes. */
private fun DrawScope.drawReel(c: Offset, r: Float, fg: Color, bg: Color) {
    drawCircle(fg, radius = r, center = c)
    for (k in 0 until 5) {
        val a = Math.toRadians(k * 72.0 - 90.0)
        drawCircle(bg, radius = r * 0.22f, center = Offset(c.x + (cos(a) * r * 0.55f).toFloat(), c.y + (sin(a) * r * 0.55f).toFloat()))
    }
    drawCircle(bg, radius = r * 0.10f, center = c)
}

/** Row of perforation holes evenly spread inside [left, right], never past the edge. */
private fun DrawScope.drawPerforations(left: Float, right: Float, y: Float, holeW: Float, holeH: Float, color: Color) {
    val step = holeW * 2f
    val count = max(1, ((right - left - holeW) / step).toInt() + 1)
    val used = (count - 1) * step + holeW
    val start = left + (right - left - used) / 2f
    for (k in 0 until count) drawRect(color, Offset(start + k * step, y), Size(holeW, holeH))
}

private fun DrawScope.drawFilmFrame() {
    val w = size.width
    val h = size.height
    drawRect(Palette.Film)
    val holeW = w * 0.06f
    val holeH = h * 0.07f
    drawPerforations(w * 0.03f, w * 0.97f, h * 0.04f, holeW, holeH, Palette.PaperDark)
    drawPerforations(w * 0.03f, w * 0.97f, h - h * 0.04f - holeH, holeW, holeH, Palette.PaperDark)
    drawRect(Palette.InkSoft, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.64f))
    drawReel(center, h * 0.18f, Palette.Paper, Palette.InkSoft)
}

/** Cartoons: artist's palette with paint and a brush. */
private fun DrawScope.drawPalette() {
    val s = min(size.width, size.height)
    val c = center
    val pw = s * 1.15f
    val ph = s * 0.78f
    drawOval(Palette.Ink.copy(alpha = 0.18f), Offset(c.x - pw / 2 + s * 0.03f, c.y - ph / 2 + s * 0.04f), Size(pw, ph))
    drawOval(Wood, Offset(c.x - pw / 2, c.y - ph / 2), Size(pw, ph))
    // thumb hole
    drawCircle(Palette.PaperDark, radius = s * 0.085f, center = Offset(c.x + pw * 0.28f, c.y + ph * 0.14f))
    // paint blobs
    val blobs = listOf(
        Palette.Red to Offset(-0.32f, -0.12f),
        Palette.Mustard to Offset(-0.12f, -0.26f),
        Palette.Teal to Offset(0.10f, -0.24f),
        Palette.Paper to Offset(-0.34f, 0.14f),
        Palette.Ink to Offset(-0.12f, 0.22f),
    )
    for ((color, o) in blobs) drawCircle(color, radius = s * 0.085f, center = Offset(c.x + o.x * pw, c.y + o.y * ph * 1.4f))
    // brush
    rotate(-35f, pivot = Offset(c.x + pw * 0.10f, c.y)) {
        val bx = c.x - pw * 0.05f
        val by = c.y - s * 0.02f
        drawLine(Palette.Ink, Offset(bx, by), Offset(bx + pw * 0.55f, by), strokeWidth = s * 0.045f, cap = StrokeCap.Round)
        drawRect(Steel, Offset(bx - s * 0.08f, by - s * 0.03f), Size(s * 0.09f, s * 0.06f))
        val tip = Path().apply {
            moveTo(bx - s * 0.08f, by - s * 0.035f)
            lineTo(bx - s * 0.21f, by)
            lineTo(bx - s * 0.08f, by + s * 0.035f)
            close()
        }
        drawPath(tip, Palette.Red)
    }
}

/** Films: movie camera with two reels. */
private fun DrawScope.drawCamera() {
    val s = min(size.width, size.height)
    val c = center
    val bw = s * 0.56f
    val bh = s * 0.30f
    val left = c.x - bw * 0.62f
    val top = c.y - bh * 0.35f
    // reels
    drawReel(Offset(left + bw * 0.22f, top - s * 0.13f), s * 0.135f, Palette.Ink, Palette.PaperDark)
    drawReel(Offset(left + bw * 0.76f, top - s * 0.13f), s * 0.135f, Palette.Ink, Palette.PaperDark)
    // body
    drawRoundRect(Palette.Ink, Offset(left, top), Size(bw, bh), CornerRadius(s * 0.04f))
    drawRoundRect(Palette.Red, Offset(left + bw * 0.08f, top + bh * 0.22f), Size(bw * 0.40f, bh * 0.20f), CornerRadius(s * 0.02f))
    drawCircle(Palette.Mustard, radius = s * 0.028f, center = Offset(left + bw * 0.80f, top + bh * 0.30f))
    // lens hood
    val hood = Path().apply {
        moveTo(left + bw, top + bh * 0.35f)
        lineTo(left + bw + s * 0.20f, top + bh * 0.08f)
        lineTo(left + bw + s * 0.20f, top + bh * 0.92f)
        lineTo(left + bw, top + bh * 0.65f)
        close()
    }
    drawPath(hood, Palette.InkSoft)
    // tripod
    val foot = top + bh
    drawLine(Palette.Ink, Offset(left + bw * 0.5f, foot), Offset(left + bw * 0.2f, foot + s * 0.12f), strokeWidth = s * 0.025f)
    drawLine(Palette.Ink, Offset(left + bw * 0.5f, foot), Offset(left + bw * 0.8f, foot + s * 0.12f), strokeWidth = s * 0.025f)
    drawLine(Palette.Ink, Offset(left + bw * 0.5f, foot), Offset(left + bw * 0.5f, foot + s * 0.13f), strokeWidth = s * 0.025f)
}

/** TV films: a 1970s TV set with antenna. */
private fun DrawScope.drawTv() {
    val s = min(size.width, size.height)
    val c = center
    val bw = s * 0.80f
    val bh = s * 0.52f
    val left = c.x - bw / 2
    val top = c.y - bh / 2 + s * 0.05f
    // antenna
    drawLine(Palette.Ink, Offset(c.x, top), Offset(c.x - s * 0.16f, top - s * 0.17f), strokeWidth = s * 0.02f)
    drawLine(Palette.Ink, Offset(c.x, top), Offset(c.x + s * 0.18f, top - s * 0.15f), strokeWidth = s * 0.02f)
    drawCircle(Palette.Ink, radius = s * 0.035f, center = Offset(c.x, top))
    // legs
    drawLine(Palette.Ink, Offset(left + bw * 0.18f, top + bh), Offset(left + bw * 0.12f, top + bh + s * 0.08f), strokeWidth = s * 0.025f)
    drawLine(Palette.Ink, Offset(left + bw * 0.82f, top + bh), Offset(left + bw * 0.88f, top + bh + s * 0.08f), strokeWidth = s * 0.025f)
    // cabinet
    drawRoundRect(Wood, Offset(left, top), Size(bw, bh), CornerRadius(s * 0.05f))
    // screen
    drawRoundRect(Palette.Teal, Offset(left + bw * 0.06f, top + bh * 0.10f), Size(bw * 0.66f, bh * 0.80f), CornerRadius(s * 0.07f))
    drawRoundRect(
        Palette.Paper.copy(alpha = 0.35f),
        Offset(left + bw * 0.10f, top + bh * 0.16f), Size(bw * 0.20f, bh * 0.14f), CornerRadius(s * 0.04f),
    )
    // knobs and speaker
    drawCircle(Palette.Ink, radius = s * 0.045f, center = Offset(left + bw * 0.86f, top + bh * 0.25f))
    drawCircle(Palette.Ink, radius = s * 0.045f, center = Offset(left + bw * 0.86f, top + bh * 0.48f))
    for (k in 0 until 3) {
        val y = top + bh * (0.66f + k * 0.08f)
        drawLine(Palette.Ink, Offset(left + bw * 0.78f, y), Offset(left + bw * 0.94f, y), strokeWidth = s * 0.015f)
    }
}

private fun DrawScope.drawRecord() {
    val r = size.minDimension * 0.42f
    drawCircle(Palette.Film, radius = r, center = center)
    for (k in 1..4) drawCircle(Color(0xFF4A3A30), radius = r * (0.5f + k * 0.11f), center = center, style = Stroke(1.dp.toPx()))
    drawCircle(Palette.Red, radius = r * 0.36f, center = center)
    drawCircle(Palette.Paper, radius = r * 0.05f, center = center)
    rotate(-30f) {
        drawArc(
            Palette.Paper.copy(alpha = 0.25f), 200f, 40f, false,
            topLeft = Offset(center.x - r * 0.8f, center.y - r * 0.8f), size = Size(r * 1.6f, r * 1.6f), style = Stroke(3.dp.toPx()),
        )
    }
}

private fun DrawScope.drawRadio() {
    val w = size.width
    val h = size.height
    val bw = w * 0.56f
    val bh = h * 0.56f
    val left = (w - bw) / 2
    val top = (h - bh) / 2 + h * 0.06f
    drawRoundRect(Palette.Teal, Offset(left, top), Size(bw, bh), CornerRadius(12.dp.toPx()))
    drawCircle(Palette.Paper, radius = bh * 0.3f, center = Offset(left + bw * 0.32f, top + bh * 0.5f))
    for (k in 0 until 4) {
        val y = top + bh * (0.28f + k * 0.15f)
        drawLine(Palette.Paper, Offset(left + bw * 0.62f, y), Offset(left + bw * 0.9f, y), strokeWidth = 3.dp.toPx())
    }
    drawLine(Palette.Ink, Offset(left + bw * 0.3f, top), Offset(left + bw * 0.12f, top - h * 0.16f), strokeWidth = 3.dp.toPx())
    for (k in 1..3) {
        drawArc(
            Palette.Red, -60f, 50f, false,
            topLeft = Offset(left + bw * 0.12f - k * 10.dp.toPx(), top - h * 0.16f - k * 10.dp.toPx()),
            size = Size(k * 20.dp.toPx(), k * 20.dp.toPx()), style = Stroke(2.dp.toPx()),
        )
    }
}

/** Downloads: a tin film can with a "down" badge. */
private fun DrawScope.drawFilmCan() {
    val s = min(size.width, size.height)
    val c = center
    val cw = s * 0.80f
    val eh = s * 0.22f // ellipse height (perspective)
    val bodyH = s * 0.22f
    val left = c.x - cw / 2
    val topY = c.y - bodyH / 2 - s * 0.04f
    // side and bottom
    drawOval(Palette.Ink.copy(alpha = 0.2f), Offset(left + s * 0.03f, topY + bodyH + s * 0.02f), Size(cw, eh))
    drawOval(Steel, Offset(left, topY + bodyH), Size(cw, eh))
    drawRect(Steel, Offset(left, topY + eh / 2), Size(cw, bodyH))
    drawRect(Palette.Red, Offset(left, topY + eh / 2 + bodyH * 0.25f), Size(cw, bodyH * 0.45f))
    // lid
    drawOval(SteelLight, Offset(left, topY), Size(cw, eh))
    drawOval(Steel, Offset(left + cw * 0.12f, topY + eh * 0.12f), Size(cw * 0.76f, eh * 0.76f), style = Stroke(s * 0.012f))
    drawOval(Steel, Offset(c.x - cw * 0.08f, topY + eh * 0.38f), Size(cw * 0.16f, eh * 0.24f))
    // badge with arrow
    val bc = Offset(c.x + cw * 0.32f, topY - s * 0.06f)
    val br = s * 0.15f
    drawCircle(Palette.Teal, radius = br, center = bc)
    drawLine(Palette.Paper, Offset(bc.x, bc.y - br * 0.5f), Offset(bc.x, bc.y + br * 0.35f), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
    val head = Path().apply {
        moveTo(bc.x - br * 0.45f, bc.y + br * 0.05f)
        lineTo(bc.x, bc.y + br * 0.55f)
        lineTo(bc.x + br * 0.45f, bc.y + br * 0.05f)
        close()
    }
    drawPath(head, Palette.Paper)
}

/** History: a round wall clock with a red "back in time" arrow around it. */
private fun DrawScope.drawClock() {
    val s = min(size.width, size.height)
    val c = center
    val r = s * 0.30f
    // arrow arc (counter-clockwise, "back in time") with an arrowhead along the arc's tangent
    val arcR = r * 1.35f
    val startDeg = 215f
    drawArc(
        Palette.Red, startAngle = startDeg, sweepAngle = 270f, useCenter = false,
        topLeft = Offset(c.x - arcR, c.y - arcR), size = Size(arcR * 2, arcR * 2),
        style = Stroke(s * 0.045f, cap = StrokeCap.Butt),
    )
    val a = Math.toRadians(startDeg.toDouble())
    val at = Offset(c.x + (cos(a) * arcR).toFloat(), c.y + (sin(a) * arcR).toFloat())
    // moving against the sweep direction: tangent (sin a, -cos a) in screen coordinates
    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
    val perp = Offset(-dir.y, dir.x)
    val len = s * 0.11f
    val half = s * 0.065f
    val head = Path().apply {
        moveTo(at.x + dir.x * len, at.y + dir.y * len)            // tip
        lineTo(at.x + perp.x * half, at.y + perp.y * half)        // base corner 1
        lineTo(at.x - perp.x * half, at.y - perp.y * half)        // base corner 2
        close()
    }
    drawPath(head, Palette.Red)
    // clock
    drawCircle(Palette.Ink, radius = r, center = c)
    drawCircle(Palette.Paper, radius = r * 0.86f, center = c)
    for (k in 0 until 12) {
        val ang = Math.toRadians(k * 30.0)
        val len = if (k % 3 == 0) 0.18f else 0.09f
        val o = Offset(c.x + (cos(ang) * r * 0.78f).toFloat(), c.y + (sin(ang) * r * 0.78f).toFloat())
        val i = Offset(c.x + (cos(ang) * r * (0.78f - len)).toFloat(), c.y + (sin(ang) * r * (0.78f - len)).toFloat())
        drawLine(Palette.Ink, i, o, strokeWidth = s * 0.015f)
    }
    drawLine(Palette.Ink, c, Offset(c.x, c.y - r * 0.55f), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
    drawLine(Palette.Teal, c, Offset(c.x - r * 0.42f, c.y + r * 0.12f), strokeWidth = s * 0.025f, cap = StrokeCap.Round)
    drawCircle(Palette.Red, radius = s * 0.025f, center = c)
}
