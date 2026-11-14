package net.family.multiki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.runtime.collectAsState
import net.family.multiki.MainViewModel
import net.family.multiki.Screen
import net.family.multiki.stream.DState
import net.family.multiki.stream.Downloads
import net.family.multiki.data.History
import net.family.multiki.data.HistoryEntry
import net.family.multiki.data.formatWhen
import net.family.multiki.data.Item
import net.family.multiki.data.Kind
import net.family.multiki.data.Part
import net.family.multiki.data.Section
import net.family.multiki.data.formatDuration
import net.family.multiki.data.formatSize

@Composable
fun DetailsScreen(vm: MainViewModel, sectionId: String, itemId: String) {
    val loaded by produceState<Result<Section>?>(initialValue = null, sectionId) {
        value = runCatching { vm.section(sectionId) }
    }
    val s = loaded?.getOrNull()
    loaded?.exceptionOrNull()?.let { LoadError(it); return }
    val item = s?.items?.firstOrNull { it.id == itemId }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(s?.title ?: "") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterButton("← Назад", onClick = { vm.back() }, color = Palette.RedDark)
                PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
                if (s != null && s.items.size > 1) {
                    // «Случайный» / «Случайная» — the first word of the section's random label; a die on narrow phones
                    val narrow = false
                    PosterButton(
                        if (narrow) "🎲" else s.randomLabel.substringBefore(' '),
                        onClick = {
                            val next = s.items.filter { it.id != itemId }.random()
                            vm.replaceTop(Screen.Details(s.id, next.id)) // «Назад» still leads back to the section
                        },
                        color = Palette.Teal,
                    )
                }
            }
        }
        when {
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FilmLeaderLoader() }
            item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Не найдено", style = MaterialTheme.typography.bodyLarge)
            }
            else -> DetailsContent(
                s, item,
                onPlay = { part, startMs -> vm.play(s, item, part, startMs) },
                onDownload = { toServer -> vm.download(s, item, toServer) },
                onTransfer = { toServer, move -> vm.transfer(s.id, item.id, toServer, move) },
                onOpenDownloads = { vm.open(Screen.Downloads) },
            )
        }
    }
}

@Composable
private fun DetailsContent(
    section: Section,
    item: Item,
    onPlay: (Int, Long) -> Unit,
    onDownload: (Boolean) -> Unit,
    onTransfer: (Boolean, Boolean) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    var notice by remember { mutableStateOf<String?>(null) }
    val mainButton = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val scroll = rememberScrollState()
        val picture: @Composable (Modifier) -> Unit = { m -> Pictures(section, item, m) }
        val info: @Composable (Modifier) -> Unit = { m ->
            Info(section, item, m, mainButton, onPlay, onDownload, onTransfer, onOpenDownloads) { notice = it }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(20.dp)) {
                Column(Modifier.weight(0.45f).verticalScroll(rememberScrollState())) { picture(Modifier.fillMaxWidth()) }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(0.55f).verticalScroll(scroll)) { info(Modifier.fillMaxWidth()) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                picture(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                info(Modifier.fillMaxWidth())
            }
        }
    }
    InitialFocus(mainButton, key = item.id)
    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            confirmButton = { PosterButton("Понятно", onClick = { notice = null }) },
            title = { Text("Скоро будет", style = MaterialTheme.typography.headlineSmall) },
            text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
            containerColor = Palette.Paper,
        )
    }
}

