package com.freebuff.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.net.toUri

/**
 * All MediaStore access lives here.
 *
 * Column names are passed as string literals so the code is independent of
 * compile-time constants that moved between API levels; every read is done
 * defensively with getColumnIndex.
 */
object MediaRepository {

    private val PROJECTION = arrayOf(
        "_id",
        "display_name",
        "_data",
        "mime_type",
        "size",
        "datetaken",
        "date_added",
        "width",
        "height",
        "duration",
        "bucket_id",
        "bucket_display_name",
        "relative_path"
    )

    @Volatile
    private var cache: List<MediaItem>? = null

    /** Bumped on every mutation so screens can refresh when they resume. */
    @Volatile
    var version: Long = 0
        private set

    /** Call after any mutation (delete, restore, edit, external change). */
    fun invalidate() {
        cache = null
        version++
    }

    // ------------------------------------------------------------------ query

    fun allMedia(context: Context): List<MediaItem> {
        cache?.let { return it }
        val list = query(context, trashed = false)
        cache = list
        return list
    }

    /** Loads one collection, applying hidden/favorite/type/search filters and sorting. */
    fun load(context: Context, spec: CollectionSpec): List<MediaItem> {
        val prefs = Prefs(context)
        val base: List<MediaItem> = when (spec.source) {
            // Real trash only exists on Android 11+.
            "trash" -> if (Build.VERSION.SDK_INT >= 30) query(context, trashed = true) else emptyList()
            "single" -> queryByUri(context, spec.uri)
            else -> allMedia(context)
        }
        val hidden = prefs.hidden()
        val favorites = prefs.favorites()

        var list = when (spec.source) {
            "hidden" -> base.filter { it.id in hidden }
            "favorites" -> base.filter { it.id in favorites && it.id !in hidden }
            else -> base.filter { it.id !in hidden }
        }
        when (spec.source) {
            "album" -> list = list.filter { it.albumId == spec.albumId }
            "search" -> {
                val q = spec.query.trim()
                if (q.isNotEmpty()) {
                    list = list.filter {
                        it.name.contains(q, true) ||
                            it.albumName.contains(q, true) ||
                            it.relativePath.contains(q, true)
                    }
                }
                list = when (spec.filter) {
                    "photos" -> list.filter { !it.isVideo }
                    "videos" -> list.filter { it.isVideo }
                    "gifs" -> list.filter { it.isGif }
                    "screenshots" -> list.filter { it.isScreenshot }
                    else -> list
                }
            }
        }
        return sort(list, prefs.sort())
    }

    fun sort(list: List<MediaItem>, key: String): List<MediaItem> = when (key) {
        "oldest" -> list.sortedBy { it.date }
        "name" -> list.sortedWith(compareBy({ it.albumName.lowercase() }, { it.name.lowercase() }))
        "size" -> list.sortedByDescending { it.size }
        else -> list.sortedByDescending { it.date }
    }

    /** Albums (buckets) with the newest item as cover. Hidden items are excluded. */
    fun albums(context: Context): List<AlbumItem> {
        val hidden = Prefs(context).hidden()
        return allMedia(context)
            .asSequence()
            .filter { it.id !in hidden }
            .groupBy { it.albumId to it.displayAlbum }
            .map { (key, items) ->
                AlbumItem(
                    id = key.first,
                    name = key.second,
                    count = items.size,
                    cover = items.maxByOrNull { it.date } ?: items.first()
                )
            }
            .sortedByDescending { it.cover.date }
            .toList()
    }

    fun trashCount(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 30) query(context, trashed = true).size else 0

    // ------------------------------------------------------------- primitives

