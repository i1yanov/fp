package net.family.multiki.stream

import net.family.multiki.platform.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Registry of cached remote files. Sources are opened on demand by [LocalProxy]. */
object Streams {
    private const val MAX_OPEN_PER_LANE = 4
    private const val CACHE_LIMIT = 1_500L * 1024 * 1024 // 1.5 GB of streaming cache

    private lateinit var root: File
    private val open = LinkedHashMap<String, StreamSource>()

    /** The source most recently opened in each section — the player screens show its status. */
    private val _latest = MutableStateFlow<Map<String, StreamSource>>(emptyMap())
    val latest: StateFlow<Map<String, StreamSource>> = _latest

    fun init(context: Context) {
        root = File(context.cacheDir, "stream").apply { mkdirs() }
    }

    @Synchronized
    fun open(lane: String, url: String): StreamSource {
        val key = "$lane|$url"
        open[key]?.let { if (!it.isClosed) return it.also { touchLatest(lane, it) } }
        // keep a few recent files of the lane open (e.g. neighbouring tracks), close the oldest
        val inLane = open.values.filter { it.lane == lane && !it.isClosed }.sortedBy { it.lastUsed }
        if (inLane.size >= MAX_OPEN_PER_LANE) {
            inLane.take(inLane.size - MAX_OPEN_PER_LANE + 1).forEach {
                it.close()
                open.remove(it.key)
            }
        }
        trimCache()
        val name = sha1(key).take(24)
        val src = StreamSource(key, lane, url, File(root, "$name.bin"), File(root, "$name.ranges"))
        open[key] = src
        touchLatest(lane, src)
        return src
    }

    @Synchronized
    fun closeLane(lane: String) {
        open.values.filter { it.lane == lane }.forEach { it.close() }
        open.entries.removeAll { it.value.lane == lane }
        _latest.value = _latest.value - lane
    }

    /** True while a player is actually reading from this section (the last 20 s). Downloads wait then. */
    @Synchronized
    fun laneActive(lane: String): Boolean {
        val now = System.currentTimeMillis()
        return open.values.any { it.lane == lane && !it.isClosed && !it.isComplete && now - it.lastUsed < 20_000 }
    }

    /** The cached copy of [url] if it is complete — «Скачать» can copy it instead of downloading again. */
    fun completeCacheFile(lane: String, url: String): File? {
        if (!::root.isInitialized) return null
        val name = sha1("$lane|$url").take(24)
        val bin = File(root, "$name.bin")
        val ranges = File(root, "$name.ranges")
        if (!bin.exists() || !ranges.exists()) return null
        val lines = runCatching { ranges.readLines() }.getOrNull() ?: return null
        val total = lines.firstOrNull()?.trim()?.toLongOrNull() ?: return null
        val complete = total > 0 && bin.length() >= total &&
            lines.drop(1).any { line -> line.trim().split(' ').let { it.size == 2 && it[0] == "0" && (it[1].toLongOrNull() ?: 0) >= total } }
        return if (complete) bin else null
    }

    private fun touchLatest(lane: String, src: StreamSource) {
        _latest.value = _latest.value + (lane to src)
    }