@Composable
private fun Pictures(section: Section, item: Item, modifier: Modifier) {
    Column(modifier) {
        if (section.kind == Kind.VIDEO) {
            var frame by remember(item.id) { mutableIntStateOf(1) }
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                PlaceholderArt(Placeholder.FILM, Modifier.fillMaxSize())
                AsyncImage(
                    model = section.imageUrl(item, frame),
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in 1..6) {
                    FocusCard(onClick = { frame = k }, modifier = Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                            PlaceholderArt(Placeholder.FILM, Modifier.fillMaxSize())
                            AsyncImage(
                                model = section.thumbUrl(item, k),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        } else {
            // Audio covers live on the rate-limited file server; they will be fetched through the
            // connection gate in the next build. For now: drawn artwork.
            PlaceholderArt(
                if (section.id == "radioteatr") Placeholder.RADIO else Placeholder.RECORD,
                Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
    }
}

@Composable
private fun Info(
    section: Section,
    item: Item,
    modifier: Modifier,
    mainButton: FocusRequester,
    onPlay: (Int, Long) -> Unit,
    onDownload: (Boolean) -> Unit,
    onTransfer: (Boolean, Boolean) -> Unit,
    onOpenDownloads: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val isVideo = section.kind == Kind.VIDEO
    val history by History.entries.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { net.family.multiki.stream.HistorySync.requestNow() }
    val entry = history.firstOrNull { it.sectionId == section.id && it.itemId == item.id }
    Column(modifier) {
        FitText(
            item.title,
            style = MaterialTheme.typography.displaySmall,
            color = Palette.Ink,
            maxLines = 3,
            minFontSize = 20.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (item.titleAuto) {
            SmallCaption("Название восстановлено по имени файла и может быть неточным")
        }
        Spacer(Modifier.height(10.dp))
        FactLine("Длительность", formatDuration(item.durationSec))
        FactLine("Размер", formatSize(item.size))
        item.parts.firstOrNull()?.resolution?.takeIf { it.isNotBlank() }?.let { FactLine("Изображение", it) }
        FactLine("Добавлено в архив", item.added?.let(::russianDate) ?: "")

        if (entry != null) {
            Spacer(Modifier.height(8.dp))
            Text(resumeLine(entry, isVideo), style = MaterialTheme.typography.titleMedium, color = Palette.Teal)
            ProgressStrip(entry.progress, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        val verb = if (isVideo) "Смотреть" else "Слушать"
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entry != null && entry.canResume) {
                PosterButton(
                    "Продолжить с ${formatMs(entry.positionMs)}",
                    onClick = { onPlay(entry.part, entry.positionMs) },
                    modifier = Modifier.focusRequester(mainButton),
                )
                PosterButton("Сначала", onClick = { onPlay(0, 0L) }, color = Palette.InkSoft)
            } else {
                PosterButton(
                    if (entry?.finished == true) "$verb снова" else verb,
                    onClick = { onPlay(0, 0L) },
                    modifier = Modifier.focusRequester(mainButton),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        DownloadButton(section, item, onDownload, onTransfer, onOpenDownloads)

        if (item.parts.size > 1) {
            Spacer(Modifier.height(20.dp))
            Text(
                if (isVideo) "СЕРИИ" else "ЗАПИСИ",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Red,
            )
            Spacer(Modifier.height(6.dp))
            var lastGroup: String? = null
            item.parts.forEach { part ->
                if (!isVideo && part.group.isNotBlank() && part.group != lastGroup) {
                    lastGroup = part.group
                    Text(
                        part.group,
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Teal,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }
                val index = item.parts.indexOf(part)
                val here = entry != null && entry.canResume && entry.part == index
                PartRow(isVideo, part, stoppedAt = if (here) entry?.positionMs else null) {
                    onPlay(index, if (here) entry?.positionMs ?: 0L else 0L)
                }
            }
        }

        item.description?.let {
            Spacer(Modifier.height(20.dp))
            Text("С КОНВЕРТА ПЛАСТИНКИ", style = MaterialTheme.typography.titleLarge, color = Palette.Red)
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.titleMedium, color = Palette.InkSoft)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
    }
}

@Composable
private fun PartRow(isVideo: Boolean, part: Part, stoppedAt: Long? = null, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1f) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val name = if (isVideo) "Серия ${part.n}" else prettyTrackName(part.file)
            Text(name, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.weight(1f))
            val meta = listOfNotNull(
                stoppedAt?.let { "остановились на ${formatMs(it)}" },
                formatDuration(part.durationSec).ifBlank { null },
                formatSize(part.size).ifBlank { null },
            )
            SmallCaption(meta.joinToString(" · "))
        }
    }
}

/** "03_Kolobok.mp3" -> "03 Kolobok" — readable track name until proper titles are known. */
private fun prettyTrackName(file: String): String {
    val stem = file.substringBeforeLast('.')
    val decoded = runCatching { java.net.URLDecoder.decode(stem.replace("+", "%2B"), "UTF-8") }.getOrDefault(stem)
    return decoded.replace('_', ' ').trim()
}

private val months = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)

/** "2012-02-27" -> "27 февраля 2012" */
private fun russianDate(iso: String): String {
    val p = iso.split('-')
    if (p.size != 3) return iso
    val m = p[1].toIntOrNull() ?: return iso
    val d = p[2].toIntOrNull() ?: return iso
    return "$d ${months.getOrElse(m - 1) { "" }} ${p[0]}"
}

/** "Остановились: серия 2, 12:34 · сегодня, 21:30" or "Просмотрено · вчера, 19:05". */
fun resumeLine(e: HistoryEntry, isVideo: Boolean): String {
    val whenText = formatWhen(e.updated)
    if (e.finished) {
        val where = if (e.deviceId != History.deviceId && e.device.isNotBlank()) " · на «${e.device}»" else ""
        return (if (isVideo) "Просмотрено" else "Прослушано") + " · $whenText$where"
    }
    val unit = if (isVideo) "серия" else "запись"
    val partText = if (e.partsCount > 1) "$unit ${e.part + 1} из ${e.partsCount}, " else ""
    val where = if (e.deviceId != History.deviceId && e.device.isNotBlank()) " · на «${e.device}»" else ""
    return "Остановились: $partText${formatMs(e.positionMs)} · $whenText$where"
}

/** Thin red progress line; mustard when finished. */
@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.height(4.dp)) {
        drawRect(Palette.PaperDark)
        drawRect(
            if (progress >= 1f) Palette.Mustard else Palette.Red,
            size = androidx.compose.ui.geometry.Size(size.width * progress.coerceIn(0f, 1f), size.height),
        )
    }
}

/**
 * Where the title is saved and what can be done: «Скачать» (asks: this device or the home server),
 * «На сервер» / «На это устройство» to copy or move between them. Asks for notification permission once.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DownloadButton(
    section: Section,
    item: Item,
    onDownload: (Boolean) -> Unit,
    onTransfer: (Boolean, Boolean) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val tasks by Downloads.tasks.collectAsState()
    val smb by net.family.multiki.stream.SmbStore.settings.collectAsState()
    val library by net.family.multiki.stream.SmbStore.library.collectAsState()
    val local = Downloads.item(section.id, item.id, tasks, toServer = false)
    val serverTask = Downloads.item(section.id, item.id, tasks, toServer = true)
    val lib = library.items.firstOrNull { it.sectionId == section.id && it.itemId == item.id }
    val onDevice = local?.done == true
    val onServer = lib?.complete == true
    var dialog by remember { mutableStateOf<String?>(null) } // "where", "toServer", "toDevice", "stop"
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun withPermission(action: () -> Unit) = action() // Windows: no permission needed

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onDevice) SmallCaption("✓ На этом устройстве — смотрится без интернета")
        if (onServer) SmallCaption("✓ На домашнем сервере — смотрится на всех устройствах")
        if (local?.active == true) SmallCaption("Загружается на устройство: ${local.percent}%" + (local.current?.message?.let { " · $it" } ?: ""))
        if (serverTask?.active == true) SmallCaption("Загружается на сервер: ${serverTask.percent}%" + (serverTask.current?.message?.let { " · $it" } ?: ""))
        if (lib != null && !onServer && lib.busyElsewhere) SmallCaption("Загружается на сервер с устройства «${lib.busyDevice}»")
        val unfinished = listOfNotNull(local, serverTask).filter { !it.done }
        val running = unfinished.filter { it.active }
        val stopped = unfinished.filter { !it.active } // paused by the user or stopped after an error
        unfinished.firstOrNull { it.failed && !it.active }?.tasks?.firstOrNull { it.state == DState.ERROR }?.message?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Red)
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                running.isNotEmpty() -> {
                    PosterButton("❚❚ Пауза", onClick = { running.forEach { Downloads.pause(it.key) } }, color = Palette.Teal)
                    PosterButton("■ Остановить", onClick = { dialog = "stop" }, color = Palette.Red)
                    PosterButton("Загрузки", onClick = onOpenDownloads, color = Palette.InkSoft)
                }
                stopped.isNotEmpty() -> {
                    PosterButton("▶ Продолжить загрузку", onClick = { withPermission { stopped.forEach { Downloads.resume(it.key) } } }, color = Palette.Teal)
                    PosterButton("■ Отменить", onClick = { dialog = "stop" }, color = Palette.Red)
                }
                !onDevice && !onServer -> PosterButton(
                    "Скачать",
                    onClick = { if (smb.configured) dialog = "where" else withPermission { onDownload(false) } },
                    color = Palette.Teal,
                )
                else -> {
                    if (onServer && !onDevice) PosterButton("На это устройство", onClick = { dialog = "toDevice" }, color = Palette.Teal)
                    if (onDevice && !onServer && smb.configured) PosterButton("На сервер", onClick = { dialog = "toServer" }, color = Palette.Teal)
                    PosterButton("Загруженное", onClick = onOpenDownloads, color = Palette.InkSoft)
                }
            }
        }
    }

    when (dialog) {
        "stop" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Остановить загрузку?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Уже скачанная часть будет удалена. Чтобы потом продолжить с того же места, нажмите «Пауза».",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                DialogButtons(
                    listOf(
                        DialogAction("Остановить и удалить", Palette.Red) {
                            dialog = null
                            listOfNotNull(local, serverTask).filter { !it.done }.forEach { Downloads.delete(it.key) }
                        },
                        DialogAction("Не останавливать", Palette.InkSoft) { dialog = null },
                    )
                )
            },
            containerColor = Palette.Paper,
        )
        "where" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Куда сохранить?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "На это устройство — смотреть без интернета здесь.\nНа домашний сервер — смотреть на всех телевизорах и планшетах дома.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                DialogButtons(
                    listOf(
                        DialogAction("На это устройство", Palette.Teal) { dialog = null; withPermission { onDownload(false) } },
                        DialogAction("На сервер", Palette.Red) { dialog = null; withPermission { onDownload(true) } },
                        DialogAction("Отмена", Palette.InkSoft) { dialog = null },
                    )
                )
            },
            containerColor = Palette.Paper,
        )
        "toServer", "toDevice" -> {
            val toServer = dialog == "toServer"
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (toServer) "Перенести на сервер?" else "Перенести на это устройство?", style = MaterialTheme.typography.headlineSmall) },
                text = {
                    Text(
                        "Копирование идёт по домашней сети, без интернета. «Копировать» оставит файл в обоих местах, " +
                            "«Перенести» удалит старую копию после успешного копирования.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                confirmButton = {
                    DialogButtons(
                        listOf(
                            DialogAction("Копировать", Palette.Teal) { dialog = null; withPermission { onTransfer(toServer, false) } },
                            DialogAction("Перенести", Palette.Red) { dialog = null; withPermission { onTransfer(toServer, true) } },
                            DialogAction("Отмена", Palette.InkSoft) { dialog = null },
                        )
                    )
                },
                containerColor = Palette.Paper,
            )
        }
    }
}
