package net.family.multiki.data

import net.family.multiki.platform.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** One watched / listened title: where it was stopped and whether it was finished. */
data class HistoryEntry(
    val sectionId: String,
    val itemId: String,
    val title: String,
    val sectionTitle: String,
    val isVideo: Boolean,
    val part: Int,          // index of the series / track
    val partsCount: Int,
    val positionMs: Long,   // inside the part
    val durationMs: Long,   // of the part
    val updated: Long,
    val finished: Boolean,
    val device: String = History.deviceName, // who watched last (shown when continuing on another device)
    val deviceId: String = History.deviceId,
    val deleted: Boolean = false,            // tombstone, so a removal reaches the other devices too
) {
    val key: String get() = keyOf(sectionId, itemId)

    /** Worth offering "continue": started, not finished, not just the first seconds. */
    val canResume: Boolean get() = !finished && (positionMs >= 10_000 || part > 0)

    /** 0..1 over the whole title (all series / tracks). */
    val progress: Float
        get() {
            if (finished) return 1f
            val inPart = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            return ((part + inPart) / partsCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        }

    companion object {
        fun keyOf(sectionId: String, itemId: String) = "$sectionId|$itemId"
    }
}

/**
 * Viewing history, stored as a small JSON file in the app's private storage.
 * With a home server configured it is also shared between devices (see HistorySync):
 * for each title the newest record wins, removals travel as tombstones.
 */
object History {
    private const val MAX_ENTRIES = 500
    private const val TOMBSTONE_DAYS = 60L
    private lateinit var file: File
    private val writer = Executors.newSingleThreadExecutor()
    private var all: List<HistoryEntry> = emptyList() // including tombstones
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Newest first, without removed ones. */
    val entries: StateFlow<List<HistoryEntry>> = _entries

    var deviceName: String = net.family.multiki.platform.Platform.deviceName
        private set
    var deviceId: String = "local"
        private set

    /** Called after every local change (HistorySync uploads it to the server). */
    @Volatile var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        deviceId = prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        deviceName = net.family.multiki.platform.Platform.deviceName
        all = runCatching { load() }.getOrDefault(emptyList())
        publish()
    }

    fun get(sectionId: String, itemId: String): HistoryEntry? {
        val key = HistoryEntry.keyOf(sectionId, itemId)
        return _entries.value.firstOrNull { it.key == key }
    }

    @Synchronized
    fun update(entry: HistoryEntry) {
        put(entry.copy(device = deviceName, deviceId = deviceId, deleted = false))
    }

    @Synchronized
    fun remove(key: String) {
        val e = all.firstOrNull { it.key == key } ?: return
        put(e.copy(deleted = true, updated = System.currentTimeMillis(), device = deviceName, deviceId = deviceId))
    }

    @Synchronized
    fun clear() {
        val now = System.currentTimeMillis()
        all = all.map { it.copy(deleted = true, updated = now, device = deviceName, deviceId = deviceId) }
        publish()
        save()
        onChanged?.invoke()
    }

    /** Records written by this device (sent to its own file on the server). */
    @Synchronized
    fun ownRecords(): List<HistoryEntry> = all.filter { it.deviceId == deviceId }

    /** Takes records from other devices: for each title the newest one wins. */
    @Synchronized
    fun mergeRemote(remote: List<HistoryEntry>) {
        val map = all.associateBy { it.key }.toMutableMap()
        var changed = false
        for (r in remote) {
            val l = map[r.key]
            if (l == null || r.updated > l.updated) {
                map[r.key] = r
                changed = true
            }
        }
        if (!changed) return
        all = map.values.toList()
        publish()
        save()
    }

    private fun put(entry: HistoryEntry) {
        all = listOf(entry) + all.filter { it.key != entry.key }
        publish()
        save()
        onChanged?.invoke()
    }

    private fun publish() {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_DAYS * 24 * 3600 * 1000
        all = all.filter { !it.deleted || it.updated > cutoff }.sortedByDescending { it.updated }
        _entries.value = all.filter { !it.deleted }.take(MAX_ENTRIES)
    }

    fun toJson(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("s", e.sectionId).put("i", e.itemId).put("t", e.title).put("st", e.sectionTitle)
                    .put("v", e.isVideo).put("p", e.part).put("pc", e.partsCount)
                    .put("pos", e.positionMs).put("dur", e.durationMs)
                    .put("u", e.updated).put("f", e.finished)
                    .put("dev", e.device).put("did", e.deviceId).put("del", e.deleted)
            )
        }
        return arr.toString()
    }

    fun fromJson(text: String): List<HistoryEntry> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                sectionId = o.getString("s"),
                itemId = o.getString("i"),
                title = o.optString("t"),
                sectionTitle = o.optString("st"),
                isVideo = o.optBoolean("v", true),
                part = o.optInt("p", 0),
                partsCount = o.optInt("pc", 1),
                positionMs = o.optLong("pos", 0),
                durationMs = o.optLong("dur", 0),
                updated = o.optLong("u", 0),
                finished = o.optBoolean("f", false),
                device = o.optString("dev", deviceName),
                deviceId = o.optString("did", deviceId),
                deleted = o.optBoolean("del", false),
            )
        }
    }

    private fun save() {
        if (!::file.isInitialized) return
        val text = toJson(all)
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun load(): List<HistoryEntry> = if (!file.exists()) emptyList() else fromJson(file.readText())
}

private val ru = Locale("ru")

/** "сегодня, 21:30", "вчера, 09:15", "7 ноября, 18:02", "7 ноября 2025" */
fun formatWhen(time: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val hm = SimpleDateFormat("HH:mm", ru).format(Date(time))
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "сегодня, $hm"
        sameYear && dayDiff == 1 -> "вчера, $hm"
        sameYear -> SimpleDateFormat("d MMMM, HH:mm", ru).format(Date(time))
        else -> SimpleDateFormat("d MMMM yyyy", ru).format(Date(time))
    }
}
