package com.freebuff.gallery

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.freebuff.gallery.data.Prefs

/**
 * Applies the user's theme choice (system / light / dark) before any activity is created.
 */
class GalleryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = when (Prefs(this).theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
