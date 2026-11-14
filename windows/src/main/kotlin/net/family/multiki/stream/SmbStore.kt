package net.family.multiki.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.family.multiki.platform.Context
import net.family.multiki.platform.Platform
import net.family.multiki.platform.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Home server settings. On Windows the server is just a folder — usually a network folder such as
 * \\192.168.1.50\storage\SrvAV (Windows itself remembers the login and password for it).
 */
data class SmbSettings(
    val folder: String = "",
) {
    val configured: Boolean get() = folder.isNotBlank()
}

data class SmbPart(val part: Int, val file: String, val size: Long, val done: Boolean)

/** One title on the server, described by the info.json in its folder. */
data class SmbItem(
    val sectionId: String,
    val itemId: String,
    val title: String,
    val sectionTitle: String,
    val isVideo: Boolean,
    val folder: String,
    val parts: List<SmbPart>,
    val busyDevice: String?,
    val busyAt: Long,
) {
    val key: String get() = "$sectionId|$itemId"
    val complete: Boolean get() = parts.isNotEmpty() && parts.all { it.done }
    val doneCount: Int get() = parts.count { it.done }
    val bytes: Long get() = parts.filter { it.done }.sumOf { it.size }
    fun partPath(index: Int): String? = parts.firstOrNull { it.part == index && it.done }?.let { SmbStore.join(folder, it.file) }

    /** Another device is downloading this title right now (heartbeat fresher than 3 minutes). */
    val busyElsewhere: Boolean
        get() = busyDevice != null && busyDevice != SmbStore.deviceName &&
            System.currentTimeMillis() - busyAt < 3 * 60_000
}

data class SmbLibrary(
    val items: List<SmbItem> = emptyList(),
    val error: String? = null,
    val loading: Boolean = false,
    val updated: Long = 0,
)

/** An open file on the server for writing (same calls as smbj's File in the Android version). */
class SmbFile internal constructor(private val raf: RandomAccessFile) : Closeable {
    fun write(buf: ByteArray, fileOffset: Long) = write(buf, fileOffset, 0, buf.size)
    fun write(buf: ByteArray, fileOffset: Long, off: Int, len: Int) {
        raf.seek(fileOffset)
        raf.write(buf, off, len)
    }
    fun flush() = Unit // RandomAccessFile writes straight through
    override fun close() { runCatching { raf.close() } }
}

/**
 * Home server: a shared «Загруженное» for all the family's devices.
 *
 * Layout:  <folder>\<Раздел>\<Название>\<files>  +  info.json
 * The same layout and info.json as the Android app, so titles downloaded from a phone or a TV play here and back.
 */
object SmbStore {
    private const val INFO = "info.json"
    private lateinit var prefs: SharedPreferences

    val deviceName: String get() = Platform.deviceName

    private val _settings = MutableStateFlow(SmbSettings())
    val settings: StateFlow<SmbSettings> = _settings

    private val _library = MutableStateFlow(SmbLibrary())
    val library: StateFlow<SmbLibrary> = _library

    fun init(context: Context) {
        prefs = context.getSharedPreferences("smb", Context.MODE_PRIVATE)
        _settings.value = SmbSettings(folder = prefs.getString("folder", "") ?: "")
        if (configured) refreshAsync()
    }

    val configured: Boolean get() = _settings.value.configured

    fun save(s: SmbSettings) {
        val clean = SmbSettings(folder = s.folder.trim().trimEnd('\\', '/'))
        prefs.edit().putString("folder", clean.folder).apply()
        _settings.value = clean
        _library.value = SmbLibrary()
        if (clean.configured) refreshAsync()
    }

    /** Tries the folder; returns null on success or a readable error. Call off the main thread. */
    fun test(s: SmbSettings): String? = try {
        val root = File(s.folder.trim())
        if (s.folder.isBlank()) "Выберите папку сервера"
        else {
            if (!root.exists() && !root.mkdirs()) "Папка недоступна. Откройте её один раз в Проводнике и сохраните логин и пароль."
            else {
                val probe = File(root, ".proverka-${System.currentTimeMillis()}")
                probe.writeText("ok")
                probe.delete()
                null
            }
        }
    } catch (e: Exception) {
        readable(e)
    }

    fun refreshAsync() {
        if (!configured) return
        if (_library.value.loading) return
        _library.value = _library.value.copy(loading = true)
        thread(name = "smb-scan", isDaemon = true) {
            _library.value = try {
                SmbLibrary(items = scan(), error = null, loading = false, updated = System.currentTimeMillis())
            } catch (e: Exception) {
                _library.value.copy(error = readable(e), loading = false)
            }
        }
    }

    /** Path of a finished part on the server, from the last scan (no network). */
    fun donePartPath(sectionId: String, itemId: String, index: Int): String? =
        _library.value.items.firstOrNull { it.sectionId == sectionId && it.itemId == itemId }?.partPath(index)

    fun item(sectionId: String, itemId: String): SmbItem? =
        _library.value.items.firstOrNull { it.sectionId == sectionId && it.itemId == itemId }

    // ------------------------------------------------------------------ file operations

    /** Full path of the server folder, e.g. \\192.168.1.50\storage\SrvAV */
    fun rootPath(): String = _settings.value.folder.trim().trimEnd('\\', '/')

