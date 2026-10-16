package com.freebuff.gallery.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.freebuff.gallery.R
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.data.MediaItem
import com.freebuff.gallery.data.MediaRepository
import com.freebuff.gallery.data.Prefs
import com.freebuff.gallery.data.Row
import com.freebuff.gallery.databinding.FragmentMediaGridBinding
import com.freebuff.gallery.util.Permissions
import com.freebuff.gallery.util.dayHeaderLabel
import com.freebuff.gallery.util.shareItems
import com.freebuff.gallery.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable media grid: photo timeline, album contents, favorites, hidden,
 * trash and search results all flow through this fragment via [CollectionSpec].
 */
class MediaGridFragment : Fragment() {

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var spec: CollectionSpec
    private lateinit var adapter: MediaAdapter
    private lateinit var layoutManager: GridLayoutManager

    private var mediaList: List<MediaItem> = emptyList()
    private val selected = LinkedHashSet<Long>()
    private var selectionMode = false
    private var actionMode: ActionMode? = null
    private var loadToken = 0

    private val queryRunnable = Runnable { load() }

    private val trashLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            refreshSelectionAfterMutation()
            load()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spec = CollectionSpec.fromKey(arguments?.getString(ARG_SPEC) ?: "")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()

        adapter = MediaAdapter(
            onItemClicked = ::onItemClicked,
            onItemLongClicked = ::onItemLongClicked
        )

