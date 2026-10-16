package com.freebuff.gallery.data

import android.net.Uri

/**
 * A single photo or video as stored in MediaStore.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val path: String,
    val relativePath: String,
    val albumId: String,
    val albumName: String,
    val mimeType: String,
    val size: Long,
    val dateTaken: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val isVideo: Boolean
) {
    /** Best available timestamp: capture time, falling back to insertion time. */
    val date: Long
        get() = if (dateTaken > 0) dateTaken else dateAdded * 1000

    val isGif: Boolean
        get() = mimeType.equals("image/gif", true) || name.endsWith(".gif", true)

    val isScreenshot: Boolean
        get() = name.startsWith("screenshot", true) ||
            path.contains("screenshot", true) ||
            relativePath.contains("screenshot", true)

    val displayAlbum: String
        get() = albumName.ifBlank {
            relativePath.trim('/').substringBeforeLast('/').substringAfterLast('/')
                .ifBlank { "Internal storage" }
        }
}

/** Album (folder) with its cover photo. */
data class AlbumItem(
    val id: String,
    val name: String,
    val count: Int,
    val cover: MediaItem
)

/** Which collection of media a screen is showing. */
data class CollectionSpec(
    val source: String,        // photos | album | favorites | hidden | trash | search | single
    val albumId: String = "",
    val query: String = "",
    val filter: String = "all",
    val uri: String = ""
) {
    fun toKey(): String =
        listOf(source, albumId, query, filter, uri).joinToString("\u001F")

    companion object {
        fun fromKey(key: String): CollectionSpec {
            val parts = key.split("\u001F")
            return CollectionSpec(
                source = parts.getOrElse(0) { "" }.ifBlank { "photos" },
                albumId = parts.getOrElse(1) { "" },
                query = parts.getOrElse(2) { "" },
                filter = parts.getOrElse(3) { "all" },
                uri = parts.getOrElse(4) { "" }
            )
        }
    }
}

/** Rows shown by the grid adapter: day headers interleaved with media. */
sealed class Row {
    data class Header(val id: String, val label: String) : Row()
    data class Media(val item: MediaItem) : Row()
}
