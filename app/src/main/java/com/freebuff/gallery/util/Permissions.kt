package com.freebuff.gallery.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime media permission handling for the Android 10 / 11-12 / 13+ models.
 * On Android 14+ the user may grant access to only selected photos and videos.
 */
object Permissions {

    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT == 29 -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Can the app read media at all (including "selected photos only")? */
    fun hasMediaAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 10 needs WRITE_EXTERNAL_STORAGE to delete media owned by other apps. */
    fun canDeleteDirectly(context: Context): Boolean =
        Build.VERSION.SDK_INT != 29 || granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}
