package com.example.syncplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity(), SyncClient.SyncCommandListener, SyncAudioPlayer.PlayerListener {

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var trackInfoText: TextView
    private lateinit var playerView: PlayerView
    private lateinit var settingsButton: FloatingActionButton
    private lateinit var debugCardView: View
    private lateinit var debugText: TextView
    private lateinit var clearLogButton: Button
    
    private lateinit var prefs: SharedPreferences
    private var syncClient: SyncClient? = null
    private var audioPlayer: SyncAudioPlayer? = null
    private var debugEnabled = false
    
    private val READ_STORAGE_PERMISSION_REQUEST = 1001
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        trackInfoText = findViewById(R.id.trackInfoText)
        playerView = findViewById(R.id.playerView)
        settingsButton = findViewById(R.id.settingsButton)
        debugCardView = findViewById(R.id.debugCardView)
        debugText = findViewById(R.id.debugText)
        clearLogButton = findViewById(R.id.clearLogButton)
        
        // Setup preferences
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        debugEnabled = prefs.getBoolean("enable_debug", false)
        
        // Update debug visibility
        updateDebugVisibility()
        
        // Setup button listeners
        connectButton.setOnClickListener {
            if (syncClient?.isConnected() == true) {
                disconnect()
            } else {
                connect()
            }
        }
        
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        clearLogButton.setOnClickListener {
            debugText.text = ""
        }
        
        // Request permissions
        requestPermissions()
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if debug setting has changed
        val newDebugEnabled = prefs.getBoolean("enable_debug", false)
        if (debugEnabled != newDebugEnabled) {
            debugEnabled = newDebugEnabled
            updateDebugVisibility()
        }
        
        // Create player if needed
        if (audioPlayer == null) {
            initializePlayer()
        }
    }
    
    private fun updateDebugVisibility() {
        debugCardView.visibility = if (debugEnabled) View.VISIBLE else View.GONE
    }
    
    override fun onPause() {
        super.onPause()
        
        // If we're not actively connected, release the player
        if (syncClient?.isConnected() != true) {
            releasePlayer()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        releasePlayer()
    }
    
    private fun initializePlayer() {
        val musicDirString = prefs.getString("music_directory", null)
        val musicDirUri = if (musicDirString != null) Uri.parse(musicDirString) else null
        val calibrationMs = prefs.getString("calibration", "0")?.toIntOrNull() ?: 0
        
        addDebugLog("Creating player with music dir: $musicDirString, calibration: ${calibrationMs}ms")
        
        // Pass the playerView to connect the ExoPlayer to the UI
        audioPlayer = SyncAudioPlayer(this, musicDirUri, calibrationMs, this, playerView)
        
        // Make player view visible by default so controls are accessible
        playerView.visibility = View.VISIBLE
    }
    
    private fun releasePlayer() {
        audioPlayer?.release()
        audioPlayer = null
    }
    
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                    READ_STORAGE_PERMISSION_REQUEST
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    READ_STORAGE_PERMISSION_REQUEST
                )
            }
        }
    }
    
    private fun connect() {
        val serverIp = prefs.getString("server_ip", "") ?: ""
        val serverPortStr = prefs.getString("server_port", "12345") ?: "12345"
        val serverPort = serverPortStr.toIntOrNull() ?: 12345
        
        if (serverIp.isEmpty()) {
            Toast.makeText(this, R.string.error_invalid_settings, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Initialize client if needed
        if (syncClient == null) {
            syncClient = SyncClient(serverIp, serverPort, this)
        }
        
        // Initialize player if needed
        if (audioPlayer == null) {
            initializePlayer()
        }
        
        // Update UI
        statusText.text = getString(R.string.status_connecting)
        connectButton.isEnabled = false
        trackInfoText.text = getString(R.string.connecting_to_server, serverIp, serverPort)
        
        // Connect to server
        lifecycleScope.launch {
            val connected = syncClient?.connect() ?: false
            withContext(Dispatchers.Main) {
                if (connected) {
                    statusText.text = getString(R.string.status_connected)
                    connectButton.text = getString(R.string.disconnect_button)
                    trackInfoText.text = getString(R.string.player_waiting)
                } else {
                    statusText.text = getString(R.string.status_disconnected)
                    trackInfoText.text = getString(R.string.player_not_connected)
                    syncClient = null
                }
                connectButton.isEnabled = true
            }
        }
    }
    
    private fun disconnect() {
        // Disconnect client
        syncClient?.disconnect()
        syncClient = null
        
        // Update UI
        statusText.text = getString(R.string.status_disconnected)
        connectButton.text = getString(R.string.connect_button)
        trackInfoText.text = getString(R.string.player_not_connected)
        playerView.visibility = View.GONE
    }
    
    // Add log to debug view
    private fun addDebugLog(message: String) {
        if (!debugEnabled) return
        
        lifecycleScope.launch(Dispatchers.Main) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val logLine = "[$timestamp] $message\n"
            
            // Prepend the log line (newest logs at top)
            debugText.text = logLine + debugText.text
        }
    }
    
    // SyncClient.SyncCommandListener implementation
    override fun onCommandReceived(command: JSONObject) {
        lifecycleScope.launch(Dispatchers.Main) {
            val cmd = command.optString("cmd", "")
            addDebugLog("Received command: $command")
            
            when (cmd) {
                "PLAY" -> {
                    audioPlayer?.handlePlayCommand(command)
                }
                "STOP" -> {
                    audioPlayer?.handleStopCommand()
                }
                else -> {
                    // Ignore unknown commands
                    addDebugLog("Unknown command ignored: $cmd")
                }
            }
        }
    }
    
    override fun onRawMessageReceived(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            addDebugLog(message)
        }
    }
    
    override fun onConnectionLost(errorMessage: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            statusText.text = getString(R.string.status_disconnected)
            connectButton.text = getString(R.string.connect_button)
            trackInfoText.text = getString(R.string.connection_error, errorMessage)
            connectButton.isEnabled = true
            syncClient = null
            
            addDebugLog("Connection lost: $errorMessage")
        }
    }
    
    // SyncAudioPlayer.PlayerListener implementation
    override fun onPreparingToPlay(filename: String, delayMs: Long) {
        runOnUiThread {
            trackInfoText.text = "Preparing to play: $filename in ${delayMs}ms"
            addDebugLog("Preparing to play: $filename with delay ${delayMs}ms")
        }
    }
    
    override fun onPlaybackStarted(filename: String) {
        runOnUiThread {
            trackInfoText.text = getString(R.string.player_playing, filename)
            
            // Make sure player view is visible
            playerView.visibility = View.VISIBLE
            
            // Re-enable controls once playing
            playerView.useController = true
            
            addDebugLog("Playback started: $filename")
            
            // Add current system time for debugging sync
            val currentTimeMs = System.currentTimeMillis()
            val timeNs = System.nanoTime()
            addDebugLog("Start time: ${currentTimeMs}ms / ${timeNs}ns")
            
            // Check audio focus and volume
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                addDebugLog("Device volume: $currentVolume/$maxVolume")
                
                // Boost volume if too low
                if (currentVolume < maxVolume / 4) {
                    audioManager.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        maxVolume / 2,
                        0
                    )
                    addDebugLog("Volume was low, increased to ${maxVolume / 2}")
                }
            } catch (e: Exception) {
                addDebugLog("Error checking audio: ${e.message}")
            }
        }
    }
    
    override fun onPlaybackStopped() {
        runOnUiThread {
            trackInfoText.text = getString(R.string.player_waiting)
            playerView.visibility = View.GONE
            addDebugLog("Playback stopped")
        }
    }
    
    override fun onPlaybackEnded() {
        runOnUiThread {
            trackInfoText.text = getString(R.string.player_waiting)
            playerView.visibility = View.GONE
            addDebugLog("Playback ended")
        }
    }
    
    override fun onPlaybackError(message: String) {
        runOnUiThread {
            trackInfoText.text = getString(R.string.player_error, message)
            playerView.visibility = View.GONE
            addDebugLog("Playback error: $message")
        }
    }
    
    override fun onDebugInfo(message: String) {
        runOnUiThread {
            addDebugLog("Debug: $message")
        }
    }
    
    override fun onPlayerReady(player: ExoPlayer) {
        runOnUiThread {
            // Make sure player view is visible
            playerView.visibility = View.VISIBLE
            
            // Make sure playback controls are disabled
            try {
                // Attempt to disable user controls on the player UI
                playerView.useController = false
                
                addDebugLog("Player ready, UI controls disabled")
            } catch (e: Exception) {
                addDebugLog("Error configuring player UI: ${e.message}")
            }
        }
    }
}