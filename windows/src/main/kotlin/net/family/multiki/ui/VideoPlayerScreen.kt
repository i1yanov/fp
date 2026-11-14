package net.family.multiki.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.family.multiki.LocalFullscreen
import net.family.multiki.MainViewModel
import net.family.multiki.Screen
import net.family.multiki.data.History
import net.family.multiki.data.HistoryEntry
import net.family.multiki.data.Section
import net.family.multiki.data.nextAfter
import net.family.multiki.player.VideoFrames
import net.family.multiki.player.Vlc
import net.family.multiki.stream.Streams
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

@Composable
fun VideoPlayerScreen(vm: MainViewModel, sectionId: String, itemId: String, startPart: Int, startMs: Long = 0L) {
    val loaded by produceState<Section?>(initialValue = null, sectionId) {
        value = runCatching { vm.section(sectionId) }.getOrNull()
    }
    val section = loaded
    val item = section?.items?.firstOrNull { it.id == itemId }
    if (section == null || item == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { FilmLeaderLoader() }
        return
    }
    if (Vlc.factory == null) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Vlc.error ?: "Не удалось запустить VLC", style = MaterialTheme.typography.headlineSmall, color = Palette.Paper)
                Gap(12.dp)
                PosterButton("← Назад", onClick = { vm.back() })
            }
        }
        return
    }
    VideoPlayer(vm, section, item, startPart, startMs)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VideoPlayer(vm: MainViewModel, section: Section, item: net.family.multiki.data.Item, startPart: Int, startMs: Long) {
    val fullscreen = LocalFullscreen.current
    var part by remember { mutableIntStateOf(startPart.coerceIn(0, item.parts.lastIndex)) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var lengthMs by remember { mutableLongStateOf(item.parts[part].durationSec * 1000L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableFloatStateOf(0f) }
    var started by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var finishedAll by remember { mutableStateOf(false) }
    // «Что дальше?» after the last series
    var nextUp by remember { mutableStateOf(false) }
    var nextRandom by remember { mutableStateOf(false) }
    var nextItem by remember { mutableStateOf<net.family.multiki.data.Item?>(null) }
    var secondsLeft by remember { mutableIntStateOf(10) }
    var interaction by remember { mutableIntStateOf(0) }
    var pendingSeek by remember { mutableLongStateOf(startMs) } // resume point, applied once playback starts
    var endedPart by remember { mutableIntStateOf(-1) }        // set from VLC's thread, handled on the UI thread
    val rootFocus = remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var focusToggle by remember { mutableIntStateOf(0) }

    fun saveHistory(finished: Boolean) {
        // nothing meaningful yet: don't overwrite an earlier resume point with zero
        if (!finished && !started && pendingSeek > 0) return
        History.update(
            HistoryEntry(
                sectionId = section.id,
                itemId = item.id,
                title = item.title,
                sectionTitle = section.title,
                isVideo = true,
                part = if (finished) 0 else part,
                partsCount = item.parts.size,
                positionMs = if (finished) 0 else timeMs,
                durationMs = lengthMs,
                updated = System.currentTimeMillis(),
                finished = finished,
            )
        )
    }

    val frames = remember { VideoFrames() }
    val player = remember {
        Vlc.factory!!.mediaPlayers().newEmbeddedMediaPlayer().also { p ->
            p.videoSurface().set(Vlc.factory!!.videoSurfaces().newVideoSurface(frames, frames, true))
        }
    }

    DisposableEffect(Unit) {
        val events = object : MediaPlayerEventAdapter() {
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { timeMs = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { if (newLength > 0) lengthMs = newLength }
            override fun playing(mediaPlayer: MediaPlayer) {
                playing = true
                error = null
                val seek = pendingSeek
                if (seek > 0) {
                    pendingSeek = 0
                    timeMs = seek
                    mediaPlayer.submit { mediaPlayer.controls().setTime(seek) }
                }
                if (!started) {
                    started = true
                    saveHistory(finished = false) // "started" counts as history too
                }
            }
            override fun paused(mediaPlayer: MediaPlayer) {
                playing = false
                saveHistory(finished = false)
                net.family.multiki.stream.HistorySync.flush()
            }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache }
            override fun error(mediaPlayer: MediaPlayer) {
                playing = false
                error = "Не удалось воспроизвести файл"
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                playing = false
                endedPart = part
            }
        }
        player.events().addMediaPlayerEventListener(events)
        onDispose {
            if (!finishedAll && started) saveHistory(finished = false)
            net.family.multiki.stream.HistorySync.flush() // so another device can continue right away
            player.events().removeMediaPlayerEventListener(events)
            runCatching { player.controls().stop() }
            runCatching { player.release() }
            Streams.closeLane(section.id) // stop downloading this film in the background
            if (fullscreen.isOn) fullscreen.set(false)
        }
    }

    // end of a series: next series, or «Что дальше?» after the last one
    LaunchedEffect(endedPart) {
        if (endedPart < 0) return@LaunchedEffect
        endedPart = -1
        if (part < item.parts.lastIndex) {
            part += 1
        } else {
            saveHistory(finished = true)
            finishedAll = true
            nextRandom = false
            nextItem = section.nextAfter(item.id, random = false)
            controlsVisible = false
            nextUp = nextItem != null
            if (nextItem == null) vm.back()
        }
    }

    // (re)start when the series changes
    LaunchedEffect(part) {
        val p = item.parts[part]
        timeMs = 0
        lengthMs = p.durationSec * 1000L
        started = false
        error = null
        if (part != startPart) pendingSeek = 0
        frames.clear()
        // saved on this computer or on the home server → play the file, otherwise through the caching proxy
        val uri = net.family.multiki.stream.playUri(section, item, part)
        player.media().play(uri, ":network-caching=3000")
        interaction++
    }

    fun goNext() {
        val n = nextItem ?: return
        nextUp = false
        val resume = History.get(section.id, n.id)?.takeIf { it.canResume }
        vm.replaceTop(Screen.VideoPlayer(section.id, n.id, resume?.part ?: 0, resume?.positionMs ?: 0L))
    }

    // countdown: restarts when the choice (order / random) changes
    LaunchedEffect(nextUp, nextItem) {
        if (!nextUp) return@LaunchedEffect
        secondsLeft = 10
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
        goNext()
    }

    // remember the position every 5 s while watching
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (playing && started) saveHistory(finished = false)
        }
    }

    // hide the controls 4 s after the last interaction while playing
    LaunchedEffect(controlsVisible, interaction, playing) {
        if (controlsVisible && playing) {
            delay(4000)
            controlsVisible = false
            runCatching { rootFocus.requestFocus() }
        }
    }

    fun showControls() {
        controlsVisible = true
        interaction++
    }
    fun toggle() {
        if (player.status().isPlaying) player.controls().pause() else player.controls().play()
        showControls()
    }
    /** Space / Enter while nothing in the controls has focus: pause/play and keep the focus on Play/Pause. */
    fun toggleFromKeyboard() {
        toggle()
        focusToggle++
    }
    LaunchedEffect(focusToggle) {
        if (focusToggle > 0) {
            delay(60)
            runCatching { toggleFocus.requestFocus() }
        }
    }
    /** «Стоп»: stop completely and return to the title card (the place is kept in the history). */
    fun stopPlayback() {
        runCatching { player.controls().setPause(true) }
        vm.closeTo(Screen.Details(section.id, item.id))
    }
    fun seekBy(delta: Long) {
        val target = (player.status().time() + delta).coerceIn(0L, maxOf(0L, lengthMs - 1000))
        player.controls().setTime(target)
        timeMs = target
    }

    val streams by Streams.latest.collectAsState()
    val status = streams[section.id]?.status?.collectAsState()?.value

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (nextUp) return@onPreviewKeyEvent false // the «Что дальше?» buttons handle the keyboard
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { toggleFromKeyboard(); true }
                    Key.MediaStop -> { stopPlayback(); true }
                    Key.MediaFastForward -> { seekBy(30_000); true }
                    Key.MediaRewind -> { seekBy(-30_000); true }
                    Key.Spacebar -> { toggleFromKeyboard(); true }
                    Key.F, Key.F11 -> { fullscreen.toggle(); true }
                    Key.Escape -> {
                        if (fullscreen.isOn) fullscreen.set(false) else vm.back()
                        true
                    }
                    Key.PageDown -> { if (part < item.parts.lastIndex) part += 1; true }
                    Key.PageUp -> { if (part > 0) part -= 1; true }
                    Key.DirectionLeft -> if (!controlsVisible || rootFocused) { seekBy(-10_000); showControls(); true } else { interaction++; false }
                    Key.DirectionRight -> if (!controlsVisible || rootFocused) { seekBy(10_000); showControls(); true } else { interaction++; false }
                    Key.Enter, Key.NumPadEnter ->
                        if (!controlsVisible || rootFocused) { toggleFromKeyboard(); true } else { interaction++; false }
                    Key.DirectionUp, Key.DirectionDown ->
                        if (!controlsVisible) { showControls(); runCatching { toggleFocus.requestFocus() }; true } else { interaction++; false }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { if (!nextUp) showControls() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!nextUp) toggle() },
                    onDoubleTap = { fullscreen.toggle() },
                )
            }
    ) {
        // the picture, letterboxed to its own aspect ratio
        val img = frames.image
        if (img != null) {
            Canvas(Modifier.fillMaxSize()) {
                val a = frames.aspect
                var w = size.width
                var h = w / a
                if (h > size.height) {
                    h = size.height
                    w = h * a
                }
                drawImage(
                    img,
                    dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                )
            }
        }

        if (!started && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                FilmLeaderLoader(caption = "Заряжаем плёнку…")
                StreamStatusLine(status, dark = true, modifier = Modifier.padding(top = 8.dp))
            }
        } else if (playing && buffering < 100f && buffering > 0f) {
            FilmLeaderLoader(caption = null, size = 72.dp, modifier = Modifier.align(Alignment.Center))
        }

        error?.let {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, style = MaterialTheme.typography.headlineSmall, color = Palette.Paper)
                StreamStatusLine(status, dark = true)
                Gap(12.dp)
                PosterButton("Попробовать снова", onClick = {
                    error = null
                    val uri = net.family.multiki.stream.playUri(section, item, part)
                    pendingSeek = timeMs
                    player.media().play(uri, ":network-caching=3000")
                })
            }
        }

        if (nextUp) {
            val n = nextItem
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                if (n != null) {
                    NextUpPanel(
                        title = n.title,
                        secondsLeft = secondsLeft,
                        random = nextRandom,
                        onNow = { goNext() },
                        onStop = {
                            nextUp = false
                            vm.back()
                        },
                        onOrder = {
                            nextRandom = false
                            nextItem = section.nextAfter(item.id, random = false)
                        },
                        onRandom = {
                            nextRandom = true
                            nextItem = section.nextAfter(item.id, random = true)
                        },
                        dark = true,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        AnimatedVisibility(visible = controlsVisible && !nextUp, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterButton("← Назад", onClick = { vm.back() }, color = Palette.RedDark)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.parts.size > 1) {
                            Text("Серия ${part + 1} из ${item.parts.size}", style = MaterialTheme.typography.titleMedium, color = Palette.PaperDark)
                        }
                    }
                    PosterButton(
                        if (fullscreen.isOn) "Окно" else "На весь экран",
                        onClick = { fullscreen.toggle() },
                        color = Palette.RedDark,
                    )
                    Spacer(Modifier.width(8.dp))
                    PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
                }
                // bottom controls
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SeekBar(
                        timeMs,
                        lengthMs,
                        onSeek = {
                            player.controls().setTime(it)
                            timeMs = it
                            interaction++
                        },
                        dark = true,
                        modifier = Modifier.fillMaxWidth(),
                        onActivity = { interaction++ },
                    )
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        PlayerControlsRow(
                            isPlaying = playing,
                            onPrev = if (part > 0) ({ part -= 1 }) else null,
                            onRewind = { seekBy(-10_000); interaction++ },
                            onToggle = { toggle() },
                            onForward = { seekBy(10_000); interaction++ },
                            onNext = if (part < item.parts.lastIndex) ({ part += 1 }) else null,
                            toggleModifier = Modifier.focusRequester(toggleFocus),
                            onStop = { stopPlayback() },
                        )
                    }
                    Text(
                        "Пробел — пауза · ← → — перемотка · F — на весь экран · Esc — выход",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.PaperDark,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    StreamStatusLine(status, dark = true, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { rootFocus.requestFocus() }
    }
}
