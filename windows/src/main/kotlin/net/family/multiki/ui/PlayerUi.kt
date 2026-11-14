package net.family.multiki.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.family.multiki.stream.StreamStatus

enum class PlayerIcon { PLAY, PAUSE, NEXT, PREV, REWIND, FORWARD, STOP, CLOSE }

@Composable
fun PlayerGlyph(icon: PlayerIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (icon) {
            PlayerIcon.PLAY -> drawPath(triangle(w * 0.28f, h * 0.18f, w * 0.80f, h * 0.5f, w * 0.28f, h * 0.82f), color)
            PlayerIcon.PAUSE -> {
                drawRect(color, Offset(w * 0.26f, h * 0.2f), Size(w * 0.17f, h * 0.6f))
                drawRect(color, Offset(w * 0.57f, h * 0.2f), Size(w * 0.17f, h * 0.6f))
            }
            PlayerIcon.STOP -> drawRect(color, Offset(w * 0.25f, h * 0.25f), Size(w * 0.5f, h * 0.5f))
            PlayerIcon.NEXT -> {
                drawPath(triangle(w * 0.22f, h * 0.22f, w * 0.64f, h * 0.5f, w * 0.22f, h * 0.78f), color)
                drawRect(color, Offset(w * 0.66f, h * 0.22f), Size(w * 0.11f, h * 0.56f))
            }
            PlayerIcon.PREV -> {
                drawRect(color, Offset(w * 0.23f, h * 0.22f), Size(w * 0.11f, h * 0.56f))
                drawPath(triangle(w * 0.78f, h * 0.22f, w * 0.36f, h * 0.5f, w * 0.78f, h * 0.78f), color)
            }
            PlayerIcon.CLOSE -> {
                val sw = w * 0.12f
                drawLine(color, Offset(w * 0.28f, h * 0.28f), Offset(w * 0.72f, h * 0.72f), strokeWidth = sw)
                drawLine(color, Offset(w * 0.72f, h * 0.28f), Offset(w * 0.28f, h * 0.72f), strokeWidth = sw)
            }
            PlayerIcon.REWIND -> doubleTriangle(color, left = true)
            PlayerIcon.FORWARD -> doubleTriangle(color, left = false)
        }
    }
}

private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
    moveTo(x1, y1)
    lineTo(x2, y2)
    lineTo(x3, y3)
    close()
}

private fun DrawScope.doubleTriangle(color: Color, left: Boolean) {
    val w = size.width
    val h = size.height
    if (left) {
        drawPath(triangle(w * 0.50f, h * 0.25f, w * 0.16f, h * 0.5f, w * 0.50f, h * 0.75f), color)
        drawPath(triangle(w * 0.84f, h * 0.25f, w * 0.50f, h * 0.5f, w * 0.84f, h * 0.75f), color)
    } else {
        drawPath(triangle(w * 0.16f, h * 0.25f, w * 0.50f, h * 0.5f, w * 0.16f, h * 0.75f), color)
        drawPath(triangle(w * 0.50f, h * 0.25f, w * 0.84f, h * 0.5f, w * 0.50f, h * 0.75f), color)
    }
}

/** Round player button with a mustard ring when focused by the remote. */
@Composable
fun RoundButton(
    icon: PlayerIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    background: Color = Palette.Red,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(size)
            .border(BorderStroke(if (focused) 4.dp else 0.dp, if (focused) Palette.Mustard else Color.Transparent), CircleShape)
            .background(if (enabled) background else Palette.InkSoft.copy(alpha = 0.4f), CircleShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlyph(icon, Palette.Paper, Modifier.size(size * 0.6f))
    }
}

