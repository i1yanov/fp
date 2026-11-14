package net.family.multiki.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Что дальше?» panel: countdown to the next title, "Сейчас", "Стоп",
 * and the choice between the next one in order and a random one.
 */
@Composable
fun NextUpPanel(
    title: String,
    secondsLeft: Int,
    random: Boolean,
    onNow: () -> Unit,
    onStop: () -> Unit,
    onOrder: () -> Unit,
    onRandom: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val nowFocus = remember { FocusRequester() }
    val bg = if (dark) Palette.Film.copy(alpha = 0.92f) else Palette.PaperDark
    val fg = if (dark) Palette.Paper else Palette.Ink
    Column(
        modifier
            .widthIn(max = 640.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(BorderStroke(2.dp, Palette.Red), RoundedCornerShape(8.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ЧТО ДАЛЬШЕ?", style = MaterialTheme.typography.headlineMedium, color = Palette.Red)
        Spacer(Modifier.height(6.dp))
        Text(
            if (random) "Случайно из этого раздела:" else "Следующий по порядку:",
            style = MaterialTheme.typography.titleMedium,
            color = if (dark) Palette.PaperDark else Palette.InkSoft,
        )
        FitText(title, style = MaterialTheme.typography.headlineSmall, color = fg, maxLines = 2, minFontSize = 15.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text("Включится через $secondsLeft с", style = MaterialTheme.typography.titleLarge, color = Palette.Mustard, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PosterButton("Сейчас", onClick = onNow, modifier = Modifier.focusRequester(nowFocus))
            PosterButton("Стоп", onClick = onStop, color = Palette.InkSoft)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PosterButton("По порядку", onClick = onOrder, color = if (!random) Palette.Teal else Palette.InkSoft.copy(alpha = 0.6f))
            PosterButton("Случайно", onClick = onRandom, color = if (random) Palette.Teal else Palette.InkSoft.copy(alpha = 0.6f))
        }
    }
    InitialFocus(nowFocus, key = title)
}

/** Sleep timer choices: 15 / 30 / 60 minutes, after this tale, off. */
@Composable
fun SleepTimerDialog(onPick: (minutes: Int?, afterTale: Boolean) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таймер сна", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Звук плавно затихнет и выключится.", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PosterButton("15 мин", onClick = { onPick(15, false) }, color = Palette.Teal, modifier = Modifier.focusRequester(first))
                    PosterButton("30 мин", onClick = { onPick(30, false) }, color = Palette.Teal)
                    PosterButton("60 мин", onClick = { onPick(60, false) }, color = Palette.Teal)
                }
                PosterButton("После этой сказки", onClick = { onPick(null, true) }, color = Palette.Teal, modifier = Modifier.fillMaxWidth())
                PosterButton("Выключить таймер", onClick = { onPick(null, false) }, color = Palette.InkSoft, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { PosterButton("Закрыть", onClick = onDismiss, color = Palette.RedDark) },
        containerColor = Palette.Paper,
    )
    InitialFocus(first)
}

@Suppress("unused")
private val keepColorImport = Color.Unspecified
