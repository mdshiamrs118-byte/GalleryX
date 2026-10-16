package com.freebuff.gallery.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.freebuff.gallery.R
import com.freebuff.gallery.data.AlbumItem
import com.freebuff.gallery.data.MediaItem
import com.freebuff.gallery.databinding.ItemAlbumBinding

/** One entry of the albums grid: a real album or a special collection card. */
data class AlbumEntry(
    val key: String,
    val title: String,
    val count: Int,
    val albumId: String,
    val cover: MediaItem?,
    val iconRes: Int,      // 0 for regular albums
    val backgroundRes: Int // 0 for regular albums
) {
    companion object {
        fun of(album: AlbumItem) = AlbumEntry(
            key = "album:${album.id}",
            title = album.name,
            count = album.count,
            albumId = album.id,
            cover = album.cover,
            iconRes = 0,
            backgroundRes = 0
        )
    }
}

class AlbumAdapter(
    private val onEntryClicked: (AlbumEntry) -> Unit
) : ListAdapter<AlbumEntry, AlbumAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemAlbumBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AlbumEntry) {
            val context = binding.root.context
            binding.albumName.text = entry.title
            binding.albumCount.text =
                if (entry.count == 1) context.getString(R.string.album_count_one)
                else context.getString(R.string.album_count, entry.count)

            if (entry.cover != null) {
                binding.specialIcon.isVisible = false
                binding.cover.setBackgroundColor(0)
                Glide.with(binding.cover)
                    .load(entry.cover.uri)
                    .placeholder(R.drawable.bg_placeholder)
                    .centerCrop()
                    .into(binding.cover)
            } else {
                Glide.with(binding.cover).clear(binding.cover)
                binding.cover.setBackgroundResource(entry.backgroundRes)
                binding.specialIcon.setImageResource(entry.iconRes)
                binding.specialIcon.isVisible = true
            }

            binding.root.setOnClickListener { onEntryClicked(entry) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AlbumEntry>() {
            override fun areItemsTheSame(oldItem: AlbumEntry, newItem: AlbumEntry): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: AlbumEntry, newItem: AlbumEntry): Boolean =
                oldItem == newItem
        }
    }
}
