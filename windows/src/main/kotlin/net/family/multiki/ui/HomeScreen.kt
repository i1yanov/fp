package net.family.multiki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.family.multiki.MainViewModel
import androidx.compose.runtime.collectAsState
import net.family.multiki.Screen
import net.family.multiki.data.History
import net.family.multiki.data.formatCount
import net.family.multiki.data.plural

private data class Tile(val id: String?, val title: String, val subtitle: String, val art: Placeholder, val target: Screen? = null)

private fun countLabel(sectionId: String, n: Int): String {
    val words = when (sectionId) {
        "multiki" -> Triple("мультфильм", "мультфильма", "мультфильмов")
        "audio" -> Triple("сказка", "сказки", "сказок")
        "radioteatr" -> Triple("спектакль", "спектакля", "спектаклей")
        "history" -> Triple("запись", "записи", "записей")
        else -> Triple("фильм", "фильма", "фильмов")
    }
    return "${formatCount(n)} ${plural(n, words.first, words.second, words.third)}"
}

private fun artFor(sectionId: String) = when (sectionId) {
    "multiki" -> Placeholder.PALETTE
    "film" -> Placeholder.CAMERA
    "filmiki" -> Placeholder.TV
    "audio" -> Placeholder.RECORD
    "radioteatr" -> Placeholder.RADIO
    else -> Placeholder.FILM
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    val sections = vm.sections
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Архив Вещания") {
            PosterButton("Настройки", onClick = { vm.open(Screen.Settings) }, color = Palette.RedDark)
        }
        when {
            vm.loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Не удалось открыть каталог: ${vm.loadError}", style = MaterialTheme.typography.bodyLarge)
            }
            sections == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FilmLeaderLoader() }
            else -> {
                val historyCount = History.entries.collectAsState().value.size
                val tiles = sections.map { Tile(it.id, it.title, countLabel(it.id, it.count), artFor(it.id)) } +
                    Tile(
                        null, "История", if (historyCount == 0) "начатое и просмотренное" else countLabel("history", historyCount),
                        Placeholder.HISTORY, Screen.HistoryList,
                    ) +
                    Tile(null, "Загруженное", downloadsLabel(), Placeholder.CAN, Screen.Downloads)
                HomeGrid(tiles) { tile ->
                    val target = tile.target
                    if (target != null) vm.open(target) else if (tile.id != null) vm.open(Screen.SectionList(tile.id))
                }
            }
        }
    }
}

@Composable
private fun HomeGrid(tiles: List<Tile>, onOpen: (Tile) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tv = net.family.multiki.LocalIsTv.current
        val cols = when {
            tv -> tiles.size.coerceAtMost(7) // TV: small tiles, all sections in one row
            maxWidth >= 840.dp -> 3
            maxWidth >= 560.dp -> 3
            else -> 2
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(if (tv) 16.dp else 20.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 14.dp else 20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Архив советского кино, мультипликации, сказок и радиоспектаклей",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.InkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            itemsIndexed(tiles) { index, tile ->
                FocusCard(
                    onClick = { onOpen(tile) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                ) {
                    Column {
                        PlaceholderArt(tile.art, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        Column(
                            Modifier.fillMaxWidth().background(Palette.Red)
                                .padding(horizontal = if (tv) 4.dp else 8.dp, vertical = if (tv) 5.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            FitText(
                                tile.title.uppercase(),
                                style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                                color = Palette.Paper,
                                maxLines = 1,
                                minFontSize = if (tv) 9.sp else 14.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FitText(
                                tile.subtitle,
                                style = if (tv) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                color = Palette.PaperDark,
                                maxLines = if (tv) 2 else 1,
                                minFontSize = 8.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SourceCredit() }
        }
    }
    InitialFocus(firstFocus)
}

/** Where the content comes from: the ArjLover archive. Opens the site in a browser if there is one. */
@Composable
private fun SourceCredit() {
    var noBrowser by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp)
            .background(Palette.PaperDark)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("ИСТОЧНИК", style = MaterialTheme.typography.titleLarge, color = Palette.Red)
        Text(
            "Все фильмы, мультфильмы, сказки и радиоспектакли — из архива «Мультики by ArjLover».\n" +
                "Приложение лишь открывает файлы этого архива; права на произведения принадлежат их правообладателям.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        PosterButton(
            "multiki.arjlover.net",
            onClick = {
                noBrowser = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI("https://multiki.arjlover.net/")) }.isFailure
            },
            color = Palette.Teal,
        )
        if (noBrowser) {
            SmallCaption("Не удалось открыть браузер. Адрес сайта: multiki.arjlover.net", Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun downloadsLabel(): String {
    val tasks = net.family.multiki.stream.Downloads.tasks.collectAsState().value
    val items = net.family.multiki.stream.Downloads.items(tasks)
    val active = items.count { it.active }
    return when {
        active > 0 -> "загружается: $active"
        items.isNotEmpty() -> countLabel("history", items.size) + " без интернета"
        else -> "смотреть без интернета"
    }
}
