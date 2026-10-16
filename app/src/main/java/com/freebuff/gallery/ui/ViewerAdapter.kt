package com.freebuff.gallery.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.freebuff.gallery.data.MediaItem
import com.freebuff.gallery.databinding.ItemViewerBinding

/** Pages of the full-screen viewer: a zoomable image (or video thumbnail + player) per item. */
class ViewerAdapter(
    private val onTap: () -> Unit,
    private val onPlay: (Holder) -> Unit
) : RecyclerView.Adapter<ViewerAdapter.Holder>() {

    private var items: List<MediaItem> = emptyList()

    fun submit(list: List<MediaItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemViewerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: Holder) {
        try {
            holder.binding.video.stopPlayback()
        } catch (t: Throwable) {
            // nothing to stop
        }
        super.onViewRecycled(holder)
    }

    inner class Holder(val binding: ItemViewerBinding) : RecyclerView.ViewHolder(binding.root) {

        var item: MediaItem? = null

        init {
            binding.image.onSingleTap = { onTap() }
            binding.root.setOnClickListener { onTap() }
            binding.btnPlayBig.setOnClickListener { onPlay(this) }
        }

        fun bind(item: MediaItem) {
            this.item = item
            binding.video.visibility = View.GONE
            binding.videoError.visibility = View.GONE
            binding.itemProgress.visibility = View.GONE
            binding.image.visibility = View.VISIBLE
            binding.btnPlayBig.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            Glide.with(binding.image)
                .load(item.uri)
                .override(2560, 2560)
                .fitCenter()
                .into(binding.image)
        }
    }
}
