package net.family.multiki.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.family.multiki.platform.Platform
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Thumbnails from Cloudflare (the archive's pictures, not its file server).
 * Gentle on the site: at most 4 pictures at a time, memory cache + 150 MB disk cache, so pages open instantly the second time.
 */
object Thumbs {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val gate = Semaphore(4)
    private val dir = File(Platform.cacheDir, "thumbs").apply { mkdirs() }
    private const val DISK_LIMIT = 150L * 1024 * 1024
    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }
    private val failed = HashSet<String>()
    @Volatile private var trimmed = false

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? {
        if (System.getenv("ARHIV_SMOKE") != null) return null // build-machine self-test: no requests to the site
        cached(url)?.let { return it }
        if (synchronized(failed) { url in failed }) return null
        return withContext(Dispatchers.IO) {
            val file = File(dir, sha1(url))
            val bytes = if (file.isFile && file.length() > 0) runCatching { file.readBytes() }.getOrNull() else null
            val data = bytes ?: gate.withPermit {
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.bytes()
                    }
                }.getOrNull()
            }?.also { if (bytes == null) runCatching { file.writeBytes(it); trimLater() } }
            val img = data?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
            if (img == null) synchronized(failed) { failed += url } else synchronized(memory) { memory[url] = img }
            img
        }
    }

    private fun trimLater() {
        if (trimmed) return
        trimmed = true
        Thread {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@Thread
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= DISK_LIMIT) break
                total -= f.length()
                f.delete()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Same call shape as Coil's AsyncImage in the Android version. Shows nothing until the picture arrives. */
@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val url = model as? String
    var img by remember(url) { mutableStateOf(url?.let { Thumbs.cached(it) }) }
    LaunchedEffect(url) {
        if (url != null && img == null) img = Thumbs.load(url)
    }
    img?.let { Image(it, contentDescription, modifier, contentScale = contentScale) }
}
