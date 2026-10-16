package com.freebuff.gallery.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.freebuff.gallery.R
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.data.MediaItem
import com.freebuff.gallery.data.MediaRepository
import com.freebuff.gallery.data.Prefs
import com.freebuff.gallery.databinding.ActivityViewerBinding
import com.freebuff.gallery.util.formatDateTime
import com.freebuff.gallery.util.formatDuration
import com.freebuff.gallery.util.formatSize
import com.freebuff.gallery.util.shareItems
import com.freebuff.gallery.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen photo / video viewer. Opened from a grid (spec + index) or from
 * another app through an ACTION_VIEW intent.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var prefs: Prefs
    private lateinit var spec: CollectionSpec
    private lateinit var adapter: ViewerAdapter

    private var items: List<MediaItem> = emptyList()
    private var startIndex = 0
    private var currentIndex = 0
    private var loadedVersion = -1L
    private var loadToken = 0
    private var chromeVisible = true
    private var slideshowRunning = false
    private var activeVideo: ViewerAdapter.Holder? = null
    private var pendingAfterSender: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private val senderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pendingAfterSender
            pendingAfterSender = null
            if (result.resultCode == RESULT_OK) action?.invoke()
        }

    private val videoTicker = object : Runnable {
        override fun run() {
            val video = activeVideo?.binding?.video ?: return
            try {
                val position = video.currentPosition
                val duration = video.duration
                binding.seekbar.progress = position
                binding.videoTime.text = formatDuration(position.toLong()) + " / " +
                    formatDuration(duration.coerceAtLeast(0).toLong())
            } catch (t: Throwable) {
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    private val slideshowTick = object : Runnable {
        override fun run() {
            if (!slideshowRunning || items.size < 2) return
            val wrap = currentIndex + 1 >= items.size
            binding.pager.setCurrentItem(if (wrap) 0 else currentIndex + 1, !wrap)
            handler.postDelayed(this, prefs.slideshowIntervalMs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        spec = resolveSpec()
        startIndex = savedInstanceState?.getInt(STATE_INDEX)
            ?: intent.getIntExtra(EXTRA_INDEX, 0)

        binding.toolbar.inflateMenu(R.menu.menu_viewer)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { onMenuItem(it) }

        adapter = ViewerAdapter(onTap = ::onPageTapped, onPlay = ::startVideo)
        binding.pager.adapter = adapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                stopVideo()
                updateChrome()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING && slideshowRunning) stopSlideshow()
            }
        })
        setupVideoControls()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (loadedVersion != -1L && loadedVersion != MediaRepository.version) load()
    }

    override fun onStop() {
        stopVideo()
        stopSlideshow()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, currentIndex)
    }

    private fun resolveSpec(): CollectionSpec {
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            return CollectionSpec("single", uri = data.toString())
        }
        return CollectionSpec.fromKey(intent.getStringExtra(EXTRA_SPEC) ?: "")
    }

    private fun current(): MediaItem? = items.getOrNull(currentIndex)

    // ---------------------------------------------------------------- loading

    private fun load() {
        val token = ++loadToken
        val app = applicationContext
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { MediaRepository.load(app, spec) }
            if (token != loadToken) return@launch
            loadedVersion = MediaRepository.version
            if (list.isEmpty()) {
                if (items.isEmpty()) toast(this@ViewerActivity, getString(R.string.unsupported_media))
                finish()
                return@launch
            }
            val keepId = if (items.isEmpty()) null else items.getOrNull(currentIndex)?.id
            items = list
            adapter.submit(list)
            val target = if (keepId != null) {
                val found = list.indexOfFirst { it.id == keepId }
                if (found >= 0) found else currentIndex.coerceAtMost(list.lastIndex)
            } else {
                startIndex.coerceIn(0, list.lastIndex)
            }
            binding.pager.setCurrentItem(target, false)
            currentIndex = target
            updateChrome()
        }
    }

    // ----------------------------------------------------------------- chrome

    private fun updateChrome() {
        val item = current() ?: return
        binding.toolbar.title = item.name
        binding.toolbar.subtitle = if (items.size > 1) "${currentIndex + 1} / ${items.size}" else null

        val menu = binding.toolbar.menu
        val trash = spec.source == "trash"
        val owned = item.id > 0
        val favorite = owned && prefs.isFavorite(item.id)

        menu.findItem(R.id.action_favorite)?.apply {
            isVisible = owned && !trash
            setIcon(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            setTitle(if (favorite) R.string.unfavorite else R.string.favorite)
        }
        menu.findItem(R.id.action_edit)?.isVisible = !item.isVideo && !trash
        menu.findItem(R.id.action_delete)?.isVisible = owned
        menu.findItem(R.id.action_wallpaper)?.isVisible = !item.isVideo
        menu.findItem(R.id.action_slideshow)?.isVisible = items.size > 1
        menu.findItem(R.id.action_rename)?.isVisible = owned && !trash
        menu.findItem(R.id.action_hide)?.apply {
            isVisible = owned && !trash
            setTitle(if (spec.source == "hidden") R.string.show else R.string.hide)
        }
    }

    private fun onPageTapped() {
        if (slideshowRunning) {
            stopSlideshow()
            setChrome(true)
        } else {
            setChrome(!chromeVisible)
        }
    }

    private fun setChrome(show: Boolean) {
        chromeVisible = show
        binding.toolbar.visibility = if (show) View.VISIBLE else View.GONE
        updateVideoControlsVisibility()
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (show) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------- menu

    private fun onMenuItem(menuItem: MenuItem): Boolean {
        val item = current() ?: return false
        when (menuItem.itemId) {
            R.id.action_share -> shareItems(this, listOf(item))
            R.id.action_favorite -> {
                prefs.setFavorite(item.id, !prefs.isFavorite(item.id))
                updateChrome()
            }
            R.id.action_edit -> {
                if (item.isGif) toast(this, getString(R.string.edit_unsupported))
                else EditorActivity.start(this, item.uri)
            }
            R.id.action_delete -> deleteCurrent(item)
            R.id.action_details -> showDetails(item)
            R.id.action_wallpaper -> setWallpaper(item)
            R.id.action_slideshow -> startSlideshow()
            R.id.action_rename -> renameCurrent(item)
            R.id.action_hide -> {
                prefs.setHidden(item.id, spec.source != "hidden")
                MediaRepository.invalidate()
                load()
            }
            else -> return false
        }
        return true
    }

    // ----------------------------------------------------------------- delete

    private fun launchSender(pending: android.app.PendingIntent, after: () -> Unit) {
        pendingAfterSender = after
        try {
            senderLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        } catch (t: Throwable) {
            pendingAfterSender = null
            toast(this, getString(R.string.delete_failed))
        }
    }

    private fun deleteCurrent(item: MediaItem) {
        if (item.id <= 0) return
        val permanent = spec.source == "trash"
        if (Build.VERSION.SDK_INT >= 30) {
            val pending = if (permanent) MediaRepository.deleteRequest(this, listOf(item))
            else MediaRepository.trashRequest(this, listOf(item))
            if (pending != null) {
                launchSender(pending) {
                    MediaRepository.invalidate()
                    if (!permanent) toast(this, getString(R.string.moved_to_trash))
                    load()
                }
                return
            }
        }
        // Android 10: permanent delete after confirmation.
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title_android10)
            .setMessage(R.string.delete_confirm_message_android10)
            .setPositiveButton(R.string.delete) { _, _ ->
                val count = MediaRepository.deleteDirect(this, listOf(item))
                toast(
                    this,
                    if (count > 0) getString(R.string.deleted_items, count)
                    else getString(R.string.delete_failed)
                )
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------- rename

    private fun renameCurrent(item: MediaItem) {
        val input = EditText(this).apply {
            setText(item.name.substringBeforeLast('.', item.name))
            setSelectAllOnFocus(true)
            setSingleLine()
            hint = getString(R.string.rename_hint)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                doRename(item, input.text.toString().trim(), retried = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doRename(item: MediaItem, base: String, retried: Boolean) {
        if (base.isEmpty()) return
        val ext = item.name.substringAfterLast('.', "")
        val newName = if (ext.isNotEmpty() && !base.endsWith(".$ext", true)) "$base.$ext" else base
        if (newName == item.name) return
        when (MediaRepository.rename(this, item, newName)) {
            MediaRepository.RenameResult.Success -> {
                MediaRepository.invalidate()
                toast(this, getString(R.string.rename_done))
                load()
            }
            MediaRepository.RenameResult.NeedsConsent -> {
                val pending = if (retried) null else MediaRepository.writeRequest(this, item)
                if (pending != null) {
                    launchSender(pending) { doRename(item, base, retried = true) }
                } else {
                    toast(this, getString(R.string.rename_failed))
                }
            }
            MediaRepository.RenameResult.Failed -> toast(this, getString(R.string.rename_failed))
        }
    }

    // -------------------------------------------------------------- wallpaper

    private fun setWallpaper(item: MediaItem) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(item.uri)?.use { stream ->
                        WallpaperManager.getInstance(applicationContext).setStream(stream)
                        true
                    } ?: false
                } catch (t: Throwable) {
                    false
                }
            }
            toast(
                this@ViewerActivity,
                getString(if (ok) R.string.wallpaper_done else R.string.wallpaper_failed)
            )
        }
    }

    // ---------------------------------------------------------------- details

    private fun showDetails(item: MediaItem) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { buildDetails(item) }
            val builder = MaterialAlertDialogBuilder(this@ViewerActivity)
                .setTitle(R.string.details)
                .setMessage(info.text)
                .setPositiveButton(R.string.close, null)
            val path = item.path.ifBlank { item.relativePath }
            if (path.isNotBlank()) {
                builder.setNeutralButton(R.string.copy_path) { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("path", path))
                    toast(this@ViewerActivity, getString(R.string.path_copied))
                }
            }
            val geo = info.geo
            if (geo != null) {
                builder.setNegativeButton(R.string.open_maps) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$geo?q=$geo")))
                    } catch (e: ActivityNotFoundException) {
                        // no maps app installed
                    }
                }
            }
            builder.show()
        }
    }

    private class Details(val text: String, val geo: String?)

    private fun buildDetails(item: MediaItem): Details {
        val lines = ArrayList<String>()
        fun add(label: Int, value: String) {
            lines.add(getString(label) + ": " + value)
        }
        add(R.string.info_name, item.name)
        add(R.string.info_album, item.displayAlbum)
        add(R.string.info_type, item.mimeType)
        add(R.string.info_size, formatSize(item.size))
        if (item.width > 0 && item.height > 0) {
            add(R.string.info_dimensions, "${item.width} × ${item.height}")
        }
        if (item.isVideo && item.duration > 0) {
            add(R.string.info_duration, formatDuration(item.duration))
        }
        add(R.string.info_date, formatDateTime(item.date))
        val path = item.path.ifBlank { item.relativePath }
        if (path.isNotBlank()) add(R.string.info_path, path)

        var geo: String? = null
        if (!item.isVideo) {
            try {
                contentResolver.openInputStream(item.uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty().trim()
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty().trim()
                    val camera = if (model.startsWith(make, true)) model else "$make $model".trim()
                    if (camera.isNotEmpty()) add(R.string.info_camera, camera)
                    val latLong = exif.latLong
                    if (latLong != null) {
                        geo = "${latLong[0]},${latLong[1]}"
                        add(R.string.info_location, geo!!)
                    }
                }
            } catch (t: Throwable) {
                // no EXIF available
            }
        }
        return Details(lines.joinToString("\n"), geo)
    }

    // ------------------------------------------------------------------ video

    private fun setupVideoControls() {
        binding.btnPlayPause.setOnClickListener {
            val holder = activeVideo ?: return@setOnClickListener
            val video = holder.binding.video
            if (video.isPlaying) {
                video.pause()
                updatePlayIcon(false)
            } else {
                video.start()
                holder.binding.btnPlayBig.visibility = View.GONE
                updatePlayIcon(true)
                handler.removeCallbacks(videoTicker)
                handler.post(videoTicker)
            }
        }
        binding.seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) activeVideo?.binding?.video?.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun startVideo(holder: ViewerAdapter.Holder) {
        stopVideo()
        val item = holder.item ?: return
        activeVideo = holder
        val b = holder.binding
        b.btnPlayBig.visibility = View.GONE
        b.videoError.visibility = View.GONE
        b.itemProgress.visibility = View.VISIBLE
        b.video.visibility = View.VISIBLE
        b.video.setOnPreparedListener { player ->
            b.itemProgress.visibility = View.GONE
            b.image.visibility = View.GONE
            binding.seekbar.max = player.duration
            player.start()
            updatePlayIcon(true)
            handler.removeCallbacks(videoTicker)
            handler.post(videoTicker)
        }
        b.video.setOnErrorListener { _, _, _ ->
            b.itemProgress.visibility = View.GONE
            b.video.visibility = View.GONE
            b.image.visibility = View.VISIBLE
            b.videoError.visibility = View.VISIBLE
            true
        }
        b.video.setOnCompletionListener {
            updatePlayIcon(false)
            b.btnPlayBig.visibility = View.VISIBLE
        }
        b.video.setVideoURI(item.uri)
        updateVideoControlsVisibility()
    }

    private fun stopVideo() {
        handler.removeCallbacks(videoTicker)
        activeVideo?.let { holder ->
            val b = holder.binding
            try {
                b.video.stopPlayback()
            } catch (t: Throwable) {
                // already stopped
            }
            b.video.visibility = View.GONE
            b.image.visibility = View.VISIBLE
            b.itemProgress.visibility = View.GONE
            b.videoError.visibility = View.GONE
            if (holder.item?.isVideo == true) b.btnPlayBig.visibility = View.VISIBLE
        }
        activeVideo = null
        updatePlayIcon(false)
        binding.seekbar.progress = 0
        binding.videoTime.text = ""
        updateVideoControlsVisibility()
    }

    private fun updatePlayIcon(playing: Boolean) {
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlayPause.contentDescription =
            getString(if (playing) R.string.pause else R.string.play)
    }

    private fun updateVideoControlsVisibility() {
        binding.videoControls.visibility =
            if (chromeVisible && activeVideo != null) View.VISIBLE else View.GONE
    }

    // --------------------------------------------------------------- slideshow

    private fun startSlideshow() {
        if (items.size < 2) return
        slideshowRunning = true
        setChrome(false)
        if (prefs.keepScreenOn()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(slideshowTick)
        handler.postDelayed(slideshowTick, prefs.slideshowIntervalMs())
    }

    private fun stopSlideshow() {
        slideshowRunning = false
        handler.removeCallbacks(slideshowTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_SPEC = "extra_spec"
        const val EXTRA_INDEX = "extra_index"
        private const val STATE_INDEX = "state_index"
    }
}
