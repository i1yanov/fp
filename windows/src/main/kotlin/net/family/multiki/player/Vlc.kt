package net.family.multiki.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * VLC inside the program. The installer carries libvlc.dll, libvlccore.dll and the plugins in
 * <app>\resources\vlc; when run from the IDE an installed VLC is used instead.
 */
object Vlc {
    @Volatile var error: String? = null
        private set

    val factory: MediaPlayerFactory? by lazy {
        runCatching {
            val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
            if (bundled != null && File(bundled, "libvlc.dll").isFile) {
                System.setProperty("jna.library.path", bundled.absolutePath)
            }
            NativeDiscovery().discover()
            MediaPlayerFactory(
                "--avi-index=2",          // never ask about / rebuild a broken AVI index, just play
                "--network-caching=3000",
                "--file-caching=1500",
                "--no-video-title-show",
                "--no-snapshot-preview",
                "--no-stats",
                "--quiet",
            )
        }.onFailure { error = "Не удалось запустить VLC: ${it.message ?: it}" }.getOrNull()
    }
}

/**
 * Receives decoded frames from VLC and hands them to Compose, so the player controls can be drawn on top of the video.
 * (Same approach as the Compose for Desktop video player sample.)
 */
class VideoFrames : BufferFormatCallback, RenderCallback {
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var aspect by mutableStateOf(4f / 3f)
        private set

    private var bitmap: Bitmap? = null
    private var info: ImageInfo? = null
    private var bytes = ByteArray(0)
    private val pending = AtomicBoolean(false)

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        val w = sourceWidth.coerceAtLeast(2)
        val h = sourceHeight.coerceAtLeast(2)
        val i = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        SwingUtilities.invokeLater {
            info = i
            bitmap = Bitmap().apply { allocPixels(i) }
            bytes = ByteArray(w * h * 4)
            aspect = w.toFloat() / h
        }
        return RV32BufferFormat(w, h)
    }

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        if (!pending.compareAndSet(false, true)) return // the screen is behind: skip this frame
        val src = nativeBuffers[0]
        val len = bufferFormat.width * bufferFormat.height * 4
        val copy = ByteArray(len)
        src.duplicate().apply { rewind() }.get(copy, 0, minOf(len, src.capacity()))
        SwingUtilities.invokeLater {
            try {
                val bmp = bitmap
                val i = info
                if (bmp != null && i != null && i.width == bufferFormat.width && i.height == bufferFormat.height) {
                    bmp.installPixels(i, copy, i.width * 4)
                    image = bmp.asComposeImageBitmap()
                }
            } finally {
                pending.set(false)
            }
        }
    }

    fun clear() {
        image = null
    }
}
