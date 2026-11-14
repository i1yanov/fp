package net.family.multiki.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Text that never breaks a word in the middle.
 * Tries the given font size; if the text needs more than [maxLines] lines or a word would be split,
 * the font shrinks step by step down to [minFontSize]. If it still does not fit, the last line ends with "…".
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minFontSize: TextUnit = 11.sp,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Center,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        // Constraints can't hold huge values; 8000 px is wider than any screen
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.coerceIn(1, 8000) else 8000
        val startSize = if (style.fontSize.isSpecified) style.fontSize.value else 16f
        val minSize = if (minFontSize.isSpecified) minFontSize.value else 11f
        val chosen = remember(text, style, maxWidth, maxLines, startSize, minSize) {
            var size = startSize
            var result: TextStyle
            while (true) {
                val candidate = style.copy(
                    fontSize = size.sp,
                    lineHeight = (size * 1.15f).sp,
                    lineBreak = LineBreak.Heading,
                    textAlign = textAlign,
                )
                val layout = measurer.measure(
                    text = AnnotatedString(text),
                    style = candidate,
                    constraints = Constraints(maxWidth = maxWidth),
                )
                result = candidate
                if (layout.lineCount <= maxLines && !splitsWord(layout, text)) break
                if (size <= minSize) break
                size = max(minSize, size - 1f)
            }
            result
        }
        Text(
            text = text,
            style = chosen,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
        )
    }
}

private fun splitsWord(layout: TextLayoutResult, text: String): Boolean {
    for (i in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(i, visibleEnd = false)
        if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return true
    }
    return false
}

/** Waiting indicator in the style of a film countdown leader: a sweeping sector and 3-2-1. */
@Composable
fun FilmLeaderLoader(modifier: Modifier = Modifier, caption: String? = "Заряжаем плёнку…", size: Dp = 140.dp) {
    val transition = rememberInfiniteTransition(label = "leader")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3000, easing = LinearEasing)),
        label = "t",
    )
    val number = 3 - floor(t).toInt().coerceIn(0, 2)
    val sweep = (t - floor(t)) * 360f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f
                val c = center
                drawRect(Palette.Film, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2))
                drawCircle(Palette.PaperDark, radius = r * 0.92f, center = c)
                drawArc(
                    color = Palette.InkSoft.copy(alpha = 0.55f),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = Offset(c.x - r * 0.92f, c.y - r * 0.92f),
                    size = Size(r * 1.84f, r * 1.84f),
                )
                drawLine(Palette.Ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 2.dp.toPx())
                drawLine(Palette.Ink, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 2.dp.toPx())
                drawCircle(Palette.Paper, radius = r * 0.92f, center = c, style = Stroke(3.dp.toPx()))
                drawCircle(Palette.Paper, radius = r * 0.70f, center = c, style = Stroke(2.dp.toPx()))
                // perforations on the sides of the frame
                val hole = r * 0.10f
                for (k in -3..3) {
                    val y = c.y + k * r * 0.28f
                    drawRect(Palette.Paper, Offset(c.x - r + hole * 0.3f, y - hole / 2), Size(hole * 0.6f, hole))
                    drawRect(Palette.Paper, Offset(c.x + r - hole * 0.9f, y - hole / 2), Size(hole * 0.6f, hole))
                }
            }
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = (size.value * 0.42f).sp),
                color = Palette.Ink,
            )
        }
        if (caption != null) {
            Spacer(Modifier.height(12.dp))
            Text(caption, style = MaterialTheme.typography.titleMedium, color = Palette.InkSoft)
        }
    }
}

/** Card frame that highlights when focused with a TV remote. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Palette.Paper,
    focusScale: Float = 1.05f, // wide rows use 1f: growing 5% would push them over neighbouring buttons
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .scale(if (focused) focusScale else 1f)
            .border(
                BorderStroke(if (focused) 4.dp else 1.dp, if (focused) Palette.Mustard else Palette.InkSoft.copy(alpha = 0.4f)),
                shape,
            )
            .background(background, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) { content() }
}

/** Big poster-like button. */
@Composable
fun PosterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Palette.Red,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) Palette.Mustard else Color.Transparent), shape)
            .background(if (enabled) color else Palette.InkSoft.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Palette.Paper, maxLines = 1)
    }
}

/** Decorative film-strip band used as a header/footer. */
@Composable
fun FilmStripBand(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(Palette.Film)
        val hole = size.height * 0.45f
        val step = hole * 3f
        val count = max(1, ((size.width - hole) / step).toInt())
        val start = (size.width - (count - 1) * step - hole * 1.4f) / 2f
        for (k in 0 until count) {
            drawRect(Palette.Paper, Offset(start + k * step, (size.height - hole) / 2), Size(hole * 1.4f, hole))
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(Palette.Red)) {
        FilmStripBand()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = Palette.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun SmallCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = Palette.InkSoft, modifier = modifier)
}

/** On TV, moves the remote-control focus to [requester] once it is on screen. */
@Composable
fun InitialFocus(requester: androidx.compose.ui.focus.FocusRequester, key: Any? = Unit) {
    if (!net.family.multiki.LocalIsTv.current) return
    androidx.compose.runtime.LaunchedEffect(key) {
        repeat(5) {
            kotlinx.coroutines.delay(120)
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

/** Readable error box shown instead of a crash when a catalog file can't be opened. */
@Composable
fun LoadError(error: Throwable) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Не удалось открыть раздел", style = MaterialTheme.typography.headlineSmall, color = Palette.Red)
            Spacer(Modifier.height(8.dp))
            Text(
                error.toString().take(600),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A button of [DialogButtons]. */
data class DialogAction(val label: String, val color: Color, val onClick: () -> Unit)

/**
 * Dialog buttons that always fit: in a row on wide screens, stacked full-width on narrow phones.
 * The first action gets the remote-control focus.
 */
@Composable
fun DialogButtons(actions: List<DialogAction>) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEachIndexed { i, a ->
                    PosterButton(
                        a.label, onClick = a.onClick, color = a.color,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                actions.forEachIndexed { i, a ->
                    PosterButton(
                        a.label, onClick = a.onClick, color = a.color,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
    InitialFocus(first)
}
