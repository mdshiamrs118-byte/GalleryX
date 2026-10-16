package com.freebuff.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Executors

/**
 * Loads and caches video thumbnails (fast provider path first,
 * frame extraction as fallback) off the main thread.
 */
object Thumbs {

    private val cache = object : LruCache<Long, Bitmap>(6 * 1024) { // ~6 MB
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** [callback] is always invoked on the main thread with a bitmap or null. */
    fun video(context: Context, item: MediaItem, callback: (Bitmap?) -> Unit) {
        if (item.id <= 0) {
            main.post { callback(null) }
            return
        }
        cache.get(item.id)?.let {
            main.post { callback(it) }
            return
        }
        val appContext = context.applicationContext
        exec.execute {
            val bitmap = load(appContext, item)
            if (bitmap != null) cache.put(item.id, bitmap)
            main.post { callback(bitmap) }
        }
    }

    private fun load(context: Context, item: MediaItem): Bitmap? {
        // 1) MediaProvider generated thumbnail (creates it on demand, API 29+)
        try {
            context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)?.let { return it }
        } catch (t: Throwable) {
        }
        // 2) Grab a frame ourselves
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, item.uri)
            val frame = retriever.getFrameAtTime(
                1_000_000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            retriever.release()
            downscale(frame)
        } catch (t: Throwable) {
            null
        }
    }

    private fun downscale(source: Bitmap?): Bitmap? {
        if (source == null) return null
        val max = 640f
        val largest = maxOf(source.width, source.height)
        if (largest <= max) return source
        val factor = max / largest
        val out = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true
        )
        if (out != source) source.recycle()
        return out
    }
}
