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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.family.multiki.LocalFullscreen
import net.family.multiki.MainViewModel
import net.family.multiki.Screen
import net.family.multiki.data.History
import net.family.multiki.data.HistoryEntry
import net.family.multiki.data.Item
import net.family.multiki.data.Section
import net.family.multiki.data.nextAfter
import net.family.multiki.player.VideoFrames
import net.family.multiki.player.Vlc
import net.family.multiki.stream.Streams
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import javax.swing.SwingUtilities

/**
 * One film being watched. Lives outside the screens, so the same playback can be shown in the program
 * window or in the borderless full-screen window without restarting.
 */
class VideoSession(
    private val vm: MainViewModel,
    val section: Section,
    val item: Item,
    private val startPart: Int,
    startMs: Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var part by mutableIntStateOf(startPart.coerceIn(0, item.parts.lastIndex))
        private set
    var timeMs by mutableLongStateOf(0L)
    var lengthMs by mutableLongStateOf(item.parts[part].durationSec * 1000L)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableFloatStateOf(0f)
        private set
    var started by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var controlsVisible by mutableStateOf(true)
    var interaction by mutableIntStateOf(0)
    // «Что дальше?» after the last series
    var nextUp by mutableStateOf(false)
        private set
    var nextRandom by mutableStateOf(false)
        private set
    var nextItem by mutableStateOf<Item?>(null)
        private set
    var secondsLeft by mutableIntStateOf(10)
        private set

    private var finishedAll = false
    private var pendingSeek = startMs // resume point, applied once playback starts
    private var countdown: Job? = null
    private var disposed = false

    val frames = VideoFrames()
    val player: EmbeddedMediaPlayer = Vlc.factory!!.mediaPlayers().newEmbeddedMediaPlayer().also { p ->
        p.videoSurface().set(Vlc.factory!!.videoSurfaces().newVideoSurface(frames, frames, true))
    }

    private val events = object : MediaPlayerEventAdapter() {
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
                ui { saveHistory(finished = false) } // "started" counts as history too
            }
        }
        override fun paused(mediaPlayer: MediaPlayer) {
            playing = false
            ui {
                saveHistory(finished = false)
                net.family.multiki.stream.HistorySync.flush()
            }
        }
        override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache }
        override fun error(mediaPlayer: MediaPlayer) {
            playing = false
            error = "Не удалось воспроизвести файл"
        }
        override fun finished(mediaPlayer: MediaPlayer) {
            playing = false
            ui { onPartEnded() }
        }
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater { if (!disposed) block() }

    init {
        player.events().addMediaPlayerEventListener(events)
        startPart(part)
        // remember the position every 5 s while watching
        scope.launch {
            while (true) {
                delay(5_000)
                if (playing && started) saveHistory(finished = false)
            }
        }
    }

    private fun startPart(p: Int) {
        part = p
        timeMs = 0
        lengthMs = item.parts[p].durationSec * 1000L
        started = false
        error = null
        if (p != startPart) pendingSeek = 0
        frames.clear()
        // saved on this computer or on the home server → play the file, otherwise through the caching proxy
        player.media().play(net.family.multiki.stream.playUri(section, item, p), ":network-caching=3000")
        interaction++
    }

    fun goToPart(p: Int) {
        if (p in item.parts.indices && p != part) {
            saveHistory(finished = false)
            startPart(p)
        }
    }

    fun retry() {
        error = null
        pendingSeek = timeMs
        player.media().play(net.family.multiki.stream.playUri(section, item, part), ":network-caching=3000")
    }

    private fun onPartEnded() {
        if (part < item.parts.lastIndex) {
            startPart(part + 1)
            return
        }
        saveHistory(finished = true)
        finishedAll = true
        controlsVisible = false
        chooseNext(random = false)
        if (nextItem == null) vm.back()
    }

    fun chooseNext(random: Boolean) {
        nextRandom = random
        nextItem = section.nextAfter(item.id, random)
        nextUp = nextItem != null
        if (!nextUp) return
        // countdown: restarts when the choice (order / random) changes
        countdown?.cancel()
        countdown = scope.launch {
            secondsLeft = 10
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft -= 1
            }
            goNext()
        }
    }

    fun goNext() {
        val n = nextItem ?: return
        countdown?.cancel()
        nextUp = false
        val resume = History.get(section.id, n.id)?.takeIf { it.canResume }
        vm.replaceTop(Screen.VideoPlayer(section.id, n.id, resume?.part ?: 0, resume?.positionMs ?: 0L))
    }

    fun stopNextUp() {
        countdown?.cancel()
        nextUp = false
        vm.back()
    }

    fun showControls() {
        controlsVisible = true
        interaction++
    }

    fun toggle() {
        if (player.status().isPlaying) player.controls().pause() else player.controls().play()
        showControls()
    }

    /** «Стоп»: stop completely and return to the title card (the place is kept in the history). */
    fun stop() {
        runCatching { player.controls().setPause(true) }
        vm.closeTo(Screen.Details(section.id, item.id))
    }

    fun seekTo(ms: Long) {
        player.controls().setTime(ms)
        timeMs = ms
        interaction++
    }

    fun seekBy(delta: Long) {
        seekTo((player.status().time() + delta).coerceIn(0L, maxOf(0L, lengthMs - 1000)))
    }

    private fun saveHistory(finished: Boolean) {
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

    fun dispose() {
        if (disposed) return
        if (!finishedAll && started) saveHistory(finished = false)
        disposed = true
        net.family.multiki.stream.HistorySync.flush() // so another device can continue right away
        scope.cancel()
        player.events().removeMediaPlayerEventListener(events)
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        Streams.closeLane(section.id) // stop downloading this film in the background
    }
}

