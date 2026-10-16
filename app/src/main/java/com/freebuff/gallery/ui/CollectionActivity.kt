package com.freebuff.gallery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.databinding.ActivityCollectionBinding

/** Hosts [MediaGridFragment] for one collection (album, favorites, hidden, trash). */
class CollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            val spec = CollectionSpec.fromKey(intent.getStringExtra(EXTRA_SPEC) ?: "")
            supportFragmentManager.beginTransaction()
                .replace(binding.container.id, MediaGridFragment.newInstance(spec))
                .commit()
        }
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SPEC = "extra_spec"

        fun start(context: Context, title: String, spec: CollectionSpec) {
            context.startActivity(
                Intent(context, CollectionActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SPEC, spec.toKey())
            )
        }
    }
}