    private fun trimCache() {
        val files = root.listFiles { f -> f.name.endsWith(".bin") }?.toList() ?: return
        var size = files.sumOf { it.length() }
        if (size <= CACHE_LIMIT) return
        val openNames = open.values.map { sha1(it.key).take(24) }.toSet()
        for (f in files.sortedBy { it.lastModified() }) {
            if (size <= CACHE_LIMIT) break
            val stem = f.name.removeSuffix(".bin")
            if (stem in openNames) continue
            size -= f.length()
            f.delete()
            File(root, "$stem.ranges").delete()
        }
    }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * A tiny HTTP server on 127.0.0.1. Players (VLC for video, ExoPlayer for audio) open
 * `http://127.0.0.1:<port>/s/<token>` and may connect as often as they like; all of it is served
 * from [StreamSource], which keeps a single connection to the archive.
 */
object LocalProxy {
    private const val SMB_LANE = "smb"
    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "local-proxy", isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                thread(name = "proxy-client", isDaemon = true) { handle(client) }
            }
        }
    }

    /** URL a player should open for a file on the home SMB server. */
    fun urlForSmb(path: String): String = urlFor(SMB_LANE, path)

    /** URL a player should open for [remoteUrl] in section [lane]. */
    fun urlFor(lane: String, remoteUrl: String): String {
        start()
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("$lane\n$remoteUrl".toByteArray())
        val ext = remoteUrl.substringAfterLast('/').substringAfterLast('.', "bin").take(5)
        return "http://127.0.0.1:$port/s/$token.$ext"
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 120_000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"
                val out = sock.getOutputStream()
                val (lane, url) = decode(path) ?: return respond(out, 404, "Not Found")
                // two kinds of sources: the archive (cached, one connection) or the home SMB server
                var smb: SmbReader? = null
                val reader: (Long, ByteArray, Int, Int) -> Int
                val total: Long
                if (lane == SMB_LANE) {
                    val r = try {
                        SmbStore.openReader(url)
                    } catch (e: Exception) {
                        return respond(out, 404, "Not Found")
                    }
                    smb = r
                    total = r.size
                    reader = { p, b, o, l -> r.read(p, b, o, l) }
                } else {
                    val source = Streams.open(lane, url)
                    total = source.awaitTotal(45_000)
                    reader = { p, b, o, l -> source.read(p, b, o, l) }
                }
                if (total <= 0) {
                    smb?.close()
                    return respond(out, 503, "Service Unavailable")
                }

                var start = 0L
                var end = total - 1
                val range = headers["range"]
                val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
                if (m != null) {
                    val a = m.groupValues[1]
                    val b = m.groupValues[2]
                    if (a.isNotEmpty()) {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                    } else if (b.isNotEmpty()) {
                        start = maxOf(0L, total - b.toLong()) // suffix range: last N bytes
                    }
                }
                if (start >= total) {
                    smb?.close()
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    out.flush()
                    return
                }
                val length = end - start + 1
                val head = StringBuilder()
                if (m != null) {
                    head.append("HTTP/1.1 206 Partial Content\r\n")
                    head.append("Content-Range: bytes $start-$end/$total\r\n")
                } else {
                    head.append("HTTP/1.1 200 OK\r\n")
                }
                head.append("Content-Type: ").append(mimeFor(url)).append("\r\n")
                head.append("Content-Length: ").append(length).append("\r\n")
                head.append("Accept-Ranges: bytes\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                if (method.equals("HEAD", ignoreCase = true)) {
                    smb?.close()
                    out.flush()
                    return
                }
                try {
                    val buf = ByteArray(64 * 1024)
                    var pos = start
                    while (pos <= end) {
                        val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                        val n = reader(pos, buf, 0, want)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        pos += n
                    }
                    out.flush()
                } finally {
                    smb?.close()
                }
            } catch (_: IOException) {
                // the player closed the connection (seek, stop) — normal
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, text: String) {
        runCatching {
            out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun decode(path: String): Pair<String, String>? {
        if (!path.startsWith("/s/")) return null
        val token = path.removePrefix("/s/").substringBefore('.').substringBefore('?')
        val text = runCatching { String(java.util.Base64.getUrlDecoder().decode(token)) }
            .getOrNull() ?: return null
        val i = text.indexOf('\n')
        if (i <= 0) return null
        return text.substring(0, i) to text.substring(i + 1)
    }

    private fun mimeFor(url: String): String = when (url.substringAfterLast('.').lowercase()) {
        "avi" -> "video/x-msvideo"
        "mpg", "mpeg" -> "video/mpeg"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}

/**
 * What a player should open for a part: the file saved on this device, the file on the home
 * server, or (if neither) the archive through the caching proxy.
 */
fun playUri(section: net.family.multiki.data.Section, item: net.family.multiki.data.Item, index: Int): String {
    Downloads.localFile(section.id, item.id, index)?.let { return it.absolutePath } // VLC opens a plain path
    SmbStore.donePartPath(section.id, item.id, index)?.let { return LocalProxy.urlForSmb(it) }
    val part = item.parts[index]
    return LocalProxy.urlFor(section.id, remoteUrl(section.host, part.path))
}

/** True if every part plays without the archive (device or home server). */
fun playsOffline(section: net.family.multiki.data.Section, item: net.family.multiki.data.Item): Boolean =
    item.parts.indices.all { Downloads.localFile(section.id, item.id, it) != null || SmbStore.donePartPath(section.id, item.id, it) != null }

/** Remote URL of a catalog part (path is already URL-encoded as on the server). */
fun remoteUrl(host: String, path: String): String = "http://$host$path"
