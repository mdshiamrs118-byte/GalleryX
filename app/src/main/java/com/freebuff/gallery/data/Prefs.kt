package com.freebuff.gallery.data

import android.content.Context
import androidx.preference.PreferenceManager
import java.security.MessageDigest

/**
 * Single access point to the app's shared preferences:
 * settings (theme, grid columns, sort order, slideshow) and library state
 * (favorites, hidden items, PIN).
 *
 * Uses the default shared preferences file so that the settings screen
 * (androidx preference) and this class read/write the same values.
 */
class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ---------------- settings ----------------

    val theme: String
        get() = sp.getString(KEY_THEME, "system") ?: "system"

    fun columns(): Int =
        (sp.getString(KEY_COLUMNS, "3") ?: "3").toIntOrNull()?.coerceIn(1, 6) ?: 3

    fun setColumns(value: Int) = sp.edit().putString(KEY_COLUMNS, value.toString()).apply()

    fun sort(): String = sp.getString(KEY_SORT, "newest") ?: "newest"

    fun setSort(key: String) {
        sp.edit().putString(KEY_SORT, key).apply()
    }

    fun slideshowIntervalMs(): Long =
        ((sp.getString(KEY_SLIDESHOW, "3") ?: "3").toIntOrNull() ?: 3) * 1000L

    fun keepScreenOn(): Boolean = sp.getBoolean(KEY_KEEP_SCREEN, true)

    // ---------------- favorites ----------------

    fun favorites(): Set<Long> = sp.getStringSet(KEY_FAVORITES, emptySet())
        .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun isFavorite(id: Long): Boolean = sp.getStringSet(KEY_FAVORITES, emptySet())!!.contains(id.toString())

    fun setFavorite(id: Long, on: Boolean) = mutateSet(KEY_FAVORITES, id, on)

    // ---------------- hidden album ----------------

    fun hidden(): Set<Long> = sp.getStringSet(KEY_HIDDEN, emptySet())
        .orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun isHidden(id: Long): Boolean = sp.getStringSet(KEY_HIDDEN, emptySet())!!.contains(id.toString())

    fun setHidden(id: Long, on: Boolean) = mutateSet(KEY_HIDDEN, id, on)

    private fun mutateSet(key: String, id: Long, on: Boolean) {
        val current = sp.getStringSet(key, emptySet())!!.toMutableSet()
        val value = id.toString()
        if (on) current.add(value) else current.remove(value)
        sp.edit().putStringSet(key, current).apply()
    }

    // ---------------- PIN ----------------

    fun hasPin(): Boolean = sp.getString(KEY_PIN, null) != null

    fun setPin(pin: String) {
        sp.edit().putString(KEY_PIN, hash(pin)).apply()
        unlock()
    }

    fun clearPin() {
        sp.edit().remove(KEY_PIN).apply()
        unlock()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = sp.getString(KEY_PIN, null) ?: return false
        return stored == hash(pin)
    }

    // Session state is deliberately kept in memory only: another app launching
    // our exported activities starts a fresh process, so it can never inherit
    // an unlocked session.
    fun unlock(minutes: Int = 15) {
        unlockedUntil = System.currentTimeMillis() + minutes * 60_000L
    }

    fun lock() {
        unlockedUntil = 0L
    }

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntil

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(("galleryx::$pin").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile
        private var unlockedUntil = 0L

        private const val KEY_THEME = "pref_theme"
        private const val KEY_COLUMNS = "pref_columns"
        private const val KEY_SORT = "pref_sort"
        private const val KEY_SLIDESHOW = "pref_slideshow"
        private const val KEY_KEEP_SCREEN = "pref_keep_screen"
        private const val KEY_FAVORITES = "library_favorites"
        private const val KEY_HIDDEN = "library_hidden"
        private const val KEY_PIN = "library_pin"
    }
}
