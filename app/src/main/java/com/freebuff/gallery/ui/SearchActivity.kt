package com.freebuff.gallery.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.freebuff.gallery.R
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.databinding.ActivitySearchBinding

/** Search box + type filter chips on top of a [MediaGridFragment] in "search" mode. */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private var filter = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.container.id, MediaGridFragment.newInstance(CollectionSpec("search")), TAG_GRID)
                .commit()
        }

        binding.query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pushQuery()
            }
        })
        binding.query.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
                true
            } else {
                false
            }
        }

        binding.filters.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chip_photos -> "photos"
                R.id.chip_videos -> "videos"
                R.id.chip_gifs -> "gifs"
                R.id.chip_screenshots -> "screenshots"
                else -> "all"
            }
            pushQuery()
        }
    }

    private fun pushQuery() {
        val grid = supportFragmentManager.findFragmentByTag(TAG_GRID) as? MediaGridFragment
        grid?.updateQuery(binding.query.text?.toString().orEmpty(), filter)
    }

    companion object {
        private const val TAG_GRID = "search_grid"
    }
}
