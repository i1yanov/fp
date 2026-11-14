package net.family.multiki.stream

import net.family.multiki.platform.Context
import net.family.multiki.platform.Platform
import net.family.multiki.platform.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.family.multiki.data.Item
import net.family.multiki.data.Kind
import net.family.multiki.data.Section
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

enum class DState { QUEUED, RUNNING, WAITING, PAUSED, DONE, ERROR }

/** One file (series / track) to save into «Загруженное». */
data class DTask(
    val id: String,
    val sectionId: String,
    val itemId: String,
    val itemTitle: String,
    val sectionTitle: String,
    val isVideo: Boolean,
    val part: Int,
    val partsCount: Int,
    val url: String,
    val path: String,
    val total: Long,
    val downloaded: Long,
    val state: DState,
    val message: String?,
    val added: Long,
    val toServer: Boolean = false, // true: saved to the home SMB server, path is relative to the share
    val moveSource: Boolean = false, // transfer: delete the other copy when the title is complete
) {
    val itemKey: String get() = "$sectionId|$itemId" + if (toServer) "|smb" else ""
    val active: Boolean get() = state == DState.QUEUED || state == DState.RUNNING || state == DState.WAITING
}

/** Everything about one title in «Загруженное». */
data class DItem(
    val sectionId: String,
    val itemId: String,
    val title: String,
    val sectionTitle: String,
    val isVideo: Boolean,
    val tasks: List<DTask>,
    val toServer: Boolean = false,
) {
    val key: String get() = "$sectionId|$itemId" + if (toServer) "|smb" else ""
    val done: Boolean get() = tasks.all { it.state == DState.DONE }
    val active: Boolean get() = tasks.any { it.active }
    val paused: Boolean get() = !done && !active && tasks.any { it.state == DState.PAUSED }
    val failed: Boolean get() = tasks.any { it.state == DState.ERROR }
    val bytesDone: Long get() = tasks.sumOf { if (it.state == DState.DONE) maxOf(it.total, it.downloaded) else it.downloaded }
    val bytesTotal: Long get() = tasks.sumOf { maxOf(it.total, it.downloaded) }
    val percent: Int
        get() = when {
            done -> 100
            tasks.any { it.total <= 0 && it.state != DState.DONE } -> {
                val doneParts = tasks.count { it.state == DState.DONE }
                doneParts * 100 / tasks.size.coerceAtLeast(1)
            }
            bytesTotal > 0 -> (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 99)
            else -> 0
        }
    val current: DTask? get() = tasks.firstOrNull { it.state == DState.RUNNING } ?: tasks.firstOrNull { it.state == DState.WAITING }
}

/**
 * Download queue for «Загруженное».
 *
 * - One worker per section (lane): files of one section download one after another, different
 *   sections in parallel — never more than one connection per section.
 * - A download never pushes away watching/listening: while a player in the same section is active,
 *   the download waits and continues by itself afterwards. Playback, on the other hand, may take the
 *   connection from a download (the app asks the user first).
 * - Interrupted files resume from where they stopped (HTTP Range).
 * - If the file is already completely in the playback cache, it is copied instead of downloaded.
 */
object Downloads {
    /** How many times a failed download is retried before it stops with an error. */
    const val MAX_RETRIES = 3

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    lateinit var root: File
        private set

    /** «Папка для скачанного» from the settings: new downloads go there (already saved files stay where they are). */
    private val _rootFlow = MutableStateFlow(File("."))
    val rootFlow: StateFlow<File> = _rootFlow

    fun setRoot(dir: File) {
        dir.mkdirs()
        root = dir
        _rootFlow.value = dir
        prefs.edit().putString("root", dir.absolutePath).apply()
    }
    private lateinit var listFile: File
    private val lock = Object()
    private val saver = Executors.newSingleThreadExecutor()
    private val workers = HashMap<String, Worker>()
    private var lastSave = 0L

