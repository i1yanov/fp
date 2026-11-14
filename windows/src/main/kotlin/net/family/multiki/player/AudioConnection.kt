package net.family.multiki.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import net.family.multiki.data.Catalogs
import net.family.multiki.data.History
import net.family.multiki.data.HistoryEntry
import net.family.multiki.data.Item
import net.family.multiki.data.Section
import net.family.multiki.data.nextAfter
import net.family.multiki.platform.AppContext
import net.family.multiki.stream.playUri
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import javax.swing.SwingUtilities
import javax.swing.Timer

/** What the audio screens show. */
data class AudioState(
    val sectionId: String = "",
    val itemId: String = "",
    val itemTitle: String = "",
    val trackTitle: String = "",
    val index: Int = 0,
    val count: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
) {
    val active: Boolean get() = count > 0
}

/**
 * Tales and radio theatre on Windows: one VLC audio player that keeps playing while you browse.
 * Same behaviour as the Android audio service: tracks one after another, place saved every 5 s,
 * «Что дальше?» after the tale, sleep timer with a soft fade-out.
 */
class AudioConnection : AudioCommands {
    private class Track(val url: String, val title: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var section: Section? = null
    private var item: Item? = null
    private var tracks: List<Track> = emptyList()
    private var index = 0
    private var pendingSeek = 0L
    private var buffering = false
    private var lastError: String? = null
    private var volume = 100

    var state by mutableStateOf(AudioState())
        private set

    private val player: MediaPlayer? by lazy {
        Vlc.factory?.mediaPlayers()?.newMediaPlayer()?.also { p ->
            p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = ui {
                    buffering = false
                    lastError = null
                    val seek = pendingSeek
                    if (seek > 0) {
                        pendingSeek = 0
                        mediaPlayer.submit { mediaPlayer.controls().setTime(seek) }
                    }
                    saveHistory(ended = false)
                    refresh()
                }
                override fun paused(mediaPlayer: MediaPlayer) = ui {
                    saveHistory(ended = false)
                    net.family.multiki.stream.HistorySync.flush()
                    refresh()
                }
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { buffering = newCache < 100f }
                override fun error(mediaPlayer: MediaPlayer) = ui {
                    lastError = "Не удаётся воспроизвести запись"
                    refresh()
                }
                override fun finished(mediaPlayer: MediaPlayer) = ui { onTrackEnded() }
            })
        }
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    init {
        AudioExtras.commands = this
        Timer(1_000) { tickNextUp(); tickSleep(); if (state.active) refresh() }.start()
        Timer(5_000) { if (player?.status()?.isPlaying == true) saveHistory(ended = false) }.start()
    }

    /** Nothing to re-attach on Windows: the player lives as long as the program. */
    fun reconnect() = Unit

