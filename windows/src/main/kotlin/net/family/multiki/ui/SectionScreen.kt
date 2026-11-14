package net.family.multiki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.family.multiki.MainViewModel
import net.family.multiki.Screen
import net.family.multiki.SectionUi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import net.family.multiki.data.History
import net.family.multiki.stream.DItem
import net.family.multiki.stream.Downloads
import net.family.multiki.data.HistoryEntry
import net.family.multiki.data.Item
import net.family.multiki.data.Kind
import net.family.multiki.data.Section
import net.family.multiki.data.formatCount
import net.family.multiki.data.formatDuration
import net.family.multiki.data.normalize
import net.family.multiki.data.plural

const val PAGE_SIZE = 20

@Composable
fun SectionScreen(vm: MainViewModel, sectionId: String) {
    val loaded by produceState<Result<Section>?>(initialValue = null, sectionId) {
        value = runCatching { vm.section(sectionId) }
    }
    val info = vm.sections?.firstOrNull { it.id == sectionId }
    val section = loaded?.getOrNull()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(section?.title ?: info?.title ?: "") {
            PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
        }
        val error = loaded?.exceptionOrNull()
        when {
            error != null -> LoadError(error)
            section == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FilmLeaderLoader() }
            else -> SectionContent(vm, section)
        }
    }
}

@Composable
private fun SectionContent(vm: MainViewModel, section: Section) {
    var ui by remember(section.id) { mutableStateOf(vm.ui(section.id)) }
    fun update(newUi: SectionUi) {
        ui = newUi
        vm.setUi(section.id, newUi)
    }

    val filtered = remember(section.id, ui.query) {
        val q = normalize(ui.query)
        if (q.isEmpty()) section.items else section.items.filter { it.searchKey.contains(q) }
    }
    val pages = maxOf(1, (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val page = ui.page.coerceIn(0, pages - 1)
    val pageItems = filtered.subList(page * PAGE_SIZE, minOf(filtered.size, (page + 1) * PAGE_SIZE))

    val history by History.entries.collectAsState()
    val watched = remember(history, section.id) {
        history.filter { it.sectionId == section.id }.associateBy { it.itemId }
    }

    val dlTasks by Downloads.tasks.collectAsState()
    val saved = remember(dlTasks, section.id) {
        Downloads.items(dlTasks.filter { it.sectionId == section.id && !it.toServer }).associateBy { it.itemId }
    }
    val smbLibrary by net.family.multiki.stream.SmbStore.library.collectAsState()
    val onServer = remember(smbLibrary, section.id) {
        smbLibrary.items.filter { it.sectionId == section.id && it.complete }.map { it.itemId }.toSet()
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(page, ui.query) { gridState.scrollToItem(0) }
    val firstCard = remember { FocusRequester() }
    val randomFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isTvScreen = net.family.multiki.LocalIsTv.current
    // TV: the search field takes focus without opening the keyboard; OK on the remote starts typing
    var typing by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(typing) {
        if (typing) {
            kotlinx.coroutines.delay(80)
            keyboard?.show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // random + search (on narrow phones the random button gets its own line above the search)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 560.dp
            val search: @Composable (Modifier) -> Unit = { m ->
                OutlinedTextField(
                    value = ui.query,
                    onValueChange = { update(SectionUi(query = it, page = 0)) },
                    singleLine = true,
                    placeholder = { Text("Поиск по названию", style = MaterialTheme.typography.bodyMedium) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        typing = false
                        keyboard?.hide()
                        if (!isTvScreen) focusManager.clearFocus()
                    }),
                    readOnly = isTvScreen && !typing,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Red,
                        unfocusedBorderColor = Palette.InkSoft,
                        focusedContainerColor = Palette.Paper,
                        unfocusedContainerColor = Palette.Paper,
                        cursorColor = Palette.Red,
                    ),
                    modifier = if (!isTvScreen) m else m
                        .onFocusChanged { if (!it.isFocused) typing = false }
                        .onPreviewKeyEvent { e ->
                            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (ok && !typing) {
                                if (e.type == KeyEventType.KeyUp) typing = true
                                true
                            } else false
                        },
                )
            }
            val randomButton: @Composable (Modifier) -> Unit = { m ->
                PosterButton(
                    section.randomLabel,
                    onClick = {
                        val pool = filtered.ifEmpty { section.items }
                        if (pool.isNotEmpty()) vm.open(Screen.Details(section.id, pool.random().id))
                    },
                    color = Palette.Teal,
                    modifier = m,
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (narrow) {
                    randomButton(Modifier.fillMaxWidth().focusRequester(randomFocus))
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!narrow) {
                        randomButton(Modifier.focusRequester(randomFocus))
                        Spacer(Modifier.width(12.dp))
                    }
                    search(Modifier.weight(1f))
                    if (ui.query.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        PosterButton("Сброс", onClick = { update(SectionUi()) }, color = Palette.InkSoft)
                    }
                }
            }
        }
        SmallCaption(
            if (ui.query.isEmpty()) "Всего: ${formatCount(filtered.size)}"
            else "Найдено: ${formatCount(filtered.size)}",
            Modifier.padding(horizontal = 16.dp),
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Ничего не нашлось.\nПопробуйте написать название короче.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = Palette.InkSoft,
                )
            }
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val isTv = net.family.multiki.LocalIsTv.current
            val cols = when {
                isTv -> 10 // TV: half-size cards, the whole page of 20 fits on screen
                maxWidth >= 900.dp -> 5
                maxWidth >= 600.dp -> 4
                else -> 2
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(pageItems, key = { _, it -> it.id }) { index, item ->
                    ItemCard(
                        section = section,
                        item = item,
                        entry = watched[item.id],
                        saved = saved[item.id],
                        onServer = item.id in onServer,
                        onClick = { vm.open(Screen.Details(section.id, item.id)) },
                        modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Pager(
                        page = page,
                        pages = pages,
                        onPage = { update(ui.copy(page = it)) },
                    )
                }
            }
        }
    }
    // TV: start on the first card; if there are no cards (nothing found) — on the random button, never on the search
    InitialFocus(if (filtered.isEmpty()) randomFocus else firstCard, key = page)
}

