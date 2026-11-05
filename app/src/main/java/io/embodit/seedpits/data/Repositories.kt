package io.embodit.seedpits.data

import android.content.Context
import android.content.SharedPreferences
import io.embodit.seedpits.domain.game.Language
import io.embodit.seedpits.domain.game.Settings
import io.embodit.seedpits.domain.game.Volume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** One save slot. A write replaces the previous save only when it succeeds. */
interface GameRepository {
    fun load(): String?
    fun save(text: String): Boolean
    fun clear()
}

class FakeGameRepository(var text: String? = null) : GameRepository {
    var writes = 0
    override fun load(): String? = text
    override fun save(text: String): Boolean { writes++; this.text = text; return true }
    override fun clear() { text = null }
}

/** files/game.txt: temp file, read back, atomic rename, one backup (Chess Stores.kt). */
class FileGameRepository(context: Context) : GameRepository {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): String? = runCatching { if (file.exists()) file.readText() else if (backup.exists()) backup.readText() else null }.getOrNull()

    override fun save(text: String): Boolean = runCatching {
        val tmp = File(dir, "game.tmp")
        tmp.writeText(text)
        if (tmp.readText() != text) return false
        if (file.exists()) file.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(file)) { file.writeText(text) }
        true
    }.getOrDefault(false)

    override fun clear() {
        runCatching { file.delete(); backup.delete() }
    }
}

interface SettingsRepository {
    val settings: StateFlow<Settings>
    fun update(transform: (Settings) -> Settings)
}

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val _s = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = _s
    override fun update(transform: (Settings) -> Settings) { _s.value = transform(_s.value) }
}

class PrefsSettingsRepository(context: Context) : SettingsRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences("seed_pits_settings", Context.MODE_PRIVATE)
    private val _s = MutableStateFlow(read())
    override val settings: StateFlow<Settings> = _s

    override fun update(transform: (Settings) -> Settings) {
        val next = transform(_s.value)
        if (next == _s.value) return
        _s.value = next
        prefs.edit()
            .putString("language", next.language.name)
            .putBoolean("sound", next.sound)
            .putString("volume", next.volume.name)
            .putBoolean("reducedMotion", next.reducedMotion)
            .putBoolean("showDigits", next.showDigits)
            .putInt("tilt", next.tiltDeg)
            .putBoolean("firstRunDone", next.firstRunDone)
            .putBoolean("coachDone", next.coachDone)
            .putInt("streak", next.streak)
            .putInt("lastLevel", next.lastLevel)
            .apply()
    }

    private fun read(): Settings = Settings(
        language = prefs.getString("language", null)?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: Language.SYSTEM,
        sound = prefs.getBoolean("sound", true),
        volume = prefs.getString("volume", null)?.let { runCatching { Volume.valueOf(it) }.getOrNull() } ?: Volume.HIGH,
        reducedMotion = prefs.getBoolean("reducedMotion", false),
        showDigits = prefs.getBoolean("showDigits", true),
        tiltDeg = prefs.getInt("tilt", 15).let { if (it == 0) 0 else 15 },
        firstRunDone = prefs.getBoolean("firstRunDone", false),
        coachDone = prefs.getBoolean("coachDone", false),
        streak = prefs.getInt("streak", 0),
        lastLevel = prefs.getInt("lastLevel", 1),
    )
}
