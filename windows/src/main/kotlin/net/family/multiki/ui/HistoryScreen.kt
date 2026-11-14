package net.family.multiki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.family.multiki.MainViewModel
import net.family.multiki.Screen
import net.family.multiki.data.History
import net.family.multiki.data.HistoryEntry

private val hosts = mapOf(
    "multiki" to "multiki.arjlover.net",
    "film" to "film.arjlover.net",
    "filmiki" to "filmiki.arjlover.net",
)

/** «История просмотров»: everything started or finished, newest first. */
@Composable
fun HistoryScreen(vm: MainViewModel) {
    val entries by History.entries.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { net.family.multiki.stream.HistorySync.requestNow() }
    var askClear by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("История просмотров") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isNotEmpty()) PosterButton("Очистить", onClick = { askClear = true }, color = Palette.RedDark)
                PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PlaceholderArt(Placeholder.HISTORY, Modifier.size(width = 220.dp, height = 140.dp))
                    Gap(16.dp)
                    Text(
                        "Здесь появятся фильмы, мультфильмы и сказки,\nкоторые вы начали смотреть или слушать.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.InkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (net.family.multiki.LocalIsTv.current) 6.dp else 10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(entries, key = { _, e -> e.key }) { index, e ->
                    HistoryRow(
                        e,
                        onOpen = { vm.open(Screen.Details(e.sectionId, e.itemId)) },
                        onResume = { vm.resume(e) },
                        onRemove = { History.remove(e.key) },
                        modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                    )
                }
            }
        }
    }
    if (entries.isNotEmpty()) InitialFocus(firstRow)

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("Очистить историю?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("Список и все места, где вы остановились, будут удалены.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                DialogButtons(
                    listOf(
                        DialogAction("Отмена", Palette.Teal) { askClear = false },
                        DialogAction("Очистить", Palette.Red) {
                            History.clear()
                            askClear = false
                        },
                    )
                )
            },
            containerColor = Palette.Paper,
        )
    }
}

@Composable
private fun HistoryRow(
    e: HistoryEntry,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = net.family.multiki.LocalIsTv.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FocusCard(onClick = onOpen, modifier = modifier.weight(1f), focusScale = 1f) {
            Row(Modifier.fillMaxWidth().padding(if (tv) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // TV: half-size thumbnails so more rows fit on screen
                Box(Modifier.size(width = if (tv) 56.dp else 112.dp, height = if (tv) 42.dp else 84.dp)) {
                    val art = when {
                        e.isVideo -> Placeholder.FILM
                        e.sectionId == "radioteatr" -> Placeholder.RADIO
                        else -> Placeholder.RECORD
                    }
                    PlaceholderArt(art, Modifier.fillMaxSize())
                    val host = hosts[e.sectionId]
                    if (e.isVideo && host != null) {
                        AsyncImage(
                            model = "http://$host/ap/${e.itemId}/${e.itemId}.thumb1.jpg",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FitText(
                        e.title,
                        style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        color = Palette.Ink,
                        maxLines = if (tv) 1 else 2,
                        minFontSize = 14.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SmallCaption(e.sectionTitle)
                    Text(resumeLine(e, e.isVideo), style = MaterialTheme.typography.labelMedium, color = Palette.Teal)
                    Spacer(Modifier.height(4.dp))
                    ProgressStrip(e.progress, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        val buttons: @Composable () -> Unit = {
            if (e.canResume) {
                RoundButton(PlayerIcon.PLAY, onResume, size = if (tv) 36.dp else 52.dp)
            } else {
                RoundButton(PlayerIcon.PREV, onResume, size = if (tv) 36.dp else 52.dp, background = Palette.Teal)
            }
            RoundButton(PlayerIcon.CLOSE, onRemove, size = if (tv) 30.dp else 40.dp, background = Palette.InkSoft)
        }
        if (tv) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}
