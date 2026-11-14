package net.family.multiki.platform

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Properties

/**
 * The few things the shared (Android-born) code needs from its surroundings, Windows edition:
 * folders for app data and cache, small key-value settings, bundled read-only files.
 */
object Platform {
    private val home = System.getProperty("user.home") ?: "."

    /** %APPDATA%\ArhivVeshchaniya — settings, history, download list. */
    val dataDir: File = File(System.getenv("APPDATA") ?: home, "ArhivVeshchaniya").apply { mkdirs() }

    /** %LOCALAPPDATA%\ArhivVeshchaniya\cache — streaming cache and thumbnails. */
    val cacheDir: File = File(System.getenv("LOCALAPPDATA") ?: home, "ArhivVeshchaniya\\cache".replace('\\', File.separatorChar)).apply { mkdirs() }

    /** «Компьютер …» — shown on other devices in the history («на …»). */
    val deviceName: String = run {
        val n = System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Windows"
        "Компьютер $n"
    }

    /** Default folder for downloads until the user chooses another one in «Настройках». */
    val defaultDownloads: File = File(File(home, "Videos").takeIf { it.isDirectory } ?: File(home), "Архив Вещания")

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
}

/** Settings file %APPDATA%\ArhivVeshchaniya\<name>.properties, with the Android SharedPreferences calls the code uses. */
class SharedPreferences internal constructor(name: String) {
    private val file = File(Platform.dataDir, "$name.properties")
    private val props = Properties().apply { runCatching { file.reader(Charsets.UTF_8).use { load(it) } } }

    fun getString(key: String, def: String?): String? = synchronized(props) { props.getProperty(key) ?: def }
    fun getBoolean(key: String, def: Boolean): Boolean = getString(key, null)?.toBooleanStrictOrNull() ?: def
    fun getInt(key: String, def: Int): Int = getString(key, null)?.toIntOrNull() ?: def
    fun getLong(key: String, def: Long): Long = getString(key, null)?.toLongOrNull() ?: def
    fun contains(key: String): Boolean = synchronized(props) { props.containsKey(key) }

    fun edit(): Editor = Editor()

    inner class Editor {
        private val changes = LinkedHashMap<String, String?>()
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() { commit() }
        fun commit(): Boolean = synchronized(props) {
            changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writer(Charsets.UTF_8).use { props.store(it, "Архив Вещания") }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.isSuccess
        }
    }
}

/** Read-only files bundled with the program (the catalog). */
class Assets internal constructor() {
    fun open(name: String): InputStream =
        Assets::class.java.getResourceAsStream("/$name") ?: throw IOException("Нет файла $name")
}

/** Stand-in for android.content.Context: one per app. */
open class Context internal constructor() {
    val filesDir: File get() = Platform.dataDir
    val cacheDir: File get() = Platform.cacheDir
    val assets = Assets()
    val applicationContext: Context get() = this
    fun getSharedPreferences(name: String, @Suppress("UNUSED_PARAMETER") mode: Int): SharedPreferences =
        prefsCache.getOrPut(name) { SharedPreferences(name) }

    companion object {
        const val MODE_PRIVATE = 0
        private val prefsCache = HashMap<String, SharedPreferences>()
    }
}

object AppContext : Context()