/** Creates the session for a video screen (after its section is loaded) and ends it when the screen is left. */
@Composable
fun rememberVideoSession(vm: MainViewModel, screen: Screen.VideoPlayer): VideoSession? {
    val section by produceState<Section?>(initialValue = null, screen.sectionId) {
        value = runCatching { vm.section(screen.sectionId) }.getOrNull()
    }
    val s = section ?: return null
    val item = s.items.firstOrNull { it.id == screen.itemId } ?: return null
    if (Vlc.factory == null) return null
    val session = remember(screen, s) { VideoSession(vm, s, item, screen.part, screen.startMs) }
    DisposableEffect(session) { onDispose { session.dispose() } }
    return session
}

/** The video screen in the program window. */
@Composable
fun VideoPlayerScreen(vm: MainViewModel, session: VideoSession?) {
    val fullscreen = LocalFullscreen.current
    when {
        session != null && fullscreen.isOn ->
            // the picture is in the full-screen window; here only a quiet placeholder
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("Показывается во весь экран · Esc — вернуть в окно", style = MaterialTheme.typography.titleMedium, color = Palette.PaperDark)
            }
        session != null -> VideoPlayerView(vm, session)
        Vlc.factory == null && Vlc.error != null ->
            Box(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Vlc.error ?: "Не удалось запустить VLC", style = MaterialTheme.typography.headlineSmall, color = Palette.Paper)
                    Gap(12.dp)
                    PosterButton("← Назад", onClick = { vm.back() })
                }
            }
        else -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { FilmLeaderLoader() }
    }
}

