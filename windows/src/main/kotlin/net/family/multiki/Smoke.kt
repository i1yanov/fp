package net.family.multiki

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.delay
import net.family.multiki.player.Vlc
import net.family.multiki.stream.Downloads
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

/**
 * Self-test for the build machine (only when ARHIV_SMOKE=<folder> is set): walks through the screens,
 * plays a test video and a test sound through VLC from local files, saves a screenshot of each step.
 * Never connects to the archive.
 */
object Smoke {
    val dir: File? = System.getenv("ARHIV_SMOKE")?.let { File(it).apply { mkdirs() } }

    fun log(text: String) {
        dir?.let { File(it, "smoke.log").appendText("${System.currentTimeMillis()} $text\n") }
    }

    suspend fun run(vm: MainViewModel, window: ComposeWindow, fullscreen: Fullscreen) {
        val out = dir ?: return
        try {
            delay(4000)
            shot(window, out, "01-home")
            val video = System.getenv("ARHIV_SMOKE_VIDEO")?.let(::File)
            val audio = System.getenv("ARHIV_SMOKE_AUDIO")?.let(::File)
            val mult = vm.section("multiki")
            val film = mult.items.first { it.parts.size == 1 }
            if (video != null) Downloads.addDoneForTest(mult, film, 0, video)
            vm.open(Screen.SectionList("multiki")); delay(3500); shot(window, out, "02-section")
            vm.open(Screen.Details("multiki", film.id)); delay(2500); shot(window, out, "03-details")
            log("vlc: ${Vlc.factory != null} ${Vlc.error ?: ""}")
            vm.play(mult, film); delay(6000); shot(window, out, "04-video")
            fullscreen.set(true); delay(2500); shotScreen(out, "04b-fullscreen")
            fullscreen.set(false); delay(1500); shot(window, out, "04c-back-in-window")
            vm.back(); delay(1500)
            val tales = vm.section("audio")
            val tale = tales.items.first { it.parts.size >= 2 }
            if (audio != null) tale.parts.indices.forEach { Downloads.addDoneForTest(tales, tale, it, audio) }
            vm.play(tales, tale); delay(5000); shot(window, out, "05-audio")
            log("audio state: ${vm.audio.state}")
            vm.home(); delay(1500); shot(window, out, "06-home-miniplayer")
            vm.open(Screen.Downloads); delay(2000); shot(window, out, "07-downloads")
            vm.open(Screen.HistoryList); delay(2000); shot(window, out, "08-history")
            vm.open(Screen.Settings); delay(2000); shot(window, out, "09-settings")
            log("done")
        } catch (e: Throwable) {
            log("ERROR: ${e.stackTraceToString()}")
        } finally {
            delay(500)
            vm.close()
            Runtime.getRuntime().halt(0)
        }
    }

    private fun shotScreen(out: File, name: String) {
        runCatching {
            val size = java.awt.Toolkit.getDefaultToolkit().screenSize
            ImageIO.write(Robot().createScreenCapture(Rectangle(size)), "png", File(out, "$name.png"))
            log("shot $name")
        }.onFailure { log("shot $name failed: $it") }
    }

    private fun shot(window: ComposeWindow, out: File, name: String) {
        runCatching {
            val img = Robot().createScreenCapture(Rectangle(window.locationOnScreen, window.size))
            ImageIO.write(img, "png", File(out, "$name.png"))
            log("shot $name")
        }.onFailure { log("shot $name failed: $it") }
    }
}