    fun join(vararg parts: String): String {
        val list = parts.filter { it.isNotEmpty() }
        if (list.isEmpty()) return ""
        val first = list.first().trimEnd('\\', '/')
        return (listOf(first) + list.drop(1).map { it.trim('\\', '/') }).joinToString(File.separator)
    }

    private fun f(path: String) = File(path)

    fun size(path: String): Long? = f(path).takeIf { it.isFile }?.length()

    fun openReader(path: String): SmbReader = SmbReader(RandomAccessFile(f(path), "r"))

    fun openWriter(path: String): SmbFile {
        f(path).parentFile?.mkdirs()
        return SmbFile(RandomAccessFile(f(path), "rw"))
    }

    fun rename(from: String, to: String) {
        val dst = f(to)
        if (dst.exists()) dst.delete()
        if (!f(from).renameTo(dst)) throw java.io.IOException("Не удалось переименовать $from")
    }

    fun deleteFile(path: String) { f(path).delete() }

    fun deleteItem(item: SmbItem) {
        f(item.folder).deleteRecursively()
        _library.value = _library.value.copy(items = _library.value.items.filter { it.key != item.key })
    }

    /** Creates / updates info.json of a title folder. [mark] changes the stored description. */
    fun updateInfo(folder: String, base: SmbItem, mark: (SmbItem) -> SmbItem) {
        val current = readInfo(folder) ?: base
        val updated = mark(current)
        f(folder).mkdirs()
        writeText(join(folder, INFO), toJson(updated))
        _library.value = _library.value.copy(items = _library.value.items.filter { it.key != updated.key } + updated)
    }

    fun readInfo(folder: String): SmbItem? = runCatching {
        val file = f(join(folder, INFO))
        if (!file.isFile) null else parse(folder, file.readText(Charsets.UTF_8))
    }.getOrNull()

    /** Small text files (history sync). Written through a temporary file so readers never see half a file. */
    fun writeText(path: String, text: String) {
        val file = f(path)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) { file.writeText(text, Charsets.UTF_8); tmp.delete() }
        }
    }

    fun readTextFiles(dir: String, suffix: String): List<String> {
        val d = f(dir)
        if (!d.isDirectory) return emptyList()
        return d.listFiles { x -> x.isFile && x.name.endsWith(suffix) }.orEmpty()
            .mapNotNull { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
    }

    // ------------------------------------------------------------------ internals

    private fun scan(): List<SmbItem> {
        val root = f(rootPath())
        if (!root.exists()) {
            if (!root.mkdirs()) throw java.io.IOException("Папка сервера недоступна: ${root.path}")
            return emptyList()
        }
        val result = ArrayList<SmbItem>()
        for (sectionDir in root.listFiles().orEmpty()) {
            if (!sectionDir.isDirectory || sectionDir.name.startsWith(".")) continue
            for (itemDir in sectionDir.listFiles().orEmpty()) {
                if (!itemDir.isDirectory || itemDir.name.startsWith(".")) continue
                val info = File(itemDir, INFO)
                if (!info.isFile) continue
                runCatching { parse(itemDir.path, info.readText(Charsets.UTF_8)) }.getOrNull()?.let { result += it }
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    private fun toJson(i: SmbItem): String = JSONObject()
        .put("app", "Архив Вещания")
        .put("sectionId", i.sectionId).put("itemId", i.itemId).put("title", i.title)
        .put("sectionTitle", i.sectionTitle).put("isVideo", i.isVideo)
        .put("busyDevice", i.busyDevice ?: JSONObject.NULL).put("busyAt", i.busyAt)
        .put("parts", JSONArray().apply {
            i.parts.sortedBy { it.part }.forEach {
                put(JSONObject().put("part", it.part).put("file", it.file).put("size", it.size).put("done", it.done))
            }
        })
        .toString(1)

    private fun parse(folder: String, text: String): SmbItem {
        val o = JSONObject(text)
        val arr = o.optJSONArray("parts") ?: JSONArray()
        return SmbItem(
            sectionId = o.getString("sectionId"),
            itemId = o.getString("itemId"),
            title = o.optString("title"),
            sectionTitle = o.optString("sectionTitle"),
            isVideo = o.optBoolean("isVideo", true),
            folder = folder,
            parts = (0 until arr.length()).map { k ->
                val p = arr.getJSONObject(k)
                SmbPart(p.optInt("part"), p.optString("file"), p.optLong("size"), p.optBoolean("done"))
            },
            busyDevice = if (o.isNull("busyDevice")) null else o.optString("busyDevice"),
            busyAt = o.optLong("busyAt", 0),
        )
    }

    fun readable(e: Throwable): String {
        val msg = (e.message ?: e.toString())
        return when {
            e is java.nio.file.AccessDeniedException || msg.contains("Access is denied", true) || msg.contains("Отказано в доступе", true) ->
                "Нет доступа к папке сервера — откройте её в Проводнике и сохраните логин и пароль"
            e is java.io.FileNotFoundException -> "Папка или файл на сервере не найдены"
            else -> msg.take(200)
        }
    }
}

/** Random-access reader of one file on the server (used by the local proxy for playback). */
class SmbReader(private val file: RandomAccessFile) : Closeable {
    val size: Long = file.length()

    @Synchronized
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        file.seek(pos)
        val n = file.read(buf, off, minOf(len.toLong(), size - pos).toInt())
        return if (n <= 0) -1 else n
    }

    override fun close() {
        runCatching { file.close() }
    }
}