@Composable
private fun ItemCard(
    section: Section,
    item: Item,
    entry: HistoryEntry?,
    saved: DItem?,
    onServer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = net.family.multiki.LocalIsTv.current
    FocusCard(onClick = onClick, modifier = modifier) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                val art = when {
                    section.kind == Kind.VIDEO -> Placeholder.FILM
                    section.id == "radioteatr" -> Placeholder.RADIO
                    else -> Placeholder.RECORD
                }
                PlaceholderArt(art, Modifier.fillMaxSize())
                val url = section.thumbUrl(item)
                if (url != null) {
                    AsyncImage(
                        model = url,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (saved != null || onServer) {
                    Text(
                        when {
                            saved?.done == true -> if (tv) "⬇" else "⬇ Загружено"
                            onServer -> if (tv) "⌂" else "⌂ На сервере"
                            else -> "⬇ ${saved?.percent ?: 0}%"
                        },
                        style = if (tv) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                        color = Palette.Paper,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Palette.Teal, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (entry != null) {
                    if (entry.finished) {
                        Text(
                            if (tv) "✓" else if (section.kind == Kind.VIDEO) "✓ Просмотрено" else "✓ Прослушано",
                            style = if (tv) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = Palette.Ink,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Palette.Mustard, RoundedCornerShape(3.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    ProgressStrip(entry.progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = if (tv) 4.dp else 6.dp, vertical = if (tv) 4.dp else 6.dp)) {
                // fixed height for two lines so that all cards in a row line up
                FitText(
                    item.title,
                    style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    color = Palette.Ink,
                    maxLines = 2,
                    minFontSize = if (tv) 9.sp else 11.sp,
                    modifier = Modifier.fillMaxWidth().height(if (tv) 34.dp else 42.dp),
                )
                if (tv) {
                    Text(
                        cardCaption(section, item),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.InkSoft,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                } else {
                    SmallCaption(cardCaption(section, item), Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}

private fun cardCaption(section: Section, item: Item): String {
    val n = item.parts.size
    val parts = when {
        n <= 1 -> null
        section.kind == Kind.AUDIO -> "$n ${plural(n, "запись", "записи", "записей")}"
        else -> "$n ${plural(n, "серия", "серии", "серий")}"
    }
    val dur = formatDuration(item.durationSec).ifEmpty { null }
    return listOfNotNull(parts, dur).joinToString(" · ").ifEmpty { " " }
}

@Composable
private fun Pager(page: Int, pages: Int, onPage: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
        val narrow = maxWidth < 520.dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!narrow) {
                PosterButton("«", onClick = { onPage(0) }, enabled = page > 0)
                Spacer(Modifier.width(8.dp))
            }
            PosterButton(if (narrow) "‹" else "‹ Назад", onClick = { onPage(page - 1) }, enabled = page > 0)
            Spacer(Modifier.width(if (narrow) 10.dp else 16.dp))
            Text(
                if (narrow) "${page + 1} из $pages" else "Страница ${page + 1} из $pages",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Ink,
                maxLines = 1,
            )
            Spacer(Modifier.width(if (narrow) 10.dp else 16.dp))
            PosterButton(if (narrow) "›" else "Вперёд ›", onClick = { onPage(page + 1) }, enabled = page < pages - 1)
            if (!narrow) {
                Spacer(Modifier.width(8.dp))
                PosterButton("»", onClick = { onPage(pages - 1) }, enabled = page < pages - 1)
            }
        }
    }
}
