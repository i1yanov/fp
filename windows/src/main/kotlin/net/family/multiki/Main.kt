package net.family.multiki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import net.family.multiki.platform.AppContext
import net.family.multiki.platform.Platform
import net.family.multiki.ui.AudioPlayerScreen
import net.family.multiki.ui.ConflictDialog
import net.family.multiki.ui.CrashDialog
import net.family.multiki.ui.DetailsScreen
import net.family.multiki.ui.DownloadsScreen
import net.family.multiki.ui.HistoryScreen
import net.family.multiki.ui.HomeScreen
import net.family.multiki.ui.MiniPlayer
import net.family.multiki.ui.MultikiTheme
import net.family.multiki.ui.Palette
import net.family.multiki.ui.SectionScreen
import net.family.multiki.ui.SettingsScreen
import net.family.multiki.ui.VideoPlayerScreen
import net.family.multiki.ui.VideoPlayerView
import net.family.multiki.ui.VideoSession
import net.family.multiki.ui.rememberVideoSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowPosition
import java.io.File

/** Shared screens look at this to switch on things meant for a TV remote; a computer is not a TV. */
val LocalIsTv = staticCompositionLocalOf { false }

/**
 * Full screen for the video player (F / F11 / double click, Esc to leave). A borderless window over the whole
 * monitor shows the same playback — an ordinary window would keep the Windows title bar.
 */
class Fullscreen {
    var isOn by mutableStateOf(false)
        private set
    /** Monitor the program window is on (taken when switching on). */
    var bounds: java.awt.Rectangle = java.awt.Rectangle(0, 0, 1280, 720)
        private set
    var owner: java.awt.Window? = null

    fun set(on: Boolean) {
        if (on == isOn) return
        if (on) owner?.graphicsConfiguration?.bounds?.let { bounds = it }
        isOn = on
        if (!on) owner?.let { w -> javax.swing.SwingUtilities.invokeLater { w.toFront(); w.requestFocus() } }
    }
    fun toggle() = set(!isOn)
}

val LocalFullscreen = staticCompositionLocalOf<Fullscreen> { error("no window") }

const val CRASH_FILE = "last_crash.txt"

/** "Black box": if the program crashes, the error text is saved and shown at the next start. */
private fun installCrashRecorder() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            val text = buildString {
                append("Архив Вещания ").append(BuildInfo.VERSION).append(" для Windows\n")
                append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                append(", Java ").append(System.getProperty("java.version")).append("\n\n")
                append(error.stackTraceToString().take(12000))
            }
            File(Platform.dataDir, CRASH_FILE).writeText(text)
        }
        previous?.uncaughtException(thread, error)
    }
}

fun main() {
    installCrashRecorder()
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) } // native folder picker
    net.family.multiki.stream.Streams.init(AppContext)
    net.family.multiki.data.History.init(AppContext)
    net.family.multiki.stream.SmbStore.init(AppContext)
    net.family.multiki.stream.Downloads.init(AppContext)
    net.family.multiki.stream.HistorySync.start()
    net.family.multiki.stream.LocalProxy.start()

    application {
        val vm = remember { MainViewModel() }
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        val fullscreen = remember { Fullscreen() }
        val screen = vm.current
        val session = if (screen is Screen.VideoPlayer) key(screen) { rememberVideoSession(vm, screen) } else null
        // leaving the video (not just switching to the next title) ends full screen
        androidx.compose.runtime.LaunchedEffect(screen is Screen.VideoPlayer) { if (screen !is Screen.VideoPlayer) fullscreen.set(false) }
        Window(
            onCloseRequest = {
                vm.close()
                net.family.multiki.stream.HistorySync.flush()
                exitApplication()
            },
            state = state,
            title = "Архив Вещания",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e ->
                // Alt+← or Backspace outside text fields = «Назад»; F11 anywhere = full screen
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && e.isAltPressed -> vm.back()
                    e.type == KeyEventType.KeyDown && e.key == Key.F11 && session != null -> { fullscreen.toggle(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            fullscreen.owner = window
            if (Smoke.dir != null) androidx.compose.runtime.LaunchedEffect(Unit) { Smoke.run(vm, window, fullscreen) }
            CompositionLocalProvider(LocalIsTv provides false, LocalFullscreen provides fullscreen) {
                MultikiTheme { AppRoot(vm, session) }
            }
        }

        // borderless full-screen window with the same playback
        if (screen is Screen.VideoPlayer && fullscreen.isOn) {
            val b = fullscreen.bounds
            val fsState = rememberWindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(b.x.dp, b.y.dp),
                size = DpSize(b.width.dp, b.height.dp),
            )
            Window(
                onCloseRequest = { fullscreen.set(false) },
                state = fsState,
                title = "Архив Вещания",
                icon = painterResource("icon.png"),
                undecorated = true,
                resizable = false,
                alwaysOnTop = true,
            ) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    window.bounds = b // exactly the monitor, over the taskbar
                    window.toFront()
                    window.requestFocus()
                }
                CompositionLocalProvider(LocalIsTv provides false, LocalFullscreen provides fullscreen) {
                    MultikiTheme {
                        if (session != null) key(session) { VideoPlayerView(vm, session) }
                        else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel, session: VideoSession?) {
    val screen = vm.current
    if (screen is Screen.VideoPlayer) {
        // key: a new title ("Что дальше?") gets a fresh view
        key(screen) { VideoPlayerScreen(vm, session) }
    } else {
        Box(Modifier.fillMaxSize().background(Palette.Red)) {
            Column(Modifier.fillMaxSize().background(Palette.Paper)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.Home -> HomeScreen(vm)
                        is Screen.SectionList -> SectionScreen(vm, screen.sectionId)
                        is Screen.Details -> key(screen) { DetailsScreen(vm, screen.sectionId, screen.itemId) } // fresh state for «Случайный»
                        Screen.Downloads -> DownloadsScreen(vm)
                        Screen.AudioPlayer -> AudioPlayerScreen(vm)
                        Screen.HistoryList -> HistoryScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        is Screen.VideoPlayer -> Unit
                    }
                }
                val st = vm.audio.state
                val nextUp = net.family.multiki.player.AudioExtras.nextUpFlow.collectAsState().value
                if (st.active && screen != Screen.AudioPlayer) {
                    MiniPlayer(
                        title = st.itemTitle,
                        subtitle = when {
                            nextUp.visible -> "Далее через ${nextUp.secondsLeft} с: ${nextUp.title} — нажмите, чтобы выбрать"
                            st.count > 1 -> "${st.index + 1} из ${st.count}: ${st.trackTitle}"
                            else -> "Аудио"
                        },
                        isPlaying = st.isPlaying,
                        onOpen = { vm.open(Screen.AudioPlayer) },
                        onToggle = { vm.audio.toggle() },
                        onStop = { vm.stopAudio() },
                        onTick = { vm.audio.refresh() },
                    )
                }
            }
        }
    }
    vm.conflict?.let { ConflictDialog(it, onCancel = vm::dismissConflict) }
    vm.crashReport?.let { CrashDialog(it, onClose = vm::dismissCrash) }
}
