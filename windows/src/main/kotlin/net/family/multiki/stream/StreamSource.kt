package net.family.multiki.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** HTTP client for the archive's file servers: no idle keep-alive sockets, so an unused socket never counts as a connection. */
object Net {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android) MultikiFamily/0.3"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

data class StreamStatus(
    val downloaded: Long = 0,
    val total: Long = -1,
    val message: String? = null,
) {
    val complete: Boolean get() = total > 0 && downloaded >= total
    val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
}

private class BusyException : IOException("server busy")

/**
 * One remote file cached on disk while it is being played.
 *
 * Readers (the local proxy, serving the player) ask for bytes at any position. A single background
 * worker downloads the file through ONE connection (holding the section's lane in [ConnectionGate]).
 * If a reader needs a position far from where the download currently is, the connection is closed
 * and a new one is opened at that position (never two at the same time). Downloaded pieces are
 * remembered, so seeking back inside them never touches the network. When the player has what it
 * needs, the worker keeps filling the gaps until the whole file is cached.
 */
class StreamSource internal constructor(
    val key: String,
    val lane: String,
    val url: String,
    private val file: File,
    private val rangesFile: File,
) : ConnectionGate.Owner {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ranges = TreeMap<Long, Long>() // start -> end (exclusive)

    @Volatile var total: Long = -1
        private set
    @Volatile var lastUsed: Long = System.currentTimeMillis()
        private set
    @Volatile var isClosed = false
        private set

    private val raf: RandomAccessFile
    private val channel: FileChannel
    private var worker: Thread? = null
    @Volatile private var preempted = false
    @Volatile private var currentCall: Call? = null
    private var wanted: Long = -1 // position a blocked reader needs (guarded by lock)
    @Volatile private var upPos: Long = -1 // where the active download is, -1 when idle
    private var lastPos: Long = 0
    private var demand = false // the next download was requested by a waiting reader
    private var lastStatusBytes = 0L
    private var lastSavedBytes = 0L

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        channel = raf.channel
        loadRanges()
        publishStatus(null)
    }

    // ---------------------------------------------------------------- readers

    /** Waits (up to [timeoutMs]) until the file size is known. Returns -1 on timeout. */
    fun awaitTotal(timeoutMs: Long): Long {
        startWorker()
        val deadline = System.currentTimeMillis() + timeoutMs
        lock.lock()
        try {
            while (total < 0 && !isClosed) {
                if (wanted < 0 && upPos < 0) {
                    wanted = 0
                    changed.signalAll()
                }
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                changed.await(min(left, 500L), TimeUnit.MILLISECONDS)
            }
            return total
        } finally {
            lock.unlock()
        }
    }

    /** Blocking read of up to [len] bytes at [pos]. Returns -1 at end of file. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        startWorker()
        lastUsed = System.currentTimeMillis()
        var available = 0L
        lock.lock()
        try {
            while (true) {
                if (isClosed) throw IOException("stream closed")
                if (total in 0..pos) return -1
                val end = coveredEnd(pos)
                if (end > pos) {
                    available = min(len.toLong(), end - pos)
                    break
                }
                val active = upPos
                val arrivingSoon = active in 0..pos && pos <= active + AHEAD
                if (!arrivingSoon && wanted != pos) {
                    wanted = pos
                    // the active download is somewhere else: close it so the worker can jump here
                    if (active >= 0) currentCall?.cancel()
                    changed.signalAll()
                }
                changed.await(500, TimeUnit.MILLISECONDS)
            }
        } finally {
            lock.unlock()
        }
        val bb = ByteBuffer.wrap(buf, off, available.toInt())
        var done = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos + done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        currentCall?.cancel()
        lock.lock()
        try {
            changed.signalAll()
        } finally {
            lock.unlock()
        }
        saveRanges()
        runCatching { channel.close() }
        runCatching { raf.close() }
    }

    override fun preempt() {
        preempted = true
        currentCall?.cancel()
    }

    val isComplete: Boolean
        get() {
            lock.lock()
            try {
                return completeLocked()
            } finally {
                lock.unlock()
            }
        }

    // ---------------------------------------------------------------- worker

    private fun startWorker() {
        lock.lock()
        try {
            if (worker == null && !isClosed) {
                worker = thread(name = "stream-$lane", isDaemon = true) { runWorker() }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun runWorker() {
        var backoff = 0L
        while (!isClosed) {
            val start: Long
            lock.lock()
            try {
                var s = nextStartLocked()
                while (s == null && !isClosed) {
                    changed.await(1, TimeUnit.SECONDS)
                    s = nextStartLocked()
                }
                if (isClosed || s == null) return
                start = s
            } finally {
                lock.unlock()
            }

            var sleepMs = 0L
            preempted = false
            if (demand) {
                ConnectionGate.acquire(lane, this)
            } else if (!ConnectionGate.tryAcquire(lane, this)) {
                // someone else is using the section right now; fill our cache later
                sleepInterruptibly(5_000)
                continue
            }
            try {
                download(start)
                backoff = 0
                publishStatus(null)
            } catch (e: BusyException) {
                backoff = (if (backoff == 0L) 5_000L else backoff * 2).coerceAtMost(60_000L)
                sleepMs = backoff
                publishStatus(
                    "Сервер архива занят: с вашего адреса уже есть соединение с этим разделом " +
                        "(например, с другого устройства дома). Повтор через ${backoff / 1000} с."
                )
            } catch (e: IOException) {
                val intentional = isClosed || preempted || hasJumpRequest()
                if (!intentional) {
                    backoff = (if (backoff == 0L) 3_000L else backoff * 2).coerceAtMost(30_000L)
                    sleepMs = backoff
                    publishStatus("Нет связи с архивом. Повтор через ${backoff / 1000} с.")
                }
            } finally {
                currentCall = null
                upPos = -1
                ConnectionGate.release(lane, this)
                lock.lock()
                try {
                    changed.signalAll()
                } finally {
                    lock.unlock()
                }
            }
            if (sleepMs > 0) sleepInterruptibly(sleepMs)
        }
    }

    private fun download(start: Long) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Net.USER_AGENT)
            .header("Range", "bytes=$start-")
            .build()
        val call = Net.client.newCall(request)
        currentCall = call
        if (isClosed || preempted) {
            call.cancel()
            return
        }
        call.execute().use { resp ->
            if (resp.code == 503 || resp.code == 429) throw BusyException()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            var pos: Long
            if (resp.code == 206) {
                // Content-Range: bytes 1000-2000/214505472
                val cr = resp.header("Content-Range").orEmpty()
                val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(cr)
                pos = m?.groupValues?.get(1)?.toLongOrNull() ?: start
                m?.groupValues?.get(3)?.toLongOrNull()?.let { setTotal(it) }
            } else {
                // server ignored the Range header: the body is the whole file from byte 0
                pos = 0
                val len = body.contentLength()
                if (len > 0) setTotal(len)
            }
            upPos = pos
            val input = body.byteStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (isClosed || preempted) break
                lock.lock()
                val stop = try {
                    val w = wanted
                    (w >= 0 && (w < pos || w > pos + AHEAD)) || coveredEnd(pos) > pos
                } finally {
                    lock.unlock()
                }
                if (stop) break
                val n = input.read(buf)
                if (n < 0) break
                val bb = ByteBuffer.wrap(buf, 0, n)
                var written = 0
                while (bb.hasRemaining()) written += channel.write(bb, pos + written)
                lock.lock()
                try {
                    addRangeLocked(pos, pos + n)
                    pos += n
                    upPos = pos
                    lastPos = pos
                    if (wanted in 0 until pos && coveredEnd(wanted) > wanted) wanted = -1
                    changed.signalAll()
                } finally {
                    lock.unlock()
                }
                maybePublishAndSave()
            }
        }
    }

    /** Where the next download should start, or null if there is nothing to do right now. */
    private fun nextStartLocked(): Long? {
        if (isClosed) return null
        demand = false
        if (wanted >= 0) {
            val w = wanted
            wanted = -1
            preempted = false
            demand = true
            val end = coveredEnd(w)
            return if (end > w) {
                if (total in 0..end) null else end
            } else {
                w
            }
        }
        if (preempted || total < 0 || completeLocked()) return null
        return firstGapFrom(lastPos) ?: firstGapFrom(0)
    }

    private fun firstGapFrom(from: Long): Long? {
        var pos = from
        while (true) {
            val end = coveredEnd(pos)
            if (end > pos) pos = end else break
        }
        return if (total in 0..pos) null else pos
    }

    private fun hasJumpRequest(): Boolean {
        lock.lock()
        try {
            return wanted >= 0
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- bookkeeping

    private fun coveredEnd(pos: Long): Long {
        val e = ranges.floorEntry(pos) ?: return -1
        return if (e.value > pos) e.value else -1
    }

    private fun addRangeLocked(s: Long, e: Long) {
        var start = s
        var end = e
        val lower = ranges.floorEntry(start)
        if (lower != null && lower.value >= start) {
            start = lower.key
            end = max(end, lower.value)
            ranges.remove(lower.key)
        }
        while (true) {
            val next = ranges.ceilingEntry(start) ?: break
            if (next.key > end) break
            end = max(end, next.value)
            ranges.remove(next.key)
        }
        ranges[start] = end
    }

    private fun completeLocked(): Boolean {
        if (total <= 0) return false
        val first = ranges.firstEntry() ?: return false
        return first.key == 0L && first.value >= total
    }

    private fun downloadedLocked(): Long = ranges.entries.sumOf { it.value - it.key }

    private fun setTotal(t: Long) {
        if (t <= 0) return
        lock.lock()
        try {
            if (total != t) {
                total = t
                if (raf.length() != t) runCatching { raf.setLength(t) }
                changed.signalAll()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun maybePublishAndSave() {
        val downloaded: Long
        lock.lock()
        try {
            downloaded = downloadedLocked()
        } finally {
            lock.unlock()
        }
        if (downloaded - lastStatusBytes >= 256 * 1024 || (total > 0 && downloaded >= total)) {
            lastStatusBytes = downloaded
            _status.value = StreamStatus(downloaded, total, null)
        }
        if (downloaded - lastSavedBytes >= 4L * 1024 * 1024 || (total > 0 && downloaded >= total)) {
            lastSavedBytes = downloaded
            saveRanges()
        }
    }

    private fun publishStatus(message: String?) {
        val downloaded: Long
        lock.lock()
        try {
            downloaded = downloadedLocked()
        } finally {
            lock.unlock()
        }
        _status.value = StreamStatus(downloaded, total, message)
    }

    private fun saveRanges() {
        val text: String
        lock.lock()
        try {
            text = buildString {
                append(total).append('\n')
                for ((s, e) in ranges) append(s).append(' ').append(e).append('\n')
            }
        } finally {
            lock.unlock()
        }
        runCatching { rangesFile.writeText(text) }
    }

    private fun loadRanges() {
        if (!rangesFile.exists()) return
        runCatching {
            val lines = rangesFile.readLines()
            val t = lines.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
            if (t > 0 && raf.length() >= t) total = t
            for (line in lines.drop(1)) {
                val p = line.trim().split(' ')
                if (p.size == 2) {
                    val s = p[0].toLongOrNull()
                    val e = p[1].toLongOrNull()
                    if (s != null && e != null && e > s && e <= raf.length()) addRangeLocked(s, e)
                }
            }
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (!isClosed && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(min(250L, until - System.currentTimeMillis()).coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    companion object {
        /** A reader waits instead of reconnecting if the needed byte is less than this ahead of the download. */
        private const val AHEAD = 2L * 1024 * 1024
    }
}