/** The picture with the controls on top — used both in the window and in full screen. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoPlayerView(vm: MainViewModel, s: VideoSession) {
    val fullscreen = LocalFullscreen.current
    val item = s.item
    val rootFocus = remember { FocusRequester() }
    val toggleFocus = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var focusToggle by remember { mutableIntStateOf(0) }

    // hide the controls 4 s after the last interaction while playing
    LaunchedEffect(s.controlsVisible, s.interaction, s.playing) {
        if (s.controlsVisible && s.playing) {
            delay(4000)
            s.controlsVisible = false
            runCatching { rootFocus.requestFocus() }
        }
    }
    /** Space / Enter while nothing in the controls has focus: pause/play and keep the focus on Play/Pause. */
    fun toggleFromKeyboard() {
        s.toggle()
        focusToggle++
    }
    LaunchedEffect(focusToggle) {
        if (focusToggle > 0) {
            delay(60)
            runCatching { toggleFocus.requestFocus() }
        }
    }

    val streams by Streams.latest.collectAsState()
    val status = streams[s.section.id]?.status?.collectAsState()?.value

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.key == Key.Escape) {
                    if (fullscreen.isOn) fullscreen.set(false) else vm.back()
                    return@onPreviewKeyEvent true
                }
                if (s.nextUp) return@onPreviewKeyEvent false // the «Что дальше?» buttons handle the keyboard
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { toggleFromKeyboard(); true }
                    Key.MediaStop -> { s.stop(); true }
                    Key.MediaFastForward -> { s.seekBy(30_000); true }
                    Key.MediaRewind -> { s.seekBy(-30_000); true }
                    Key.Spacebar -> { toggleFromKeyboard(); true }
                    Key.F, Key.F11 -> { fullscreen.toggle(); true }
                    Key.PageDown -> { s.goToPart(s.part + 1); true }
                    Key.PageUp -> { s.goToPart(s.part - 1); true }
                    Key.DirectionLeft -> if (!s.controlsVisible || rootFocused) { s.seekBy(-10_000); s.showControls(); true } else { s.interaction++; false }
                    Key.DirectionRight -> if (!s.controlsVisible || rootFocused) { s.seekBy(10_000); s.showControls(); true } else { s.interaction++; false }
                    Key.Enter, Key.NumPadEnter ->
                        if (!s.controlsVisible || rootFocused) { toggleFromKeyboard(); true } else { s.interaction++; false }
                    Key.DirectionUp, Key.DirectionDown ->
                        if (!s.controlsVisible) { s.showControls(); runCatching { toggleFocus.requestFocus() }; true } else { s.interaction++; false }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { if (!s.nextUp) s.showControls() }
            .pointerInput(s) {
                detectTapGestures(
                    onTap = { if (!s.nextUp) s.toggle() },
                    onDoubleTap = { fullscreen.toggle() },
                )
            }
    ) {
        // the picture: as large as the screen allows, in its own proportions (black bars, never stretched)
        val img = s.frames.image
        if (img != null) {
            Canvas(Modifier.fillMaxSize()) {
                val a = s.frames.aspect
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

        if (!s.started && s.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                FilmLeaderLoader(caption = "Заряжаем плёнку…")
                StreamStatusLine(status, dark = true, modifier = Modifier.padding(top = 8.dp))
            }
        } else if (s.playing && s.buffering < 100f && s.buffering > 0f) {
            FilmLeaderLoader(caption = null, size = 72.dp, modifier = Modifier.align(Alignment.Center))
        }

        s.error?.let {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, style = MaterialTheme.typography.headlineSmall, color = Palette.Paper)
                StreamStatusLine(status, dark = true)
                Gap(12.dp)
                PosterButton("Попробовать снова", onClick = { s.retry() })
            }
        }

        if (s.nextUp) {
            val n = s.nextItem
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                if (n != null) {
                    NextUpPanel(
                        title = n.title,
                        secondsLeft = s.secondsLeft,
                        random = s.nextRandom,
                        onNow = { s.goNext() },
                        onStop = { s.stopNextUp() },
                        onOrder = { s.chooseNext(random = false) },
                        onRandom = { s.chooseNext(random = true) },
                        dark = true,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        AnimatedVisibility(visible = s.controlsVisible && !s.nextUp, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterButton("← Назад", onClick = { if (fullscreen.isOn) fullscreen.set(false); vm.back() }, color = Palette.RedDark)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.parts.size > 1) {
                            Text("Серия ${s.part + 1} из ${item.parts.size}", style = MaterialTheme.typography.titleMedium, color = Palette.PaperDark)
                        }
                    }
                    PosterButton(
                        if (fullscreen.isOn) "В окно" else "На весь экран",
                        onClick = { fullscreen.toggle() },
                        color = Palette.RedDark,
                    )
                    if (!fullscreen.isOn) {
                        Spacer(Modifier.width(8.dp))
                        PosterButton("Главная", onClick = { vm.home() }, color = Palette.RedDark)
                    }
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
                        s.timeMs,
                        s.lengthMs,
                        onSeek = { s.seekTo(it) },
                        dark = true,
                        modifier = Modifier.fillMaxWidth(),
                        onActivity = { s.interaction++ },
                    )
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        PlayerControlsRow(
                            isPlaying = s.playing,
                            onPrev = if (s.part > 0) ({ s.goToPart(s.part - 1) }) else null,
                            onRewind = { s.seekBy(-10_000) },
                            onToggle = { s.toggle() },
                            onForward = { s.seekBy(10_000) },
                            onNext = if (s.part < item.parts.lastIndex) ({ s.goToPart(s.part + 1) }) else null,
                            toggleModifier = Modifier.focusRequester(toggleFocus),
                            onStop = { if (fullscreen.isOn) fullscreen.set(false); s.stop() },
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
