package net.family.multiki.data

import net.family.multiki.platform.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

enum class Kind { VIDEO, AUDIO }

data class Part(
    val n: Int,
    val file: String,
    val path: String,
    val size: Long,
    val resolution: String,
    val durationSec: Int,
    val group: String,
)

data class Item(
    val id: String,
    val title: String,
    val titleAuto: Boolean,
    val durationSec: Int,
    val size: Long,
    val added: String?,
    val cover: String?,
    val description: String?,
    val parts: List<Part>,
) {
    /** Lower-cased title with ё→е for search. */
    val searchKey: String = normalize(title)
}

data class SectionInfo(
    val id: String,
    val title: String,
    val count: Int,
)

data class Section(
    val id: String,
    val title: String,
    val host: String,
    val kind: Kind,
    val randomLabel: String,
    val items: List<Item>,
) {
    fun baseUrl() = "http://$host"

    /** Small preview picture. Video: frame 1 from Cloudflare. Audio: null (loaded later through the connection gate). */
    fun thumbUrl(item: Item, frame: Int = 1): String? =
        if (kind == Kind.VIDEO) "${baseUrl()}/ap/${item.id}/${item.id}.thumb$frame.jpg" else null

    fun imageUrl(item: Item, frame: Int): String? =
        if (kind == Kind.VIDEO) "${baseUrl()}/ap/${item.id}/${item.id}.image$frame.jpg" else null
}

fun normalize(s: String): String = s.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

/** Russian plural: 1 мультфильм, 2 мультфильма, 5 мультфильмов. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f ГБ".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%d МБ".format(bytes / 1_000_000L)
    else -> "%d КБ".format(bytes / 1000L)
}

fun formatCount(n: Int): String = "%,d".format(n).replace(',', ' ')

/** One shared catalog for the app and the background audio service. */
object Catalogs {
    @Volatile private var instance: CatalogRepository? = null
    fun repo(context: Context): CatalogRepository =
        instance ?: synchronized(this) { instance ?: CatalogRepository(context.applicationContext).also { instance = it } }
}

/** Next title for «Что дальше?»: the following one in catalog order (wrapping), or a random other one. */
fun Section.nextAfter(itemId: String, random: Boolean): Item? {
    if (items.isEmpty()) return null
    if (random) return items.filter { it.id != itemId }.randomOrNull() ?: items.first()
    val i = items.indexOfFirst { it.id == itemId }
    return items[(i + 1).mod(items.size)]
}

/** Loads the bundled catalog from assets/catalog. Sections are parsed lazily and cached. */
class CatalogRepository(private val context: Context) {
    private val cache = HashMap<String, Section>()
    private val mutex = Mutex()

    suspend fun sections(): List<SectionInfo> = withContext(Dispatchers.IO) {
        val text = context.assets.open("catalog/index.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONObject(text).getJSONArray("sections")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SectionInfo(o.getString("id"), o.getString("title"), o.getInt("count"))
        }
    }

    suspend fun section(id: String): Section = mutex.withLock {
        cache[id] ?: withContext(Dispatchers.Default) { load(id) }.also { cache[id] = it }
    }

    private fun load(id: String): Section {
        val bytes = readCatalogFile(id)
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val kind = if (o.optString("kind") == "audio") Kind.AUDIO else Kind.VIDEO
        val arr = o.getJSONArray("items")
        val items = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) items += parseItem(arr.getJSONObject(i))
        return Section(
            id = o.getString("id"),
            title = o.getString("title"),
            host = o.getString("host"),
            kind = kind,
            randomLabel = o.optString("random", "Случайный выбор"),
            items = items,
        )
    }

    /**
     * Finds the section file in assets under any of the names the build may have produced
     * and un-gzips it if needed (detected by the gzip signature 1F 8B).
     */
    private fun readCatalogFile(id: String): ByteArray {
        val names = listOf("catalog/$id.jgz", "catalog/$id.json.gz", "catalog/$id.json")
        var lastError: Exception? = null
        for (name in names) {
            val raw = try {
                context.assets.open(name).use { it.readBytes() }
            } catch (e: java.io.IOException) {
                lastError = e
                continue
            }
            val gzipped = raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
            return if (gzipped) GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
        }
        throw lastError ?: java.io.FileNotFoundException("catalog/$id")
    }

    private fun parseItem(o: JSONObject): Item {
        val pa: JSONArray = o.getJSONArray("parts")
        val parts = (0 until pa.length()).map { j ->
            val p = pa.getJSONObject(j)
            Part(
                n = p.optInt("n", j + 1),
                file = p.getString("file"),
                path = p.getString("path"),
                size = p.optLong("size", 0L),
                resolution = p.optString("res", ""),
                durationSec = p.optInt("dur", 0),
                group = p.optString("group", ""),
            )
        }
        return Item(
            id = o.getString("id"),
            title = o.getString("title"),
            titleAuto = o.optBoolean("title_auto", false),
            durationSec = o.optInt("dur", parts.sumOf { it.durationSec }),
            size = o.optLong("size", parts.sumOf { it.size }),
            added = o.optStringOrNull("added"),
            cover = o.optStringOrNull("thumb"),
            description = o.optStringOrNull("descr"),
            parts = parts,
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
