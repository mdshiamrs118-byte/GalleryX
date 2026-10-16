package com.freebuff.gallery.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.freebuff.gallery.R
import com.freebuff.gallery.data.MediaItem
import com.freebuff.gallery.data.Row
import com.freebuff.gallery.data.Thumbs
import com.freebuff.gallery.databinding.ItemMediaBinding
import com.freebuff.gallery.databinding.ItemMediaDayHeaderBinding
import com.freebuff.gallery.util.formatDuration

/**
 * Grid adapter for the timeline: interleaves day headers with media cells and
 * renders selection state.
 */
class MediaAdapter(
    private val onItemClicked: (MediaItem) -> Unit,
    private val onItemLongClicked: (MediaItem) -> Unit
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    /** Controlled by [MediaGridFragment] on every selection change. */
    var selectionMode: Boolean = false

    /** Controlled by [MediaGridFragment] on every selection change. */
    var selectedIds: Set<Long> = emptySet()

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_MEDIA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderHolder(
                ItemMediaDayHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        } else {
            MediaHolder(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Media -> (holder as MediaHolder).bind(row.item)
        }
    }

    private inner class MediaHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            val context = binding.root.context

            binding.thumb.tag = item.id
            if (item.isVideo) {
                binding.thumb.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.placeholder)
                )
                binding.thumb.setImageDrawable(null)
                binding.videoInfo.isVisible = true
                binding.duration.text = formatDuration(item.duration)
                Thumbs.video(context, item) { bitmap ->
                    if (bitmap != null && binding.thumb.tag == item.id) {
                        binding.thumb.setImageBitmap(bitmap)
                    }
                }
            } else {
                binding.videoInfo.isVisible = false
                binding.thumb.setBackgroundColor(0)
                com.bumptech.glide.Glide.with(binding.thumb)
                    .load(item.uri)
                    .placeholder(R.drawable.bg_placeholder)
                    .centerCrop()
                    .into(binding.thumb)
            }

            val selected = selectionMode && selectedIds.contains(item.id)
            binding.check.isVisible = selectionMode
            binding.selectedOverlay.isVisible = selected
            if (selectionMode) {
                binding.check.setImageResource(
                    if (selected) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
                )
                binding.check.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.primary else android.R.color.white
                    )
                )
            }

            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onItemClicked(item)
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onItemLongClicked(item)
                true
            }
        }
    }

    private class HeaderHolder(private val binding: ItemMediaDayHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Header) {
            binding.headerText.text = row.label
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MEDIA = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Header && newItem is Row.Header -> oldItem.id == newItem.id
                oldItem is Row.Media && newItem is Row.Media -> oldItem.item.id == newItem.item.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }
}
