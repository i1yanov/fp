package net.family.multiki

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.family.multiki.data.CatalogRepository
import net.family.multiki.data.Item
import net.family.multiki.data.Kind
import net.family.multiki.player.AudioConnection
import net.family.multiki.stream.Downloads
import net.family.multiki.stream.Streams
import net.family.multiki.data.Section
import net.family.multiki.data.SectionInfo

sealed interface Screen {
    data object Home : Screen
    data class SectionList(val sectionId: String) : Screen
    data class Details(val sectionId: String, val itemId: String) : Screen
    data object Downloads : Screen
    data class VideoPlayer(val sectionId: String, val itemId: String, val part: Int, val startMs: Long = 0L) : Screen
    data object AudioPlayer : Screen
    data object HistoryList : Screen
    data object Settings : Screen
}

/** Question shown when a new action would need a second connection to the same section. */
data class Conflict(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val cancelLabel: String = "Оставить текущее",
    val altLabel: String? = null,
    val onAlt: (() -> Unit)? = null,
)

/** Search text and page number of a section, kept while the user visits a card and comes back. */
data class SectionUi(val query: String = "", val page: Int = 0)

class MainViewModel {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val repo = net.family.multiki.data.Catalogs.repo(net.family.multiki.platform.AppContext)

    var sections by mutableStateOf<List<SectionInfo>?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    private val crashFile = java.io.File(net.family.multiki.platform.Platform.dataDir, CRASH_FILE)
    var crashReport by mutableStateOf(runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull())
        private set

    fun dismissCrash() {
        runCatching { crashFile.delete() }
        crashReport = null
    }

    val stack = mutableStateListOf<Screen>(Screen.Home)

    val audio = AudioConnection()

    var conflict by mutableStateOf<Conflict?>(null)
        private set

    fun dismissConflict() {
        conflict = null
    }
    val sectionUi = mutableStateMapOf<String, SectionUi>()

    init {
        audio.reconnect() // pick up a tale that kept playing in the background
        viewModelScope.launch {
            try {
                sections = repo.sections()
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            }
        }
    }

    val current: Screen get() = stack.last()

    fun open(screen: Screen) {
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replace the current screen (e.g. the next film in the same player). */
    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    /** «Стоп» in a player: back to the title card — the one below if it is there, otherwise in place of the player. */
    fun closeTo(screen: Screen) {
        if (stack.size >= 2 && stack[stack.lastIndex - 1] == screen) back() else replaceTop(screen)
    }

    fun home() {
        stack.clear()
        stack.add(Screen.Home)
    }

    /**
     * Start watching/listening. The archive allows one connection per section, so if this section
     * is already busy with something else, ask first instead of silently breaking it.
     */
    fun play(section: Section, item: Item, part: Int = 0, startMs: Long = 0L) {
        // a download in the same section holds the only connection: ask before taking it
        val local = net.family.multiki.stream.playsOffline(section, item)
        val running = Downloads.runningIn(section.id)
        if (!local && running != null && running.itemKey != "${section.id}|${item.id}") {
            conflict = Conflict(
                title = "Раздел занят загрузкой",
                message = "Сейчас в разделе «${section.title}» загружается «${running.itemTitle}».\n\n" +
                    "Архив разрешает только одно соединение с каждым разделом. Загрузку можно приостановить " +
                    "на время просмотра — она продолжится сама, когда вы закончите.",
                confirmLabel = "Приостановить и смотреть",
                onConfirm = {
                    conflict = null
                    startPlayback(section, item, part, startMs)
                },
                cancelLabel = "Не мешать загрузке",
            )
            return
        }
        startPlayback(section, item, part, startMs)
    }

    private fun startPlayback(section: Section, item: Item, part: Int, startMs: Long) {
        if (section.kind == Kind.VIDEO) {
            audio.pause() // one sound at a time
            open(Screen.VideoPlayer(section.id, item.id, part, startMs))
            return
        }
        val st = audio.state
        val start = {
            // a different tale: stop caching the previous one so it doesn't compete for the connection
            if (st.itemId != item.id) Streams.closeLane(section.id)
            audio.play(section, item, part, startMs)
            if (current != Screen.AudioPlayer) open(Screen.AudioPlayer)
        }
        if (st.active && st.sectionId == section.id && st.itemId != item.id) {
            conflict = Conflict(
                title = "Раздел уже занят",
                message = "Сейчас играет «${st.itemTitle}» из раздела «${section.title}».\n\n" +
                    "Архив разрешает только одно соединение с каждым разделом. Если открыть второе, " +
                    "сервер может временно заблокировать ваш адрес.\n\n" +
                    "Остановить «${st.itemTitle}» и включить «${item.title}»?",
                confirmLabel = "Включить новое",
                onConfirm = {
                    conflict = null
                    start()
                },
            )
        } else {
            start()
        }
    }

    /** Continue a title from the history list where it was stopped. */
    fun resume(entry: net.family.multiki.data.HistoryEntry) {
        viewModelScope.launch {
            val s = runCatching { section(entry.sectionId) }.getOrNull() ?: return@launch
            val item = s.items.firstOrNull { it.id == entry.itemId } ?: return@launch
            if (entry.canResume) play(s, item, entry.part, entry.positionMs) else play(s, item, 0, 0L)
        }
    }

    /** «Скачать»: put the title into the download queue (asks first if the section is busy with playback). */
    fun download(section: Section, item: Item, toServer: Boolean = false) {
        val st = audio.state
        val listeningHere = st.active && st.isPlaying && st.sectionId == section.id && st.itemId != item.id
        if (listeningHere) {
            conflict = Conflict(
                title = "Раздел сейчас занят",
                message = "Сейчас играет «${st.itemTitle}» из этого же раздела.\n\n" +
                    "Архив разрешает только одно соединение с каждым разделом, поэтому загрузка " +
                    "«${item.title}» начнётся сама, когда вы закончите слушать. Или можно остановить " +
                    "прослушивание и загружать сразу.",
                confirmLabel = "Поставить в очередь",
                onConfirm = {
                    conflict = null
                    Downloads.enqueue(section, item, toServer)
                },
                cancelLabel = "Отмена",
                altLabel = "Остановить и загружать",
                onAlt = {
                    conflict = null
                    stopAudio()
                    Downloads.enqueue(section, item, toServer)
                },
            )
            return
        }
        Downloads.enqueue(section, item, toServer)
    }

    /** Copy/move a saved title between this device and the home server. */
    fun transfer(sectionId: String, itemId: String, toServer: Boolean, move: Boolean) {
        viewModelScope.launch {
            val s = runCatching { section(sectionId) }.getOrNull() ?: return@launch
            val item = s.items.firstOrNull { it.id == itemId } ?: return@launch
            Downloads.enqueue(s, item, toServer = toServer, moveSource = move)
        }
    }

    fun stopAudio() {
        val lane = audio.state.sectionId
        audio.stop()
        if (lane.isNotEmpty()) Streams.closeLane(lane)
    }

    /** The window is closing. */
    fun close() {
        audio.release()
        viewModelScope.cancel()
    }

    suspend fun section(id: String): Section = repo.section(id)

    fun ui(sectionId: String): SectionUi = sectionUi[sectionId] ?: SectionUi()

    fun setUi(sectionId: String, ui: SectionUi) {
        sectionUi[sectionId] = ui
    }
}