        layoutManager = GridLayoutManager(context, Prefs(context).columns())
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.currentList.getOrNull(position) is Row.Header) layoutManager.spanCount
                else 1
        }

        binding.recycler.layoutManager = layoutManager
        binding.recycler.adapter = adapter
        binding.recycler.addItemDecoration(GridSpacing(resources.getDimensionPixelSize(R.dimen.grid_spacing)))
        binding.recycler.onPinchScale = { factor ->
            val current = Prefs(context).columns()
            val next = when {
                factor > 1.3f -> current - 1
                factor < 0.77f -> current + 1
                else -> current
            }.coerceIn(1, 6)
            if (next != current) {
                Prefs(context).setColumns(next)
                layoutManager.spanCount = next
            }
        }

        binding.swipe.setOnRefreshListener {
            MediaRepository.invalidate()
            load()
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) load()
    }

    override fun onDestroyView() {
        binding.recycler.removeCallbacks(queryRunnable)
        actionMode?.finish()
        actionMode = null
        selectionMode = false
        _binding = null
        super.onDestroyView()
    }

    /** Lets the host refresh after a permission change. */
    fun reload() {
        MediaRepository.invalidate()
        if (_binding != null) load()
    }

    /** Used by SearchActivity as the query or type filter changes. */
    fun updateQuery(query: String, filter: String) {
        spec = spec.copy(query = query, filter = filter)
        arguments?.putString(ARG_SPEC, spec.toKey())
        exitSelection()
        if (_binding != null) {
            binding.recycler.removeCallbacks(queryRunnable)
            binding.recycler.postDelayed(queryRunnable, 250)
        }
    }

    // ---------------------------------------------------------------- loading

    private fun load() {
        val token = ++loadToken
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                MediaRepository.load(context, spec)
            }
            if (token != loadToken || _binding == null) return@launch
            mediaList = items

            // Drop ids that no longer exist after a reload.
            val valid = items.map { it.id }.toSet()
            selected.retainAll(valid)
            if (selectionMode && selected.isEmpty()) exitSelection()

            adapter.selectionMode = selectionMode
            adapter.selectedIds = selected.toSet()
            adapter.submitList(buildRows(items))

            val empty = items.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.emptyText.setText(emptyMessage())
            binding.swipe.isRefreshing = false
            if (selectionMode) updateActionModeTitle()
        }
    }

    private fun buildRows(items: List<MediaItem>): List<Row> {
        val sort = Prefs(requireContext()).sort()
        if (sort == "name" || sort == "size") return items.map { Row.Media(it) }

        val rows = ArrayList<Row>(items.size + 16)
        var lastKey = ""
        for (item in items) {
            val key = Instant.ofEpochMilli(item.date)
                .atZone(ZoneId.systemDefault()).toLocalDate().toString()
            if (key != lastKey) {
                rows.add(Row.Header(key, dayHeaderLabel(item.date)))
                lastKey = key
            }
            rows.add(Row.Media(item))
        }
        return rows
    }

    private fun emptyMessage(): Int = when {
        !Permissions.hasMediaAccess(requireContext()) -> R.string.permission_message
        spec.source == "favorites" -> R.string.favorites_empty
        spec.source == "hidden" -> R.string.hidden_empty
        spec.source == "trash" ->
            if (Build.VERSION.SDK_INT >= 30) R.string.trash_empty else R.string.trash_unavailable
        spec.source == "search" -> R.string.no_results
        mediaList.isEmpty() && spec.source == "photos" -> R.string.empty_title
        else -> R.string.no_results
    }

    // ------------------------------------------------------------- item clicks

    private fun onItemClicked(item: MediaItem) {
        if (selectionMode) {
            if (selected.remove(item.id)) {
                if (selected.isEmpty()) {
                    exitSelection()
                    return
                }
            } else {
                selected.add(item.id)
            }
            refreshSelectionUi()
            return
        }
        val index = mediaList.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val intent = Intent(requireContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_SPEC, spec.toKey())
            .putExtra(ViewerActivity.EXTRA_INDEX, index)
        startActivity(intent)
    }

    private fun onItemLongClicked(item: MediaItem) {
        if (!selectionMode) {
            selectionMode = true
            selected.clear()
        }
        selected.add(item.id)
        refreshSelectionUi()
    }

    // -------------------------------------------------------------- selection

    private fun refreshSelectionUi() {
        if (_binding == null) return
        adapter.selectionMode = selectionMode
        adapter.selectedIds = selected.toSet()
        adapter.notifyDataSetChanged()
        if (selectionMode) {
            if (actionMode == null) {
                actionMode = (requireActivity() as AppCompatActivity)
                    .startSupportActionMode(actionModeCallback)
            }
            updateActionModeTitle()
        }
    }

    private fun updateActionModeTitle() {
        actionMode?.title = getString(R.string.selected_count, selected.size)
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        if (_binding == null) {
            actionMode = null
            return
        }
        adapter.selectionMode = false
        adapter.selectedIds = emptySet()
        adapter.notifyDataSetChanged()
        actionMode?.finish()
        actionMode = null
    }

    private fun selectedItems(): List<MediaItem> =
        mediaList.filter { selected.contains(it.id) }

    private fun refreshSelectionAfterMutation() {
        selected.clear()
        selectionMode = false
        actionMode?.finish()
        actionMode = null
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_mode, menu)
            configureMenu(menu)
            updateActionModeTitle()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
            configureMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: android.view.MenuItem): Boolean {
            when (item.itemId) {
                R.id.action_select_all -> {
                    selected.addAll(mediaList.map { it.id })
                    refreshSelectionUi()
                    return true
                }
                R.id.action_share -> {
                    shareItems(requireActivity(), selectedItems())
                    return true
                }
                R.id.action_favorite -> {
                    toggleFavorite()
                    return true
                }
                R.id.action_delete -> {
                    deleteSelected()
                    return true
                }
                R.id.action_hide -> {
                    setHiddenForSelection()
                    return true
                }
                R.id.action_restore -> {
                    restoreSelected()
                    return true
                }
                R.id.action_delete_forever -> {
                    deleteForeverSelected()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionMode = false
            selected.clear()
            adapter.selectionMode = false
            adapter.selectedIds = emptySet()
            adapter.notifyDataSetChanged()
            actionMode = null
        }
    }

    private fun configureMenu(menu: android.view.Menu) {
        val isTrash = spec.source == "trash"
        val isHidden = spec.source == "hidden"
        menu.findItem(R.id.action_restore)?.isVisible = isTrash
        menu.findItem(R.id.action_delete_forever)?.isVisible = isTrash
        menu.findItem(R.id.action_delete)?.isVisible = !isTrash
        menu.findItem(R.id.action_favorite)?.isVisible = !isTrash
        val hideItem = menu.findItem(R.id.action_hide)
        hideItem?.isVisible = !isTrash
        hideItem?.setTitle(if (isHidden) R.string.show else R.string.hide)

        if (!isTrash && selected.isNotEmpty()) {
            val prefs = Prefs(requireContext())
            val items = selectedItems()
            if (items.isNotEmpty() && items.all { prefs.isFavorite(it.id) }) {
                menu.findItem(R.id.action_favorite)?.setTitle(R.string.unfavorite)
            } else {
                menu.findItem(R.id.action_favorite)?.setTitle(R.string.favorite)
            }
        }
    }

    // --------------------------------------------------------------- actions

    private fun toggleFavorite() {
        val prefs = Prefs(requireContext())
        val items = selectedItems()
        if (items.isEmpty()) return
        val remove = items.all { prefs.isFavorite(it.id) }
        items.forEach { prefs.setFavorite(it.id, !remove) }
        actionMode?.invalidate()
    }

    private fun setHiddenForSelection() {
        val prefs = Prefs(requireContext())
        val hide = spec.source != "hidden"
        selectedItems().forEach { prefs.setHidden(it.id, hide) }
        load()
    }

    private fun deleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pending = MediaRepository.trashRequest(requireContext(), items)
            if (pending != null) {
                launchSender(pending.intentSender)
                return
            }
        }
        // Android 10 path: permanent delete after confirmation.
        val title = if (Build.VERSION.SDK_INT >= 30) R.string.delete_confirm_title
        else R.string.delete_confirm_title_android10
        val message = if (Build.VERSION.SDK_INT >= 30) R.string.delete_confirm_message
        else R.string.delete_confirm_message_android10
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                val count = MediaRepository.deleteDirect(requireContext(), items)
                MediaRepository.invalidate()
                toast(
                    requireContext(),
                    if (count > 0) getString(R.string.deleted_items, count)
                    else getString(R.string.delete_failed)
                )
                refreshSelectionAfterMutation()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteForeverSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pending = MediaRepository.deleteRequest(requireContext(), items)
            if (pending != null) {
                launchSender(pending.intentSender)
                return
            }
        }
        val count = MediaRepository.deleteDirect(requireContext(), items)
        MediaRepository.invalidate()
        toast(
            requireContext(),
            if (count > 0) getString(R.string.deleted_items, count) else getString(R.string.delete_failed)
        )
        refreshSelectionAfterMutation()
        load()
    }

    private fun restoreSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val pending = MediaRepository.restoreRequest(requireContext(), items)
        if (pending != null) {
            launchSender(pending.intentSender)
        } else {
            toast(requireContext(), getString(R.string.delete_failed))
        }
    }

    private fun launchSender(sender: android.content.IntentSender) {
        try {
            trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (t: Throwable) {
            toast(requireContext(), getString(R.string.delete_failed))
        }
    }

    private class GridSpacing(private val space: Int) : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: androidx.recyclerview.widget.RecyclerView,
            state: androidx.recyclerview.widget.RecyclerView.State
        ) {
            outRect.set(space, space, space, space)
        }
    }

    companion object {
        const val ARG_SPEC = "spec"

        fun newInstance(spec: CollectionSpec): MediaGridFragment = MediaGridFragment().apply {
            arguments = Bundle().apply { putString(ARG_SPEC, spec.toKey()) }
        }
    }
}
