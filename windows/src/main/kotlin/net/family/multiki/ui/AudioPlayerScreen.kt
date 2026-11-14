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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.family.multiki.MainViewModel
import net.family.multiki.data.Section
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.family.multiki.player.AudioConnection
import net.family.multiki.player.AudioExtras
import net.family.multiki.stream.Streams

/** Full audio player: big record, track name, seek bar, controls, list of tracks. */
@Composable
fun AudioPlayerScreen(vm: MainViewModel) {
    val audio = vm.audio
    val st = audio.state
    LaunchedEffect(Unit) {
        while (true) {
            audio.refresh()
            delay(500)
        }
    }
    val section by produceState<Section?>(initialValue = null, st.sectionId) {
        value = if (st.sectionId.isEmpty()) null else runCatching { vm.section(st.sectionId) }.getOrNull()
    }
    val item = section?.items?.firstOrNull { it.id == st.itemId }
    val streams by Streams.latest.collectAsState()
    val status = streams[st.sectionId]?.status?.collectAsState()?.value
    val toggleFocus = remember { FocusRequester() }
    val nextUp by AudioExtras.nextUpFlow.collectAsState()
    val sleep by AudioExtras.sleepFlow.collectAsState()
    var askSleep by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(section?.title ?: "Слушаем") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterButton("← Назад", onClick = { vm.back() }, color = Palette.RedDark)
                PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
            }
        }
        // «Что дальше?» on top, so it is visible without scrolling
        if (nextUp.visible) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                NextUpPanel(
                    title = nextUp.title,
                    secondsLeft = nextUp.secondsLeft,
                    random = nextUp.random,
                    onNow = { AudioExtras.commands?.nextNow() },
                    onStop = { AudioExtras.commands?.stopNext() },
                    onOrder = { AudioExtras.commands?.chooseNext(false) },
                    onRandom = { AudioExtras.commands?.chooseNext(true) },
                )
            }
        }
        if (!st.active) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Сейчас ничего не играет", style = MaterialTheme.typography.bodyLarge, color = Palette.InkSoft)
            }
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val art: @Composable (Modifier) -> Unit = { m ->
                PlaceholderArt(if (st.sectionId == "radioteatr") Placeholder.RADIO else Placeholder.RECORD, m.aspectRatio(1f))
            }
            val controls: @Composable (Modifier) -> Unit = { m ->
                Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                    FitText(
                        st.itemTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Palette.Ink,
                        maxLines = 2,
                        minFontSize = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (st.count > 1) {
                        Text(
                            "${st.index + 1} из ${st.count}: ${st.trackTitle}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Palette.Teal,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Gap(12.dp)
                    SeekBar(st.positionMs, st.durationMs, onSeek = { audio.seekTo(it) }, modifier = Modifier.fillMaxWidth())
                    Gap(8.dp)
                    PlayerControlsRow(
                        isPlaying = st.isPlaying,
                        onPrev = { audio.previous() },
                        onRewind = { audio.seekBy(-15_000) },
                        onToggle = { audio.toggle() },
                        onForward = { audio.seekBy(15_000) },
                        onNext = if (st.index < st.count - 1) ({ audio.next() }) else null,
                        toggleModifier = Modifier.focusRequester(toggleFocus),
                        onStop = {
                            val target = net.family.multiki.Screen.Details(st.sectionId, st.itemId)
                            audio.stop() // the place is saved when playback stops
                            if (st.sectionId.isNotEmpty()) vm.closeTo(target) else vm.back()
                        },
                    )
                    Gap(10.dp)
                    PosterButton(
                        when {
                            sleep.afterTale -> "Сон: после этой сказки"
                            sleep.endAt > 0 -> "Сон через ${formatMs(sleep.endAt - now)}"
                            else -> "Таймер сна"
                        },
                        onClick = { askSleep = true },
                        color = if (sleep.active) Palette.Teal else Palette.InkSoft,
                    )
                    Gap(10.dp)
                    if (st.isBuffering && !st.isPlaying) FilmLeaderLoader(caption = null, size = 56.dp)
                    StreamStatusLine(status)
                    st.error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Red) }
                    SmallCaption("Звук продолжит играть, пока вы смотрите другие разделы")

                    if (item != null && item.parts.size > 1) {
                        Gap(16.dp)
                        Text("ЗАПИСИ", style = MaterialTheme.typography.titleLarge, color = Palette.Red, modifier = Modifier.fillMaxWidth())
                        var lastGroup: String? = null
                        item.parts.forEachIndexed { i, part ->
                            if (part.group.isNotBlank() && part.group != lastGroup) {
                                lastGroup = part.group
                                Text(
                                    part.group,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Teal,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            }
                            FocusCard(
                                onClick = { audio.jumpTo(i) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                background = if (i == st.index) Palette.PaperDark else Palette.Paper,
                                focusScale = 1f,
                            ) {
                                Text(
                                    (if (i == st.index) "▶  " else "") + AudioConnection.trackTitle(item, part.file, part.n),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Palette.Ink,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Gap(24.dp)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(24.dp)) {
                    art(Modifier.weight(0.4f).widthIn(max = 420.dp))
                    Spacer(Modifier.width(28.dp))
                    controls(Modifier.weight(0.6f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    art(Modifier.fillMaxWidth().padding(horizontal = 40.dp))
                    Gap(12.dp)
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
    InitialFocus(toggleFocus)
    if (askSleep) {
        SleepTimerDialog(
            onPick = { minutes, afterTale ->
                AudioExtras.commands?.setSleep(minutes, afterTale)
                askSleep = false
            },
            onDismiss = { askSleep = false },
        )
    }
}
