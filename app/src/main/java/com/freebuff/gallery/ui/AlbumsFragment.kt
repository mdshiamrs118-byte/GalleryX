package com.freebuff.gallery.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.freebuff.gallery.R
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.data.MediaRepository
import com.freebuff.gallery.data.Prefs
import com.freebuff.gallery.databinding.FragmentMediaGridBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Albums tab: special cards (Favorites, Hidden, Recently deleted) followed by
 * the device's folders.
 */
class AlbumsFragment : Fragment() {

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AlbumAdapter
    private var loadToken = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AlbumAdapter { entry -> open(entry) }
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recycler.adapter = adapter
        binding.recycler.addItemDecoration(
            GridSpacing(resources.getDimensionPixelSize(R.dimen.grid_spacing))
        )
        binding.swipe.setOnRefreshListener {
            MediaRepository.invalidate()
            load()
        }
        binding.swipe.isEnabled = true
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Lets the host force a refresh (sort change, permission grant). */
    fun reload() {
        if (_binding != null) load()
    }

    private fun load() {
        val token = ++loadToken
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { buildEntries() }
            if (token != loadToken || _binding == null) return@launch
            adapter.submitList(entries)
            binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyText.setText(R.string.empty_title)
            binding.swipe.isRefreshing = false
        }
    }

    private fun buildEntries(): List<AlbumEntry> {
        val context = requireContext()
        val prefs = Prefs(context)
        val all = MediaRepository.allMedia(context)
        val hidden = prefs.hidden()
        val favorites = prefs.favorites()
        val trashCount = MediaRepository.trashCount(context)

        val entries = ArrayList<AlbumEntry>()

        entries.add(
            AlbumEntry(
                key = "special:favorites",
                title = getString(R.string.album_favorites),
                count = all.count { it.id in favorites && it.id !in hidden },
                albumId = "",
                cover = null,
                iconRes = R.drawable.ic_favorite,
                backgroundRes = R.drawable.bg_special_favorites
            )
        )
        entries.add(
            AlbumEntry(
                key = "special:hidden",
                title = getString(R.string.album_hidden),
                count = all.count { it.id in hidden },
                albumId = "",
                cover = null,
                iconRes = R.drawable.ic_lock,
                backgroundRes = R.drawable.bg_special_hidden
            )
        )
        entries.add(
            AlbumEntry(
                key = "special:trash",
                title = getString(R.string.album_trash),
                count = trashCount,
                albumId = "",
                cover = null,
                iconRes = R.drawable.ic_restore,
                backgroundRes = R.drawable.bg_special_trash
            )
        )

        MediaRepository.albums(context).forEach { entries.add(AlbumEntry.of(it)) }
        return entries
    }

    private fun open(entry: AlbumEntry) {
        val context = requireContext()
        when {
            entry.key == "special:favorites" -> CollectionActivity.start(
                context, getString(R.string.album_favorites), CollectionSpec("favorites")
            )
            entry.key == "special:hidden" -> {
                if (Prefs(context).isUnlocked()) {
                    CollectionActivity.start(
                        context, getString(R.string.album_hidden), CollectionSpec("hidden")
                    )
                } else {
                    startActivity(Intent(context, PinActivity::class.java))
                }
            }
            entry.key == "special:trash" -> CollectionActivity.start(
                context, getString(R.string.album_trash), CollectionSpec("trash")
            )
            else -> CollectionActivity.start(
                context, entry.title, CollectionSpec("album", albumId = entry.albumId)
            )
        }
    }

    private class GridSpacing(private val space: Int) :
        androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: androidx.recyclerview.widget.RecyclerView,
            state: androidx.recyclerview.widget.RecyclerView.State
        ) {
            outRect.set(space, space, space, space)
        }
    }
}