    private val _tasks = MutableStateFlow<List<DTask>>(emptyList())
    val tasks: StateFlow<List<DTask>> = _tasks

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        root = File(prefs.getString("root", null) ?: Platform.defaultDownloads.absolutePath).apply { mkdirs() }
        _rootFlow.value = root
        listFile = File(context.filesDir, "downloads.json")
        val loaded = runCatching { load() }.getOrDefault(emptyList())
        // a download that was running when the app stopped continues from the queue
        _tasks.value = loaded.map {
            when {
                it.state == DState.DONE && !it.toServer && !File(it.path).exists() -> it.copy(state = DState.QUEUED, downloaded = 0)
                it.state == DState.RUNNING || it.state == DState.WAITING -> it.copy(state = DState.QUEUED, message = null)
                else -> it
            }
        }
        if (_tasks.value.any { it.active }) startWork()
    }

    // ------------------------------------------------------------------ public API

    fun items(list: List<DTask> = _tasks.value): List<DItem> =
        list.groupBy { it.itemKey }.map { (_, ts) ->
            val f = ts.first()
            DItem(f.sectionId, f.itemId, f.itemTitle, f.sectionTitle, f.isVideo, ts.sortedBy { it.part }, f.toServer)
        }.sortedByDescending { it.tasks.maxOf { t -> t.added } }

    fun item(sectionId: String, itemId: String, list: List<DTask> = _tasks.value, toServer: Boolean = false): DItem? =
        items(list.filter { it.sectionId == sectionId && it.itemId == itemId && it.toServer == toServer }).firstOrNull()

    /** Local file of a finished part, for offline playback. */
    fun localFile(sectionId: String, itemId: String, part: Int): File? {
        val t = _tasks.value.firstOrNull { it.sectionId == sectionId && it.itemId == itemId && it.part == part && !it.toServer }
        return t?.takeIf { it.state == DState.DONE }?.let { File(it.path) }?.takeIf { it.exists() }
    }

    /** Self-test only (ARHIV_SMOKE): pretend a part is already saved on this computer. */
    fun addDoneForTest(section: Section, item: Item, part: Int, file: File) {
        val t = DTask(
            id = java.util.UUID.randomUUID().toString(), sectionId = section.id, itemId = item.id, itemTitle = item.title,
            sectionTitle = section.title, isVideo = section.kind == Kind.VIDEO, part = part, partsCount = item.parts.size,
            url = "", path = file.absolutePath, total = file.length(), downloaded = file.length(), state = DState.DONE,
            message = null, added = System.currentTimeMillis(),
        )
        synchronized(lock) { _tasks.value = _tasks.value + t }
    }

    fun runningIn(lane: String): DTask? =
        _tasks.value.firstOrNull { it.sectionId == lane && it.state == DState.RUNNING }

    fun hasActive(): Boolean = _tasks.value.any { it.active }

    /**
     * Put a title into the queue. [toServer] chooses the destination. If the title is already saved
     * in the other place, it is copied from there (no archive, no internet); [moveSource] then deletes
     * the other copy when done ("перенести").
     */
    fun enqueue(section: Section, item: Item, toServer: Boolean = false, moveSource: Boolean = false) {
        val now = System.currentTimeMillis()
        val dir = File(File(root, safeName(section.title)), safeName(item.title))
        val smbDir = SmbStore.join(SmbStore.rootPath(), safeName(section.title), safeName(item.title))
        synchronized(lock) {
            val existing = _tasks.value
                .filter { it.sectionId == section.id && it.itemId == item.id && it.toServer == toServer }
                .map { it.part }.toSet()
            val added = item.parts.mapIndexedNotNull { index, part ->
                if (index in existing) return@mapIndexedNotNull null
                val fileName = safeName(decodeName(part.file))
                DTask(
                    id = "${section.id}|${item.id}|$index" + if (toServer) "|smb" else "",
                    sectionId = section.id,
                    itemId = item.id,
                    itemTitle = item.title,
                    sectionTitle = section.title,
                    isVideo = section.kind == Kind.VIDEO,
                    part = index,
                    partsCount = item.parts.size,
                    url = remoteUrl(section.host, part.path),
                    path = if (toServer) SmbStore.join(smbDir, fileName) else File(dir, fileName).absolutePath,
                    total = part.size,
                    downloaded = 0,
                    state = DState.QUEUED,
                    message = null,
                    added = now + index,
                    toServer = toServer,
                    moveSource = moveSource,
                )
            }
            // resume paused / failed parts of this title too
            val resumed = _tasks.value.map {
                if (it.sectionId == section.id && it.itemId == item.id && it.toServer == toServer &&
                    (it.state == DState.PAUSED || it.state == DState.ERROR)
                ) {
                    it.copy(state = DState.QUEUED, message = null)
                } else it
            }
            _tasks.value = resumed + added
        }
        save(force = true)
        startWork()
    }

    fun pause(itemKey: String) {
        update { if (it.itemKey == itemKey && it.active) it.copy(state = DState.PAUSED, message = null) else it }
        workers.values.forEach { it.cancelIfRunning(itemKey) }
        save(force = true)
    }

    fun resume(itemKey: String) {
        update {
            if (it.itemKey == itemKey && (it.state == DState.PAUSED || it.state == DState.ERROR)) it.copy(state = DState.QUEUED, message = null) else it
        }
        save(force = true)
        startWork()
    }

    fun delete(itemKey: String) {
        val victims = _tasks.value.filter { it.itemKey == itemKey }
        synchronized(lock) { _tasks.value = _tasks.value.filter { it.itemKey != itemKey } }
        workers.values.forEach { it.cancelIfRunning(itemKey) }
        val local = victims.filter { !it.toServer }
        local.forEach {
            File(it.path).delete()
            File(it.path + ".part").delete()
        }
        local.firstOrNull()?.let { File(it.path).parentFile?.let { dir -> if (dir.list()?.isEmpty() == true) dir.delete() } }
        victims.firstOrNull { it.toServer }?.let { t ->
            thread(isDaemon = true) {
                val item = SmbStore.item(t.sectionId, t.itemId)
                    ?: SmbStore.readInfo(t.path.substringBeforeLast('\\'))
                runCatching { if (item != null) SmbStore.deleteItem(item) }
            }
        }
        save(force = true)
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun freeBytes(): Long = root.usableSpace

    // ------------------------------------------------------------------ internals

    private fun startWork() {
        val lanes = _tasks.value.filter { it.active }.map { it.sectionId }.toSet()
        synchronized(workers) {
            for (lane in lanes) {
                if (workers[lane]?.alive != true) workers[lane] = Worker(lane).also { it.start() }
            }
        }
    }

    private fun update(transform: (DTask) -> DTask) {
        synchronized(lock) { _tasks.value = _tasks.value.map(transform) }
    }

    private fun updateTask(id: String, transform: (DTask) -> DTask) = update { if (it.id == id) transform(it) else it }

    private fun current(id: String): DTask? = _tasks.value.firstOrNull { it.id == id }

    private fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2_000) return
        lastSave = now
        val snapshot = _tasks.value
        saver.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { t ->
                    arr.put(
                        JSONObject()
                            .put("id", t.id).put("s", t.sectionId).put("i", t.itemId).put("t", t.itemTitle)
                            .put("st", t.sectionTitle).put("v", t.isVideo).put("p", t.part).put("pc", t.partsCount)
                            .put("u", t.url).put("path", t.path).put("total", t.total).put("dl", t.downloaded)
                            .put("state", t.state.name).put("a", t.added).put("smb", t.toServer).put("mv", t.moveSource)
                    )
                }
                val tmp = File(listFile.parentFile, listFile.name + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(listFile)) {
                    listFile.delete()
                    tmp.renameTo(listFile)
                }
            }
        }
    }

    private fun load(): List<DTask> {
        if (!listFile.exists()) return emptyList()
        val arr = JSONArray(listFile.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DTask(
                id = o.getString("id"),
                sectionId = o.getString("s"),
                itemId = o.getString("i"),
                itemTitle = o.optString("t"),
                sectionTitle = o.optString("st"),
                isVideo = o.optBoolean("v", true),
                part = o.optInt("p"),
                partsCount = o.optInt("pc", 1),
                url = o.getString("u"),
                path = o.getString("path"),
                total = o.optLong("total", 0),
                downloaded = o.optLong("dl", 0),
                state = runCatching { DState.valueOf(o.optString("state")) }.getOrDefault(DState.QUEUED),
                message = null,
                added = o.optLong("a", 0),
                toServer = o.optBoolean("smb", false),
                moveSource = o.optBoolean("mv", false),
            )
        }
    }

    private fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.', ' ').take(90).trimEnd('.', ' ').ifEmpty { "file" }

    private fun decodeName(file: String): String =
        runCatching { java.net.URLDecoder.decode(file.replace("+", "%2B"), "UTF-8") }.getOrDefault(file)

    /** Downloads the files of one section, one at a time. */
    private class Worker(val lane: String) : ConnectionGate.Owner {
        @Volatile var alive = true
        @Volatile private var call: Call? = null
        @Volatile private var currentId: String? = null
        @Volatile private var preempted = false
        /** Consecutive failed attempts per task; after MAX_RETRIES retries the task stops with an error. */
        private val failures = HashMap<String, Int>()

        /** Counts a failure. Returns the pause before the next try, or 0 if the task was stopped. */
        private fun failed(task: DTask, reason: String): Long {
            val n = (failures[task.id] ?: 0) + 1
            failures[task.id] = n
            if (n > MAX_RETRIES) {
                failures.remove(task.id)
                updateTask(task.id) {
                    it.copy(state = DState.ERROR, message = "$reason. После $MAX_RETRIES повторов загрузка остановлена — нажмите ▶, чтобы попробовать снова")
                }
                return 0
            }
            val delay = 5_000L shl (n - 1) // 5, 10, 20 s
            updateTask(task.id) { it.copy(state = DState.WAITING, message = "$reason. Повтор $n из $MAX_RETRIES через ${delay / 1000} с") }
            return delay
        }
        private var thread: Thread? = null

        fun start() {
            thread = thread(name = "download-$lane", isDaemon = true) { run() }
        }

        override fun preempt() {
            preempted = true
            call?.cancel()
        }

        fun cancelIfRunning(itemKey: String) {
            val id = currentId ?: return
            if (id.startsWith("$itemKey|")) call?.cancel()
        }

        private fun next(): DTask? = synchronized(lock) {
            _tasks.value.filter { it.sectionId == lane && it.active }.minByOrNull { it.added }
        }

        private fun run() {
            try {
                while (true) {
                    val task = next() ?: break
                    // transfer between this device and the home server: no archive connection needed
                    if (transfer(task)) continue

                    // someone is watching/listening in this section right now: wait politely
                    if (Streams.laneActive(lane)) {
                        updateTask(task.id) { it.copy(state = DState.WAITING, message = "Ждёт: в этом разделе сейчас идёт просмотр") }
                        sleep(5_000)
                        continue
                    }
                    if (task.toServer) {
                        val skip = prepareServer(task)
                        if (skip != null) {
                            if (skip > 0) sleep(skip)
                            continue
                        }
                    }
                    // already completely in the playback cache? just copy it
                    val cached = Streams.completeCacheFile(lane, task.url)
                    if (cached != null && copyFromFile(task, cached, "Копируется из памяти просмотренного")) continue

                    if (!ConnectionGate.tryAcquire(lane, this)) {
                        updateTask(task.id) { it.copy(state = DState.WAITING, message = "Ждёт, пока освободится соединение с разделом") }
                        sleep(5_000)
                        continue
                    }
                    var sleepMs = 0L
                    preempted = false
                    currentId = task.id
                    try {
                        download(task)
                        failures.remove(task.id)
                    } catch (e: IOException) {
                        val now = current(task.id)
                        when {
                            now == null || now.state == DState.PAUSED -> Unit // deleted or paused by the user
                            preempted -> updateTask(task.id) { it.copy(state = DState.WAITING, message = "Приостановлено на время просмотра") }
                            e.message == "busy" -> sleepMs = failed(task, "Архив занят (возможно, открыт на другом устройстве дома)")
                            e.message?.startsWith("HTTP ") == true -> sleepMs = failed(task, "Ошибка архива: ${e.message}")
                            else -> sleepMs = failed(task, "Нет связи с архивом")
                        }
                    } finally {
                        call = null
                        currentId = null
                        ConnectionGate.release(lane, this)
                        save(force = true)
                    }
                    if (sleepMs > 0) sleep(sleepMs)
                }
            } finally {
                alive = false
                synchronized(workers) { if (workers[lane] === this) workers.remove(lane) }
                // tasks may have been added while we were finishing
                if (_tasks.value.any { it.sectionId == lane && it.active }) startWork()
            }
        }

        /** Copies a part from the other place if it is saved there. Returns true if handled. */
        private fun transfer(task: DTask): Boolean {
            if (task.toServer) {
                val local = localFile(task.sectionId, task.itemId, task.part) ?: return false
                val skip = prepareServer(task)
                if (skip != null) {
                    if (skip > 0) sleep(skip) else if (skip == 0L) afterTransfer(task) // 0: already on the server
                    return true
                }
                currentId = task.id
                try {
                    if (!copyFromFile(task, local, "Переносится с компьютера на сервер")) {
                        if (current(task.id)?.state != DState.PAUSED) sleep(failed(task, "Не удалось записать на сервер"))
                        return true
                    }
                    failures.remove(task.id)
                } finally {
                    currentId = null
                }
            } else {
                val smbPath = SmbStore.donePartPath(task.sectionId, task.itemId, task.part) ?: return false
                currentId = task.id
                try {
                    copyFromServer(task, smbPath)
                    failures.remove(task.id)
                } catch (e: Exception) {
                    val now = current(task.id)
                    if (now != null && now.state != DState.PAUSED) {
                        sleep(failed(task, "Домашний сервер недоступен: ${SmbStore.readable(e)}"))
                    }
                    return true
                } finally {
                    currentId = null
                    save(force = true)
                }
            }
            afterTransfer(task)
            return true
        }

        /** Server → device copy, resumable. */
        private fun copyFromServer(task: DTask, smbPath: String) {
            val target = File(task.path)
            target.parentFile?.mkdirs()
            val part = File(task.path + ".part")
            SmbStore.openReader(smbPath).use { r ->
                var done = if (part.exists()) part.length().coerceAtMost(r.size) else 0L
                updateTask(task.id) { it.copy(state = DState.RUNNING, total = r.size, downloaded = done, message = "Переносится с сервера на компьютер") }
                FileOutputStream(part, done > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastUi = 0L
                    while (done < r.size) {
                        val st = current(task.id)?.state
                        if (st == null || st == DState.PAUSED) throw IOException("stopped")
                        val n = r.read(done, buf, 0, buf.size)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 500) {
                            lastUi = now
                            val d = done
                            updateTask(task.id) { it.copy(downloaded = d) }
                            save()
                        }
                    }
                    out.flush()
                }
                if (done >= r.size) finish(task, part, target, done)
            }
        }

        /** "Перенести": once the whole title is in the new place, remove the old copy. */
        private fun afterTransfer(task: DTask) {
            if (!task.moveSource) return
            val all = _tasks.value.filter { it.itemKey == task.itemKey }
            if (all.isEmpty() || all.any { it.state != DState.DONE }) return
            if (task.toServer) {
                delete("${task.sectionId}|${task.itemId}") // the device copy
            } else {
                SmbStore.item(task.sectionId, task.itemId)?.let { runCatching { SmbStore.deleteItem(it) } }
                delete("${task.sectionId}|${task.itemId}|smb")
            }
        }

        /**
         * Before a server download: the title folder and info.json exist; the part may already be
         * on the server (another device) or being downloaded by another device right now.
         * Returns null to go on, 0 to skip this task, or a pause in ms to wait.
         */
        private fun prepareServer(task: DTask): Long? = try {
            val folder = task.path.substringBeforeLast('\\')
            val info = SmbStore.readInfo(folder)
            val part = info?.parts?.firstOrNull { it.part == task.part }
            when {
                part?.done == true -> {
                    updateTask(task.id) { it.copy(state = DState.DONE, downloaded = part.size, total = part.size, message = null) }
                    save(force = true)
                    0L
                }
                info != null && info.busyElsewhere -> {
                    updateTask(task.id) { it.copy(state = DState.WAITING, message = "Загружается на другом устройстве: ${info.busyDevice}") }
                    30_000L
                }
                else -> {
                    heartbeat(task, folder)
                    null
                }
            }
        } catch (e: Exception) {
            failed(task, "Домашний сервер недоступен: ${SmbStore.readable(e)}").takeIf { it > 0 } ?: -1L // -1: stopped with an error
        }

        private fun baseInfo(task: DTask, folder: String): SmbItem {
            val all = _tasks.value.filter { it.itemKey == task.itemKey }.sortedBy { it.part }
            return SmbItem(
                sectionId = task.sectionId,
                itemId = task.itemId,
                title = task.itemTitle,
                sectionTitle = task.sectionTitle,
                isVideo = task.isVideo,
                folder = folder,
                parts = all.map { SmbPart(it.part, it.path.substringAfterLast('\\'), it.total, it.state == DState.DONE) },
                busyDevice = null,
                busyAt = 0,
            )
        }

        /** Marks the title as "being downloaded by this device" in info.json. */
        private fun heartbeat(task: DTask, folder: String, partDone: Boolean = false, size: Long = 0) {
            SmbStore.updateInfo(folder, baseInfo(task, folder)) { cur ->
                val known = cur.parts.associateBy { it.part }.toMutableMap()
                for (p in baseInfo(task, folder).parts) if (p.part !in known) known[p.part] = p
                if (partDone) known[task.part] = SmbPart(task.part, task.path.substringAfterLast('\\'), size, true)
                cur.copy(
                    parts = known.values.sortedBy { it.part },
                    busyDevice = if (partDone && task.part == task.partsCount - 1) null else SmbStore.deviceName,
                    busyAt = System.currentTimeMillis(),
                )
            }
        }

        private fun download(task: DTask) {
            if (task.toServer) return downloadToServer(task)
            val target = File(task.path)
            target.parentFile?.mkdirs()
            val part = File(task.path + ".part")
            var start = if (part.exists()) part.length() else 0L
            val request = Request.Builder()
                .url(task.url)
                .header("User-Agent", Net.USER_AGENT)
                .apply { if (start > 0) header("Range", "bytes=$start-") }
                .build()
            val c = Net.client.newCall(request)
            call = c
            c.execute().use { resp ->
                if (resp.code == 503 || resp.code == 429) throw IOException("busy")
                if (resp.code == 416) {
                    // the .part file is already complete
                    finish(task, part, target, part.length())
                    return
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                var total = task.total
                if (resp.code == 206) {
                    Regex("""/(\d+)""").find(resp.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { total = it }
                } else {
                    // whole file from the beginning
                    start = 0
                    if (body.contentLength() > 0) total = body.contentLength()
                }
                updateTask(task.id) { it.copy(state = DState.RUNNING, total = total, downloaded = start, message = null) }
                FileOutputStream(part, start > 0).use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var lastUi = 0L
                    var speedBytes = 0L
                    var speedT = System.currentTimeMillis()
                    var speedText = ""
                    while (true) {
                        val st = current(task.id)?.state
                        if (st == null || st == DState.PAUSED || preempted) throw IOException("stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        speedBytes += n
                        val now = System.currentTimeMillis()
                        if (now - speedT >= 1_000) {
                            speedText = "%.1f МБ/с".format(speedBytes / 1_000_000.0 / ((now - speedT) / 1000.0))
                            speedBytes = 0
                            speedT = now
                        }
                        if (now - lastUi >= 500) {
                            lastUi = now
                            val d = done
                            val sp = speedText
                            updateTask(task.id) { it.copy(downloaded = d, message = sp.ifEmpty { null }) }
                            save()
                        }
                    }
                    out.flush()
                    finish(task, part, target, done)
                }
            }
        }

        /** Same as [download], but writes straight into the file on the SMB server (resumable). */
        private fun downloadToServer(task: DTask) {
            val folder = task.path.substringBeforeLast('\\')
            val partPath = task.path + ".part"
            var start = SmbStore.size(partPath) ?: 0L
            val request = Request.Builder()
                .url(task.url)
                .header("User-Agent", Net.USER_AGENT)
                .apply { if (start > 0) header("Range", "bytes=$start-") }
                .build()
            val c = Net.client.newCall(request)
            call = c
            c.execute().use { resp ->
                if (resp.code == 503 || resp.code == 429) throw IOException("busy")
                if (resp.code == 416) {
                    finishServer(task, folder, partPath, start)
                    return
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                var total = task.total
                if (resp.code == 206) {
                    Regex("""/(\d+)""").find(resp.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { total = it }
                } else {
                    start = 0
                    if (body.contentLength() > 0) total = body.contentLength()
                }
                updateTask(task.id) { it.copy(state = DState.RUNNING, total = total, downloaded = start, message = null) }
                val out = try {
                    SmbStore.openWriter(partPath)
                } catch (e: Exception) {
                    throw IOException("Домашний сервер: ${SmbStore.readable(e)}")
                }
                out.use { file ->
                    val input = body.byteStream()
                    val buf = ByteArray(256 * 1024)
                    var done = start
                    var lastUi = 0L
                    var lastBeat = System.currentTimeMillis()
                    var speedBytes = 0L
                    var speedT = System.currentTimeMillis()
                    var speedText = ""
                    while (true) {
                        val st = current(task.id)?.state
                        if (st == null || st == DState.PAUSED || preempted) throw IOException("stopped")
                        // fill the buffer a bit before each network write to the server
                        var filled = 0
                        while (filled < buf.size) {
                            val n = input.read(buf, filled, buf.size - filled)
                            if (n < 0) break
                            filled += n
                            if (filled >= 64 * 1024 && input.available() == 0) break
                        }
                        if (filled == 0) break
                        try {
                            file.write(buf, done, 0, filled)
                        } catch (e: Exception) {
                            throw IOException("Домашний сервер: ${SmbStore.readable(e)}")
                        }
                        done += filled
                        speedBytes += filled
                        val now = System.currentTimeMillis()
                        if (now - speedT >= 1_000) {
                            speedText = "%.1f МБ/с".format(speedBytes / 1_000_000.0 / ((now - speedT) / 1000.0))
                            speedBytes = 0
                            speedT = now
                        }
                        if (now - lastUi >= 500) {
                            lastUi = now
                            val d = done
                            val sp = speedText
                            updateTask(task.id) { it.copy(downloaded = d, message = sp.ifEmpty { null }) }
                            save()
                        }
                        if (now - lastBeat >= 60_000) {
                            lastBeat = now
                            runCatching { heartbeat(task, folder) }
                        }
                    }
                    file.flush()
                }
                finishServer(task, folder, partPath, current(task.id)?.downloaded?.coerceAtLeast(start) ?: start)
            }
        }

        private fun finishServer(task: DTask, folder: String, partPath: String, size: Long) {
            val real = SmbStore.size(partPath) ?: size
            SmbStore.rename(partPath, task.path)
            updateTask(task.id) { it.copy(state = DState.DONE, downloaded = real, total = maxOf(real, it.total), message = null) }
            runCatching { heartbeat(task, folder, partDone = true, size = real) }
            save(force = true)
        }

        private fun finish(task: DTask, part: File, target: File, size: Long) {
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            updateTask(task.id) { it.copy(state = DState.DONE, downloaded = size, total = maxOf(size, it.total), message = null) }
        }

        private fun copyFromFile(task: DTask, cached: File, message: String): Boolean = runCatching {
            updateTask(task.id) { it.copy(state = DState.RUNNING, total = cached.length(), message = message) }
            if (task.toServer) {
                val folder = task.path.substringBeforeLast('\\')
                val partPath = task.path + ".part"
                SmbStore.openWriter(partPath).use { file ->
                    cached.inputStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var pos = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            file.write(buf, pos, 0, n)
                            pos += n
                            if (pos % (4L * 1024 * 1024) < n) updateTask(task.id) { it.copy(downloaded = pos) }
                        }
                    }
                }
                finishServer(task, folder, partPath, cached.length())
                return@runCatching true
            }
            val target = File(task.path)
            target.parentFile?.mkdirs()
            cached.copyTo(target, overwrite = true)
            updateTask(task.id) { it.copy(state = DState.DONE, downloaded = target.length(), total = target.length(), message = null) }
            save(force = true)
            true
        }.getOrDefault(false)

        private fun sleep(ms: Long) {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250)
                val t = next() ?: return
                if (t.state == DState.QUEUED && ms > 5_000) return // user pressed "resume"
            }
        }
    }
}
