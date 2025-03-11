package com.example.syncplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }


    class SettingsFragment : PreferenceFragmentCompat() {
        private val openDirectoryLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                // Persist permission to access this directory
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, takeFlags
                )
                
                // Save the URI to preferences
                val prefs = preferenceManager.sharedPreferences
                prefs?.edit()?.apply {
                    putString("music_directory", uri.toString())
                    apply()
                }
                
                // Update the summary
                val musicDirPref = findPreference<Preference>("music_directory")
                musicDirPref?.summary = uri.toString()
            }
        }
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            // Initialize music directory preference
            val musicDirPref = findPreference<Preference>("music_directory")
            val prefs = preferenceManager.sharedPreferences
            val savedUri = prefs?.getString("music_directory", null)
            
            if (savedUri != null) {
                musicDirPref?.summary = savedUri
            }

            musicDirPref?.setOnPreferenceClickListener {
                try {
                    // Open directory picker using the registered contract
                    openDirectoryLauncher.launch(null)
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Error opening directory picker: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }

        }
    }
}