package com.freebuff.gallery.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.freebuff.gallery.data.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small formatting / UI helpers shared across screens. */

fun toast(context: Context, text: String) {
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
private val yearFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

fun formatDate(millis: Long): String {
    if (millis <= 0) return "—"
    return dateFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())
}

fun formatDateTime(millis: Long): String {
    if (millis <= 0) return "—"
    return dateTimeFormat.format(
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
    )
}

/** Grid section header: "Today", "Yesterday", "Monday, 3 June", ... */
fun dayHeaderLabel(millis: Long): String {
    val today = LocalDate.now()
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) dayFormat.format(date) else yearFormat.format(date)
    }
}

/** "3:07", "1:02:09" */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

/** Shares one or more media items with the system share sheet. */
fun shareItems(activity: Activity, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val allImages = items.all { !it.isVideo }
    val allVideos = items.all { it.isVideo }
    val mimeType = when {
        allImages -> "image/*"
        allVideos -> "video/*"
        else -> "*/*"
    }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uris[0])
            clipData = ClipData.newRawUri("media", uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri("media", uris[0]).apply {
                for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
            }
        }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    activity.startActivity(
        Intent.createChooser(send, activity.getString(com.freebuff.gallery.R.string.share_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}