    private fun query(context: Context, trashed: Boolean): List<MediaItem> {
        val resolver = context.contentResolver
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "(mime_type LIKE 'image/%' OR mime_type LIKE 'video/%')"
        val cursor = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putInt(
                        MediaStore.QUERY_ARG_MATCH_TRASHED,
                        if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
                    )
                }
                resolver.query(uri, PROJECTION, args, null)
            } else {
                resolver.query(uri, PROJECTION, selection, null, null)
            }
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        return cursor.use { c ->
            val items = ArrayList<MediaItem>(c.count)
            while (c.moveToNext()) {
                read(c)?.let { items.add(it) }
            }
            items
        }
    }

    private fun queryByUri(context: Context, uriString: String): List<MediaItem> {
        val uri = uriString.toUriOrNull() ?: return emptyList()
        val cursor = try {
            context.contentResolver.query(uri, PROJECTION, null, null, null)
        } catch (t: Throwable) {
            null
        }
        if (cursor != null) {
            val items = cursor.use { c ->
                val list = ArrayList<MediaItem>(c.count)
                while (c.moveToNext()) read(c)?.let { list.add(it) }
                list
            }
            if (items.isNotEmpty()) return items
        }
        // Not a MediaStore row (e.g. a DocumentsProvider): build a minimal item.
        return listOfNotNull(basicItem(context, uri))
    }

    /** Minimal MediaItem for foreign content uris (open from another app). */
    private fun basicItem(context: Context, uri: Uri): MediaItem? {
        val resolver = context.contentResolver
        var name = "Unknown"
        var size = 0L
        try {
            resolver.query(uri, arrayOf("_display_name", "_size"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex("_display_name")
                    val si = c.getColumnIndex("_size")
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (t: Throwable) {
        }
        val mime = try {
            resolver.getType(uri) ?: "application/octet-stream"
        } catch (t: Throwable) {
            "application/octet-stream"
        }
        return MediaItem(
            id = -1L,
            uri = uri,
            name = name,
            path = "",
            relativePath = "",
            albumId = "external",
            albumName = "Shared with GalleryX",
            mimeType = mime,
            size = size,
            dateTaken = 0L,
            dateAdded = 0L,
            width = 0,
            height = 0,
            duration = 0L,
            isVideo = mime.startsWith("video")
        )
    }

    private fun read(c: android.database.Cursor): MediaItem? {
        fun str(name: String): String {
            val i = c.getColumnIndex(name)
            return if (i >= 0 && !c.isNull(i)) c.getString(i) ?: "" else ""
        }
        fun num(name: String): Long {
            val i = c.getColumnIndex(name)
            return if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
        }
        val id = num("_id")
        if (id <= 0) return null
        val mime = str("mime_type")
        val name = str("display_name")
        val relative = str("relative_path").trim('/')
        val data = str("_data")
        val isVideo = mime.startsWith("video")
        val bucket = str("bucket_id").ifBlank { relative.hashCode().toString() }
        val bucketName = str("bucket_display_name").ifBlank {
            if (relative.isNotEmpty()) relative.substringAfterLast('/') else "Internal storage"
        }
        return MediaItem(
            id = id,
            uri = uriFor(id, isVideo),
            name = name,
            path = data,
            relativePath = if (relative.isEmpty()) "" else "$relative/",
            albumId = bucket,
            albumName = bucketName,
            mimeType = mime,
            size = num("size"),
            dateTaken = num("datetaken"),
            dateAdded = num("date_added"),
            width = num("width").toInt(),
            height = num("height").toInt(),
            duration = num("duration"),
            isVideo = isVideo
        )
    }

    private fun uriFor(id: Long, isVideo: Boolean): Uri {
        val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return ContentUris.withAppendedId(base, id)
    }

    private fun String.toUriOrNull(): Uri? = try {
        toUri()
    } catch (t: Throwable) {
        null
    }

    // -------------------------------------------------------------- mutations

    /** Sender for the system "move to trash" confirmation (Android 11+). */
    fun trashRequest(context: Context, items: List<MediaItem>): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT < 30 || items.isEmpty()) return null
        return try {
            MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true)
        } catch (t: Throwable) {
            null
        }
    }

    /** Sender for the system "restore from trash" confirmation (Android 11+). */
    fun restoreRequest(context: Context, items: List<MediaItem>): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT < 30 || items.isEmpty()) return null
        return try {
            MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, false)
        } catch (t: Throwable) {
            null
        }
    }

    /** Sender for the system "delete permanently" confirmation (Android 11+). */
    fun deleteRequest(context: Context, items: List<MediaItem>): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT < 30 || items.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
        } catch (t: Throwable) {
            null
        }
    }

    /** Sender letting the user grant write access for a rename (Android 11+). */
    fun writeRequest(context: Context, item: MediaItem): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            MediaStore.createWriteRequest(context.contentResolver, listOf(item.uri))
        } catch (t: Throwable) {
            null
        }
    }

    /** Direct delete for Android 10 (WRITE_EXTERNAL_STORAGE). Returns count deleted. */
    fun deleteDirect(context: Context, items: List<MediaItem>): Int {
        var deleted = 0
        for (item in items) {
            if (item.id <= 0) continue
            try {
                deleted += context.contentResolver.delete(item.uri, null, null)
            } catch (t: Throwable) {
                // SecurityException: item not writable by this app
            }
        }
        invalidate()
        return deleted
    }

    sealed class RenameResult {
        object Success : RenameResult()
        object NeedsConsent : RenameResult()
        object Failed : RenameResult()
    }

    fun rename(context: Context, item: MediaItem, displayName: String): RenameResult {
        val values = ContentValues().apply { put("display_name", displayName) }
        return try {
            val updated = context.contentResolver.update(item.uri, values, null, null)
            if (updated > 0) RenameResult.Success else RenameResult.Failed
        } catch (e: SecurityException) {
            RenameResult.NeedsConsent
        } catch (t: Throwable) {
            RenameResult.Failed
        }
    }

    /**
     * Saves an edited bitmap as a new file in Pictures/GalleryX.
     * Returns the new content Uri, or null on failure.
     */
    fun insertEditedImage(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put("display_name", displayName)
            put("mime_type", "image/jpeg")
            put("relative_path", "${android.os.Environment.DIRECTORY_PICTURES}/GalleryX")
            put("date_taken", System.currentTimeMillis())
            put("is_pending", 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            null
        } ?: return null

        return try {
            val out = resolver.openOutputStream(uri) ?: return null
            out.use { s -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, s) }
            val done = ContentValues().apply { put("is_pending", 0) }
            resolver.update(uri, done, null, null)
            invalidate()
            uri
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (t: Throwable) {
                // best-effort cleanup
            }
            null
        }
    }
}
