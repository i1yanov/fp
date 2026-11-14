package net.family.multiki.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.family.multiki.MainViewModel
import net.family.multiki.Screen
import net.family.multiki.data.History
import net.family.multiki.data.HistoryEntry
import net.family.multiki.data.formatSize
import net.family.multiki.stream.DItem
import net.family.multiki.stream.DState
import net.family.multiki.stream.Downloads
import net.family.multiki.stream.SmbItem
import net.family.multiki.stream.SmbStore

private val videoHosts = mapOf(
    "multiki" to "multiki.arjlover.net",
    "film" to "film.arjlover.net",
    "filmiki" to "filmiki.arjlover.net",
)

/** One row of «Загруженное», either on this device or on the home server. */
private data class DRow(
    val key: String,
    val sectionId: String,
    val itemId: String,
    val title: String,
    val sectionTitle: String,
    val isVideo: Boolean,
    val status: String,
    val percent: Int,
    val failed: Boolean,
    val canPlay: Boolean,
    val task: DItem?,       // our own download/transfer, if any
    val lib: SmbItem?,      // the server copy, if any
    val onServer: Boolean,
)

private data class Ask(val title: String, val text: String, val actions: List<Pair<String, () -> Unit>>)

/** «Загруженное»: this device and the home server, with transfers between them. */
@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val tasks by Downloads.tasks.collectAsState()
    val smbSettings by SmbStore.settings.collectAsState()
    val library by SmbStore.library.collectAsState()
    var ask by remember { mutableStateOf<Ask?>(null) }
    var used by remember { mutableLongStateOf(0L) }
    var free by remember { mutableLongStateOf(0L) }
    val firstRow = remember { FocusRequester() }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                used = Downloads.usedBytes()
                free = Downloads.freeBytes()
            }
            SmbStore.refreshAsync()
            delay(30_000)
        }
    }

    val localItems = remember(tasks) { Downloads.items(tasks).filter { !it.toServer } }
    val serverRows = remember(tasks, library) { serverRows(Downloads.items(tasks).filter { it.toServer }, library.items) }
    val deviceRows = remember(localItems, library) { localItems.map { deviceRow(it, library.items) } }

    fun play(r: DRow) {
        val e = History.get(r.sectionId, r.itemId)
            ?: HistoryEntry(r.sectionId, r.itemId, r.title, r.sectionTitle, r.isVideo, 0, 1, 0, 0, 0, false)
        vm.resume(e)
    }

    fun askTransfer(r: DRow, toServer: Boolean) {
        val where = if (toServer) "на домашний сервер" else "на этот компьютер"
        val from = if (toServer) "с этого компьютера" else "с домашнего сервера"
        ask = Ask(
            title = "Перенести $where?",
            text = "«${r.title}» будет скопирован $from $where по домашней сети, без интернета.\n\n" +
                "Можно оставить копию в обоих местах или перенести — тогда старая копия удалится после успешного копирования.",
            actions = listOf(
                "Копировать" to { vm.transfer(r.sectionId, r.itemId, toServer, move = false) },
                "Перенести" to { vm.transfer(r.sectionId, r.itemId, toServer, move = true) },
            ),
        )
    }

    fun askDelete(r: DRow) {
        val where = if (r.onServer) "с домашнего сервера — для всех устройств" else "с этого компьютера"
        ask = Ask(
            title = "Удалить?",
            text = "«${r.title}» будет удалён $where.",
            actions = listOf("Удалить" to {
                if (r.onServer) {
                    r.task?.let { Downloads.delete(it.key) }
                    r.lib?.let { lib -> Thread { runCatching { SmbStore.deleteItem(lib) } }.start() }
                } else {
                    r.task?.let { Downloads.delete(it.key) }
                }
            }),
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Загруженное") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterButton("Настройки", onClick = { vm.open(Screen.Settings) }, color = Palette.RedDark)
                PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
            }
        }
        Tabs(
            tab,
            listOf("На компьютере · ${deviceRows.size}", "На сервере · ${serverRows.size}"),
            onSelect = { tab = it },
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (net.family.multiki.LocalIsTv.current) 6.dp else 10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
          if (tab == 0) {
            // ------------------------------------------------ this device
            item {
                GroupHeader(
                    "НА ЭТОМ КОМПЬЮТЕРЕ",
                    "Занято ${formatSize(used)} · свободно ${formatSize(free)} · папка ${Downloads.root.absolutePath} (меняется в «Настройках»)",
                )
            }
            if (deviceRows.isEmpty()) {
                item { EmptyNote("Пока пусто. Откройте карточку фильма или сказки и нажмите «Скачать».") }
            }
            items(deviceRows, key = { "d:" + it.key }) { r ->
                RowCard(
                    r,
                    modifier = if (r === deviceRows.first()) Modifier.focusRequester(firstRow) else Modifier,
                    onOpen = { vm.open(Screen.Details(r.sectionId, r.itemId)) },
                    onPlay = { play(r) },
                    onPause = { r.task?.let { Downloads.pause(it.key) } },
                    onResume = { r.task?.let { Downloads.resume(it.key) } },
                    transferLabel = if (smbSettings.configured && r.task?.done == true && r.lib?.complete != true) "На сервер" else null,
                    onTransfer = { askTransfer(r, toServer = true) },
                    onDelete = { askDelete(r) },
                )
            }

          } else {
            // ------------------------------------------------ home server
            item {
                GroupHeader(
                    "НА ДОМАШНЕМ СЕРВЕРЕ",
                    when {
                        !smbSettings.configured -> "Не подключён"
                        library.loading && library.updated == 0L -> "Подключение…"
                        library.error != null -> "Нет связи: ${library.error}"
                        else -> "${smbSettings.folder} · общий для всех устройств"
                    },
                    error = smbSettings.configured && library.error != null,
                )
            }
            if (!smbSettings.configured) {
                item {
                    Column(Modifier.fillMaxWidth().background(Palette.PaperDark).padding(16.dp)) {
                        Text(
                            "Подключите домашний SMB-сервер, чтобы скачивать фильмы один раз и смотреть их на всех телевизорах и планшетах.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Ink,
                        )
                        Gap(10.dp)
                        PosterButton("Настроить сервер", onClick = { vm.open(Screen.Settings) }, color = Palette.Teal)
                    }
                }
            } else {
                if (library.error != null) {
                    item { PosterButton("Повторить подключение", onClick = { SmbStore.refreshAsync() }, color = Palette.Teal) }
                }
                if (serverRows.isEmpty() && library.error == null) {
                    item { EmptyNote("На сервере пока ничего нет. Нажмите «Скачать» → «На домашний сервер» в карточке.") }
                }
                items(serverRows, key = { "s:" + it.key }) { r ->
                    val onDevice = Downloads.item(r.sectionId, r.itemId, tasks, toServer = false)
                    RowCard(
                        r,
                        onOpen = { vm.open(Screen.Details(r.sectionId, r.itemId)) },
                        onPlay = { play(r) },
                        onPause = { r.task?.let { Downloads.pause(it.key) } },
                        onResume = { r.task?.let { Downloads.resume(it.key) } },
                        transferLabel = if (r.lib?.complete == true && onDevice == null) "На компьютер" else null,
                        onTransfer = { askTransfer(r, toServer = false) },
                        onDelete = { askDelete(r) },
                    )
                }
            }
          }
        }
    }
    if (tab == 0 && deviceRows.isNotEmpty()) InitialFocus(firstRow)

    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(a.title, style = MaterialTheme.typography.headlineSmall) },
            text = { Text(a.text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                DialogButtons(
                    a.actions.mapIndexed { i, (label, action) ->
                        DialogAction(label, if (i == a.actions.lastIndex) Palette.Red else Palette.Teal) {
                            ask = null
                            action()
                        }
                    } + DialogAction("Отмена", Palette.InkSoft) { ask = null }
                )
            },
            containerColor = Palette.Paper,
        )
    }
}

