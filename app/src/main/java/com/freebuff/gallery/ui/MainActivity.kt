package com.freebuff.gallery.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.freebuff.gallery.R
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.data.MediaRepository
import com.freebuff.gallery.data.Prefs
import com.freebuff.gallery.databinding.ActivityMainBinding
import com.freebuff.gallery.util.Permissions
import com.freebuff.gallery.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator

/** Photo timeline + albums, with search / sort / settings on the toolbar. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.pager.adapter = TabsAdapter(this)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.setText(if (position == 0) R.string.tab_photos else R.string.tab_albums)
        }.attach()

        binding.btnGrant.setOnClickListener { requestMediaAccess() }
        maybePromptForAccess()
    }

    override fun onResume() {
        super.onResume()
        binding.permissionView.visibility =
            if (Permissions.hasMediaAccess(this)) View.GONE else View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            startActivity(Intent(this, SearchActivity::class.java))
            true
        }
        R.id.action_sort -> {
            showSortDialog()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------- sort

    private fun showSortDialog() {
        val prefs = Prefs(this)
        val entries = resources.getStringArray(R.array.entries_sort)
        val values = resources.getStringArray(R.array.values_sort)
        val current = values.indexOf(prefs.sort()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(entries, current) { dialog, which ->
                prefs.setSort(values[which])
                MediaRepository.invalidate()
                reloadGrids()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reloadGrids() {
        supportFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is MediaGridFragment -> fragment.reload()
                is AlbumsFragment -> fragment.reload()
            }
        }
    }

    // ------------------------------------------------------------- permission

    private fun maybePromptForAccess() {
        if (Permissions.hasMediaAccess(this)) return
        binding.permissionView.visibility = View.VISIBLE
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (!sp.getBoolean(KEY_PROMPTED, false)) {
            sp.edit().putBoolean(KEY_PROMPTED, true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_message)
                .setPositiveButton(R.string.grant_access) { _, _ -> requestMediaAccess() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun requestMediaAccess() {
        permissionLauncher.launch(Permissions.required())
    }

    private fun onPermissionResult() {
        val granted = Permissions.hasMediaAccess(this)
        binding.permissionView.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            MediaRepository.invalidate()
            reloadGrids()
        } else {
            toast(this, getString(R.string.permission_denied))
        }
    }

    // ------------------------------------------------------------------ tabs

    private class TabsAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == 0) MediaGridFragment.newInstance(CollectionSpec("photos"))
            else AlbumsFragment()
    }

    companion object {
        private const val KEY_PROMPTED = "permission_prompted_once"
    }
}
