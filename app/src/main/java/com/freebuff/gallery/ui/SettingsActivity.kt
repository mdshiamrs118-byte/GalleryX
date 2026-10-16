package com.freebuff.gallery.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.freebuff.gallery.R
import com.freebuff.gallery.data.Prefs
import com.freebuff.gallery.databinding.ActivitySettingsBinding
import com.freebuff.gallery.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Hosts the preference screen defined in res/xml/preferences.xml. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.container.id, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val verifyLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) confirmRemovePin()
            }

        private val changeLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                updatePinPreferences()
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("pref_change_pin")?.setOnPreferenceClickListener {
                changeLauncher.launch(pinIntent(PinActivity.MODE_CHANGE))
                true
            }
            findPreference<Preference>("pref_remove_pin")?.setOnPreferenceClickListener {
                if (Prefs(requireContext()).hasPin()) {
                    verifyLauncher.launch(pinIntent(PinActivity.MODE_VERIFY))
                }
                true
            }
            findPreference<Preference>("pref_about")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.about_title)
                    .setMessage(R.string.about_message)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            updatePinPreferences()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "pref_theme") {
                val mode = when (Prefs(requireContext()).theme) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        private fun pinIntent(mode: String): Intent =
            Intent(requireContext(), PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, mode)

        private fun updatePinPreferences() {
            if (!isAdded) return
            findPreference<Preference>("pref_remove_pin")?.isEnabled =
                Prefs(requireContext()).hasPin()
        }

        private fun confirmRemovePin() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pin_remove_title)
                .setMessage(R.string.pin_remove_message)
                .setPositiveButton(R.string.pin_remove) { _, _ ->
                    val prefs = Prefs(requireContext())
                    prefs.clearPin()
                    prefs.lock()
                    toast(requireContext(), getString(R.string.pin_removed))
                    updatePinPreferences()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