/** Two tabs: «На компьютере» / «На сервере». */
@Composable
private fun Tabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().background(Palette.PaperDark).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            PosterButton(
                label,
                onClick = { onSelect(i) },
                color = if (i == selected) Palette.Red else Palette.InkSoft.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun deviceRow(d: DItem, lib: List<SmbItem>): DRow = DRow(
    key = d.key, sectionId = d.sectionId, itemId = d.itemId, title = d.title, sectionTitle = d.sectionTitle,
    isVideo = d.isVideo, status = statusText(d, onServer = false), percent = d.percent, failed = d.failed,
    canPlay = d.done, task = d, lib = lib.firstOrNull { it.sectionId == d.sectionId && it.itemId == d.itemId }, onServer = false,
)

/** Server rows: everything in the library, plus our own server downloads not yet visible there. */
private fun serverRows(own: List<DItem>, lib: List<SmbItem>): List<DRow> {
    val byKey = own.associateBy { "${it.sectionId}|${it.itemId}" }
    val rows = lib.map { l ->
        val t = byKey[l.key]
        val status = when {
            t != null && t.active -> statusText(t, onServer = true)
            l.complete -> "✓ На сервере · ${formatSize(l.bytes)} · смотрится на всех устройствах"
            l.busyElsewhere -> "Загружается с устройства «${l.busyDevice}» · ${l.doneCount} из ${l.parts.size}"
            t != null -> statusText(t, onServer = true)
            else -> "Загружено ${l.doneCount} из ${l.parts.size} — докачать можно кнопкой «Скачать» в карточке"
        }
        val percent = if (t != null && t.active) t.percent else (l.doneCount * 100 / l.parts.size.coerceAtLeast(1))
        DRow(l.key, l.sectionId, l.itemId, l.title, l.sectionTitle, l.isVideo, status, percent, t?.failed == true,
            canPlay = l.complete, task = t, lib = l, onServer = true)
    }
    val extra = own.filter { o -> lib.none { it.key == "${o.sectionId}|${o.itemId}" } }.map { t ->
        DRow("${t.sectionId}|${t.itemId}", t.sectionId, t.itemId, t.title, t.sectionTitle, t.isVideo,
            statusText(t, onServer = true), t.percent, t.failed, canPlay = false, task = t, lib = null, onServer = true)
    }
    return extra + rows
}

@Composable
private fun GroupHeader(title: String, subtitle: String, error: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Red)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = if (error) Palette.Red else Palette.InkSoft)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun RowCard(
    r: DRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    transferLabel: String?,
    onTransfer: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = net.family.multiki.LocalIsTv.current
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FocusCard(onClick = onOpen, modifier = modifier.weight(1f), focusScale = 1f) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(if (tv) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // TV: half-size thumbnails so more rows fit on screen
                Box(Modifier.size(width = if (tv) 56.dp else 112.dp, height = if (tv) 42.dp else 84.dp)) {
                    val art = when {
                        r.isVideo -> Placeholder.FILM
                        r.sectionId == "radioteatr" -> Placeholder.RADIO
                        else -> Placeholder.RECORD
                    }
                    PlaceholderArt(art, Modifier.fillMaxSize())
                    val host = videoHosts[r.sectionId]
                    if (r.isVideo && host != null) {
                        AsyncImage(
                            model = "http://$host/ap/${r.itemId}/${r.itemId}.thumb1.jpg",
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FitText(
                        r.title,
                        style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        color = Palette.Ink,
                        maxLines = if (tv) 1 else 2,
                        minFontSize = 14.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SmallCaption(r.sectionTitle)
                    Text(r.status, style = MaterialTheme.typography.labelMedium, color = if (r.failed) Palette.Red else Palette.Teal)
                    Spacer(Modifier.height(4.dp))
                    ProgressStrip(r.percent / 100f, Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        val buttons: @Composable () -> Unit = {
            val t = r.task
            when {
                t != null && t.active -> RoundButton(PlayerIcon.PAUSE, onPause, size = if (tv) 36.dp else 48.dp, background = Palette.Teal)
                r.canPlay -> RoundButton(PlayerIcon.PLAY, onPlay, size = if (tv) 36.dp else 48.dp)
                t != null && !t.done -> RoundButton(PlayerIcon.PLAY, onResume, size = if (tv) 36.dp else 48.dp, background = Palette.Teal)
            }
            if (transferLabel != null) PosterButton(transferLabel, onClick = onTransfer, color = Palette.Teal)
            RoundButton(PlayerIcon.CLOSE, onDelete, size = if (tv) 30.dp else 40.dp, background = Palette.InkSoft)
        }
        if (tv) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        }
    }
}

private fun statusText(d: DItem, onServer: Boolean): String {
    val size = formatSize(d.bytesDone) + if (d.bytesTotal > 0 && !d.done) " из " + formatSize(d.bytesTotal) else ""
    val parts = if (d.tasks.size > 1) " · ${d.tasks.count { it.state == DState.DONE }} из ${d.tasks.size}" else ""
    return when {
        d.done -> if (onServer) "✓ На сервере · $size" else "✓ Загружено · $size$parts · смотрится без интернета"
        d.active -> {
            val cur = d.current
            when {
                cur == null -> "В очереди · $size$parts"
                cur.state == DState.RUNNING -> "${d.percent}% · $size$parts" + (cur.message?.let { " · $it" } ?: "")
                else -> cur.message ?: "Ждёт очереди"
            }
        }
        d.failed -> d.tasks.firstOrNull { it.state == DState.ERROR }?.message ?: "Ошибка — нажмите ▶, чтобы повторить"
        else -> "Пауза · ${d.percent}% · $size$parts"
    }
}
