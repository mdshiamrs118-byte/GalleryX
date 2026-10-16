package com.freebuff.gallery.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.freebuff.gallery.R
import com.freebuff.gallery.data.MediaRepository
import com.freebuff.gallery.databinding.ActivityEditorBinding
import com.freebuff.gallery.util.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimal photo editor: rotate, flip and colour filters; saves a copy to Pictures/GalleryX. */
class EditorActivity : AppCompatActivity() {

    private enum class Filter { NONE, BW, SEPIA, INVERT }

    private lateinit var binding: ActivityEditorBinding
    private lateinit var uri: Uri

    private var base: Bitmap? = null      // full-quality working copy (used when saving)
    private var preview: Bitmap? = null   // small copy for fast on-screen updates
    private var rotation = 0              // quarter turns clockwise
    private var flipped = false
    private var filter = Filter.NONE
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }
        uri = Uri.parse(uriString)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRotate.setOnClickListener {
            rotation = (rotation + 1) % 4
            refresh()
        }
        binding.btnFlip.setOnClickListener {
            flipped = !flipped
            refresh()
        }
        binding.btnSave.setOnClickListener { save() }
        binding.filters.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chip_bw -> Filter.BW
                R.id.chip_sepia -> Filter.SEPIA
                R.id.chip_invert -> Filter.INVERT
                else -> Filter.NONE
            }
            refresh()
        }

        loadBitmap()
    }

    // ---------------------------------------------------------------- loading

    private fun loadBitmap() {
        setBusy(true)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode() }
            setBusy(false)
            if (bitmap == null) {
                toast(this@EditorActivity, getString(R.string.edit_failed))
                finish()
                return@launch
            }
            base = bitmap
            preview = scaleDown(bitmap, PREVIEW_SIZE)
            refresh()
        }
    }

    private fun decode(): Bitmap? = try {
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software allocator: the bitmap must be readable for transforms and filters.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > MAX_EDIT_SIZE) {
                val factor = MAX_EDIT_SIZE.toFloat() / largest
                decoder.setTargetSize(
                    (info.size.width * factor).toInt().coerceAtLeast(1),
                    (info.size.height * factor).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun scaleDown(source: Bitmap, max: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= max) return source
        val factor = max.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true
        )
    }

    // -------------------------------------------------------------- rendering

    private fun refresh() {
        val source = preview ?: return
        binding.preview.setImageBitmap(render(source))
    }

    /** Applies the current flip / rotation / filter to [src] and returns a new (or the same) bitmap. */
    private fun render(src: Bitmap): Bitmap {
        val matrix = Matrix()
        if (flipped) matrix.postScale(-1f, 1f)
        if (rotation != 0) matrix.postRotate(rotation * 90f)
        val oriented = if (matrix.isIdentity) {
            src
        } else {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }
        if (filter == Filter.NONE) return oriented

        val out = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(filterMatrix())
        Canvas(out).drawBitmap(oriented, 0f, 0f, paint)
        return out
    }

    private fun filterMatrix(): ColorMatrix = when (filter) {
        Filter.BW -> ColorMatrix().apply { setSaturation(0f) }
        Filter.SEPIA -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        Filter.INVERT -> ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        Filter.NONE -> ColorMatrix()
    }

    // ----------------------------------------------------------------- saving

    private fun save() {
        val source = base ?: return
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val name = "GalleryX_" +
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                MediaRepository.insertEditedImage(applicationContext, render(source), name)
            }
            setBusy(false)
            if (saved != null) {
                toast(this@EditorActivity, getString(R.string.edit_saved))
                setResult(RESULT_OK)
                finish()
            } else {
                toast(this@EditorActivity, getString(R.string.edit_failed))
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.editorProgress.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnSave.isEnabled = !value
        binding.btnRotate.isEnabled = !value
        binding.btnFlip.isEnabled = !value
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val PREVIEW_SIZE = 1280
        private const val MAX_EDIT_SIZE = 4096

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, EditorActivity::class.java).putExtra(EXTRA_URI, uri.toString())
            )
        }
    }
}