    fun play(section: Section, item: Item, startIndex: Int = 0, startMs: Long = 0L) {
        val p = player
        if (p == null) {
            state = state.copy(error = Vlc.error ?: "Не удалось запустить VLC")
            return
        }
        stopNext()
        this.section = section
        this.item = item
        tracks = item.parts.mapIndexed { i, part -> Track(playUri(section, item, i), trackTitle(item, part.file, part.n)) }
        startTrack(startIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0)), startMs.coerceAtLeast(0L))
    }

    private fun startTrack(i: Int, fromMs: Long) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        index = i
        pendingSeek = fromMs
        lastError = null
        buffering = true
        p.media().play(tracks[i].url, ":network-caching=3000")
        refresh()
    }

    fun toggle() {
        val p = player ?: return
        if (!state.active) return
        if (p.status().isPlaying) p.controls().setPause(true)
        else if (p.status().state() == uk.co.caprica.vlcj.player.base.State.ENDED || p.status().state() == uk.co.caprica.vlcj.player.base.State.STOPPED) startTrack(index, 0)
        else p.controls().play()
        refresh()
    }

    fun pause() {
        if (player?.status()?.isPlaying == true) player?.controls()?.setPause(true)
    }

    fun seekTo(ms: Long) {
        player?.controls()?.setTime(ms.coerceAtLeast(0))
        refresh()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.controls().setTime((p.status().time() + deltaMs).coerceAtLeast(0))
        refresh()
    }

    fun next() {
        if (index < tracks.lastIndex) {
            saveHistory(ended = false)
            startTrack(index + 1, 0)
        }
    }

    fun previous() {
        val p = player ?: return
        if (p.status().time() > 5_000 || index == 0) p.controls().setTime(0) else startTrack(index - 1, 0)
    }

    fun jumpTo(i: Int) {
        if (i in tracks.indices) startTrack(i, 0)
    }

    fun stop() {
        saveHistory(ended = false)
        net.family.multiki.stream.HistorySync.flush()
        player?.controls()?.stop()
        stopNext()
        tracks = emptyList()
        section = null
        item = null
        state = AudioState()
    }

    /** Called about twice a second by visible audio screens to move the progress bar. */
    fun refresh() {
        val p = player ?: return
        val it = item
        if (tracks.isEmpty() || it == null) {
            if (state.active) state = AudioState()
            return
        }
        state = AudioState(
            sectionId = section?.id.orEmpty(),
            itemId = it.id,
            itemTitle = it.title,
            trackTitle = tracks.getOrNull(index)?.title.orEmpty(),
            index = index,
            count = tracks.size,
            isPlaying = p.status().isPlaying,
            isBuffering = buffering,
            positionMs = p.status().time().coerceAtLeast(0),
            durationMs = p.status().length().coerceAtLeast(0),
            error = lastError,
        )
    }

    fun release() {
        saveHistory(ended = false)
        runCatching { player?.controls()?.stop() }
        runCatching { player?.release() }
    }

    // ------------------------------------------------------------ end of a track / tale

    private fun onTrackEnded() {
        if (index < tracks.lastIndex) {
            saveHistory(ended = false)
            startTrack(index + 1, 0)
            return
        }
        saveHistory(ended = true)
        refresh()
        val sleep = AudioExtras.sleep.value
        if (sleep.afterTale) {
            // sleep timer "after this tale": stop here, nothing next
            AudioExtras.sleep.value = SleepState()
            return
        }
        val s = section ?: return
        val it = item ?: return
        prepareNext(s.id, it.id, random = false)
    }

    private fun prepareNext(sectionId: String, currentItemId: String, random: Boolean) {
        scope.launch {
            val sec = runCatching { Catalogs.repo(AppContext).section(sectionId) }.getOrNull() ?: return@launch
            val next = sec.nextAfter(currentItemId, random) ?: return@launch
            AudioExtras.nextUp.value = NextUpState(
                visible = true,
                secondsLeft = COUNTDOWN,
                random = random,
                sectionId = sectionId,
                itemId = next.id,
                title = next.title,
            )
        }
    }

    private fun tickNextUp() {
        val st = AudioExtras.nextUp.value
        if (!st.visible) return
        if (st.secondsLeft <= 1) nextNow() else AudioExtras.nextUp.value = st.copy(secondsLeft = st.secondsLeft - 1)
    }

    override fun nextNow() {
        val st = AudioExtras.nextUp.value
        if (!st.visible) return
        AudioExtras.nextUp.value = NextUpState()
        scope.launch {
            val sec = runCatching { Catalogs.repo(AppContext).section(st.sectionId) }.getOrNull() ?: return@launch
            val it = sec.items.firstOrNull { x -> x.id == st.itemId } ?: return@launch
            val resume = History.get(sec.id, it.id)?.takeIf { r -> r.canResume }
            play(sec, it, resume?.part ?: 0, resume?.positionMs ?: 0L)
        }
    }

    override fun stopNext() {
        if (AudioExtras.nextUp.value.visible) AudioExtras.nextUp.value = NextUpState()
    }

    override fun chooseNext(random: Boolean) {
        val s = section ?: return
        val it = item ?: return
        prepareNext(s.id, it.id, random)
    }

    // ------------------------------------------------------------ sleep timer

    override fun setSleep(minutes: Int?, afterTale: Boolean) {
        setVolume(100)
        AudioExtras.sleep.value = when {
            afterTale -> SleepState(afterTale = true)
            minutes != null && minutes > 0 -> SleepState(endAt = System.currentTimeMillis() + minutes * 60_000L)
            else -> SleepState()
        }
    }

    private fun setVolume(v: Int) {
        if (v == volume) return
        volume = v
        player?.audio()?.setVolume(v)
    }

    private fun tickSleep() {
        val st = AudioExtras.sleep.value
        if (st.endAt <= 0) return
        val left = st.endAt - System.currentTimeMillis()
        when {
            left <= 0 -> {
                pause()
                setVolume(100)
                stopNext()
                AudioExtras.sleep.value = SleepState()
            }
            left < FADE_MS -> setVolume((left * 100 / FADE_MS).toInt().coerceIn(5, 100))
            else -> setVolume(100)
        }
    }

    // ------------------------------------------------------------ history

    private fun saveHistory(ended: Boolean) {
        val s = section ?: return
        val it = item ?: return
        val p = player ?: return
        val count = tracks.size.coerceAtLeast(1)
        val finished = ended && index >= count - 1
        History.update(
            HistoryEntry(
                sectionId = s.id,
                itemId = it.id,
                title = it.title,
                sectionTitle = s.title,
                isVideo = false,
                part = if (finished) 0 else index,
                partsCount = count,
                positionMs = if (finished) 0 else p.status().time().coerceAtLeast(0),
                durationMs = p.status().length().coerceAtLeast(0),
                updated = System.currentTimeMillis(),
                finished = finished,
            )
        )
    }

    companion object {
        private const val COUNTDOWN = 10
        private const val FADE_MS = 15_000L

        /** "03_Kolobok.mp3" -> "03 Kolobok"; single-track tales use the tale title. */
        fun trackTitle(item: Item, file: String, n: Int): String {
            if (item.parts.size == 1) return item.title
            val stem = file.substringBeforeLast('.')
            val decoded = runCatching { java.net.URLDecoder.decode(stem.replace("+", "%2B"), "UTF-8") }.getOrDefault(stem)
            val name = decoded.replace('_', ' ').trim()
            return name.ifEmpty { "Запись $n" }
        }
    }
}
