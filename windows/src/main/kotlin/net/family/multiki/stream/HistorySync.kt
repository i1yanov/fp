package net.family.multiki.stream

import net.family.multiki.data.History
import kotlin.concurrent.thread

/**
 * Shares the viewing history (where each film/tale was stopped) through the home server,
 * so you can stop on the TV and continue on the tablet.
 *
 * Every device writes only its own file: <folder>\.история\<device id>.json — devices never
 * overwrite each other. Every device reads all the files and keeps, for each title, the newest record.
 */
object HistorySync {
    private const val DIR = ".история"
    private val lock = Object()
    @Volatile private var dirty = true
    @Volatile private var wantRead = true
    private var lastWrite = 0L
    private var lastRead = 0L
    private var started = false

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        History.onChanged = {
            dirty = true
            synchronized(lock) { lock.notifyAll() }
        }
        thread(name = "history-sync", isDaemon = true) {
            while (true) {
                synchronized(lock) { lock.wait(5_000) }
                if (!SmbStore.configured) continue
                val now = System.currentTimeMillis()
                // upload our changes at most every 15 s (the audio player saves every 5 s)
                val write = dirty && now - lastWrite >= 15_000
                val read = wantRead || now - lastRead >= 30_000
                if (!write && !read) continue
                runCatching { sync(write) }
            }
        }
    }

    /** Upload right away (pause, leaving the player) so another device can continue at once. */
    fun flush() {
        dirty = true
        lastWrite = 0
        synchronized(lock) { lock.notifyAll() }
    }

    /** Fetch the newest positions from the other devices soon (e.g. when a card is opened). */
    fun requestNow() {
        wantRead = true
        synchronized(lock) { lock.notifyAll() }
    }

    private fun sync(write: Boolean) {
        val dir = SmbStore.join(SmbStore.rootPath(), DIR)
        if (write) {
            dirty = false
            try {
                SmbStore.writeText(SmbStore.join(dir, History.deviceId + ".json"), History.toJson(History.ownRecords()))
                lastWrite = System.currentTimeMillis()
            } catch (e: Exception) {
                dirty = true
                throw e
            }
        }
        wantRead = false
        val remote = SmbStore.readTextFiles(dir, ".json")
            .flatMap { text -> runCatching { History.fromJson(text) }.getOrDefault(emptyList()) }
            .filter { it.deviceId != History.deviceId }
        History.mergeRemote(remote)
        lastRead = System.currentTimeMillis()
    }
}