/**
 * Seek bar with times on both sides. Works with touch (tap / drag) and with a TV remote:
 * focus it with ↑/↓, then ←/→ move the mark (holding the key speeds up); the jump happens
 * when the key is released.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    onActivity: () -> Unit = {},
) {
    var preview by remember { mutableStateOf<Long?>(null) }
    var heldRepeats by remember { mutableStateOf(0) } // key-repeat count while ← / → is held (speeds up the jump)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shown = preview ?: positionMs
    val fraction = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val textColor = if (dark) Palette.Paper else Palette.Ink
    val maxPos = (durationMs - 1000).coerceAtLeast(0)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(formatMs(shown), style = MaterialTheme.typography.labelMedium, color = textColor)
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .padding(horizontal = 12.dp)
                .focusable(enabled = durationMs > 0, interactionSource = interaction)
                .onKeyEvent { e ->
                    if (durationMs <= 0) return@onKeyEvent false
                    val left = e.key == Key.DirectionLeft
                    val right = e.key == Key.DirectionRight
                    if (!left && !right) return@onKeyEvent false
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            val repeat = heldRepeats++
                            val base = maxOf(10_000L, durationMs / 100)
                            val step = base * (1 + repeat / 4).coerceAtMost(12)
                            val from = preview ?: positionMs
                            preview = (from + if (left) -step else step).coerceIn(0L, maxPos)
                            onActivity()
                            true
                        }
                        KeyEventType.KeyUp -> {
                            heldRepeats = 0
                            preview?.let(onSeek)
                            preview = null
                            onActivity()
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) onSeek(((offset.x / size.width) * durationMs).toLong().coerceIn(0L, maxPos))
                        onActivity()
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            preview?.let(onSeek)
                            preview = null
                        },
                        onDragCancel = { preview = null },
                    ) { change, _ ->
                        if (durationMs > 0) {
                            preview = ((change.position.x / size.width).coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, maxPos)
                            onActivity()
                        }
                        change.consume()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val trackH = if (focused) 10.dp.toPx() else 6.dp.toPx()
                val y = size.height / 2
                val inactive = if (dark) Palette.Paper.copy(alpha = 0.3f) else Palette.PaperDark
                drawRoundRect(inactive, Offset(0f, y - trackH / 2), Size(size.width, trackH), CornerRadius(trackH / 2))
                drawRoundRect(Palette.Red, Offset(0f, y - trackH / 2), Size(size.width * fraction, trackH), CornerRadius(trackH / 2))
                val thumbR = if (focused) 13.dp.toPx() else 9.dp.toPx()
                val x = size.width * fraction
                if (focused) drawCircle(Palette.Paper, radius = thumbR + 4.dp.toPx(), center = Offset(x, y))
                drawCircle(Palette.Mustard, radius = thumbR, center = Offset(x, y))
            }
        }
        Text(formatMs(durationMs), style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** One line about the cache/connection: progress or the reason for waiting. */
@Composable
fun StreamStatusLine(status: StreamStatus?, dark: Boolean = false, modifier: Modifier = Modifier) {
    if (status == null) return
    val color = if (dark) Palette.PaperDark else Palette.InkSoft
    val text = when {
        status.message != null -> status.message
        status.complete -> "Файл полностью загружен — перемотка без задержек"
        status.total > 0 -> "Загружено в кэш: ${status.percent}%"
        else -> "Подключение к архиву…"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (status.message != null) Palette.Mustard else color,
        modifier = modifier,
    )
}

/** Small bar at the bottom of every screen while a tale is playing. */
@Composable
fun MiniPlayer(
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onTick: () -> Unit,
) {
    LaunchedEffect(Unit) {
        while (true) {
            onTick()
            delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth().background(Palette.Ink)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp)) { PlaceholderArt(Placeholder.RECORD, Modifier.size(44.dp)) }
            Spacer(Modifier.width(10.dp))
            FocusCard(onClick = onOpen, modifier = Modifier.weight(1f), background = Palette.Ink, focusScale = 1f) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Palette.PaperDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundButton(if (isPlaying) PlayerIcon.PAUSE else PlayerIcon.PLAY, onToggle, size = 44.dp)
            Spacer(Modifier.width(8.dp))
            RoundButton(PlayerIcon.STOP, onStop, size = 44.dp, background = Palette.InkSoft)
        }
    }
}

@Composable
fun PlayerControlsRow(
    isPlaying: Boolean,
    onPrev: (() -> Unit)?,
    onRewind: () -> Unit,
    onToggle: () -> Unit,
    onForward: () -> Unit,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    toggleModifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(PlayerIcon.PREV, { onPrev?.invoke() }, size = 48.dp, background = Palette.InkSoft, enabled = onPrev != null)
        RoundButton(PlayerIcon.REWIND, onRewind, size = 52.dp, background = Palette.Teal)
        RoundButton(if (isPlaying) PlayerIcon.PAUSE else PlayerIcon.PLAY, onToggle, toggleModifier, size = 72.dp)
        if (onStop != null) RoundButton(PlayerIcon.STOP, onStop, size = 56.dp, background = Palette.RedDark)
        RoundButton(PlayerIcon.FORWARD, onForward, size = 52.dp, background = Palette.Teal)
        RoundButton(PlayerIcon.NEXT, { onNext?.invoke() }, size = 48.dp, background = Palette.InkSoft, enabled = onNext != null)
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))
