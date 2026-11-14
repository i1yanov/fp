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
import androidx.compose.ui.window.WindowState
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
import java.io.File

/** Shared screens look at this to switch on things meant for a TV remote; a computer is not a TV. */
val LocalIsTv = staticCompositionLocalOf { false }

/** Full screen for the video player (F / F11 / double click, Esc to leave). */
class Fullscreen(private val window: WindowState) {
    val isOn: Boolean get() = window.placement == WindowPlacement.Fullscreen
    private var before = WindowPlacement.Floating
    fun set(on: Boolean) {
        if (on == isOn) return
        if (on) {
            before = window.placement
            window.placement = WindowPlacement.Fullscreen
        } else {
            window.placement = before
        }
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
        val fullscreen = remember { Fullscreen(state) }
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
                    e.type == KeyEventType.KeyDown && e.key == Key.F11 -> { fullscreen.toggle(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            CompositionLocalProvider(LocalIsTv provides false, LocalFullscreen provides fullscreen) {
                MultikiTheme { AppRoot(vm) }
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val screen = vm.current
    if (screen is Screen.VideoPlayer) {
        // key: a new title ("Что дальше?") gets a fresh player
        key(screen) { VideoPlayerScreen(vm, screen.sectionId, screen.itemId, screen.part, screen.startMs) }
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
