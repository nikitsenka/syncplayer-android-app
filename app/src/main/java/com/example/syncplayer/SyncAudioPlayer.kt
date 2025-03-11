package com.example.syncplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Handles synchronized audio playback
 */
class SyncAudioPlayer(
    private val context: Context,
    private val musicDirUri: Uri?,
    private val calibrationMs: Int,
    private val playerListener: PlayerListener,
    playerView: androidx.media3.ui.PlayerView? = null
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentFile: String? = null

    // Coroutine scope for scheduling playback start
    private val scope = CoroutineScope(Dispatchers.Main)

    // Track any pending job that will trigger playback
    private var playbackJob: Job? = null

    init {
        // Attach player to any provided PlayerView
        playerView?.player = player

        // Add a listener to get debug/info callbacks
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val stateStr = getPlayerStateString(state)
                playerListener.onDebugInfo("Player state changed to: $stateStr")
                when (state) {
                    Player.STATE_ENDED -> {
                        playerListener.onPlaybackEnded()
                    }
                    Player.STATE_READY -> {
                        playerListener.onDebugInfo(
                            "Player ready to play, volume: ${player.volume}, " +
                                "audioSessionId: ${player.audioSessionId}"
                        )
                    }
                    Player.STATE_BUFFERING -> {
                        playerListener.onDebugInfo("Player buffering...")
                    }
                    Player.STATE_IDLE -> {
                        playerListener.onDebugInfo("Player idle")
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerListener.onDebugInfo("Player error: ${error.message}")
                playerListener.onPlaybackError("Playback error: ${error.message}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerListener.onDebugInfo("Playing state changed: $isPlaying")
            }

            override fun onVolumeChanged(volume: Float) {
                playerListener.onDebugInfo("Volume changed: $volume")
            }
        })

        // Set initial volume
        player.volume = 1.0f
    }

    /**
     * Process a play command from the server, respecting the calibration delay.
     */
    fun handlePlayCommand(command: JSONObject) {
        try {
            playerListener.onDebugInfo("Processing play command: $command")

            val filename = command.getString("filename")
            val startTimeNs = command.getLong("startTime")
            val startPosMs = if (command.has("startPosMs")) command.getInt("startPosMs") else 0

            playerListener.onDebugInfo(
                "Command details: filename=$filename, startTime=$startTimeNs, startPosMs=$startPosMs"
            )

            // Find the file in the music directory
            val fileUri = findFileInDirectory(filename) ?: run {
                playerListener.onPlaybackError("File not found: $filename")
                return
            }
            playerListener.onDebugInfo("File found at URI: $fileUri")
            currentFile = filename

            // Fully reset the player before setting up the new media item
            player.stop()
            player.clearMediaItems()

            // Prepare the player with the found file
            playerListener.onDebugInfo("Creating media item and preparing player...")
            val mediaItem = MediaItem.fromUri(fileUri)
            player.setMediaItem(mediaItem)
            playerListener.onDebugInfo("Preparing ExoPlayer...")
            player.prepare()

            playerListener.onDebugInfo(
                "Player state after prepare: ${getPlayerStateString(player.playbackState)}"
            )

            // Optional: Seek to start position before we eventually play
            if (startPosMs > 0) {
                playerListener.onDebugInfo("Seeking to position: ${startPosMs}ms")
                player.seekTo(startPosMs.toLong())
            }

            // Pause just to make sure it won't start until we say so
            mainHandler.post { player.pause() }

            // Let the UI know we have a player ready
            mainHandler.post {
                playerListener.onPlayerReady(player)
            }

            // Cancel any previous playback scheduling, then schedule new start
            playbackJob?.cancel()
            playbackJob = scope.launch {
                // Let the listener know we are preparing to play, with the specified delay
                mainHandler.post {
                    playerListener.onPreparingToPlay(filename, calibrationMs.toLong())
                }
                // Delay the playback by calibrationMs
                delay(calibrationMs.toLong())
                // Now start playback on the main thread
                mainHandler.post {

                    player.play()
                    playerListener.onPlaybackStarted(filename)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            playerListener.onPlaybackError("Error processing play command: ${e.message}")
        }
    }

    /**
     * Process a stop command from the server.
     * Ensures any scheduled playback is canceled and player is fully stopped.
     */
    fun handleStopCommand() {
        // Cancel any pending "start playback" job
        playbackJob?.cancel()
        playbackJob = null

        mainHandler.post {
            player.stop()
            // Clear items so nothing can resume playing
            player.clearMediaItems()
            currentFile = null
            playerListener.onPlaybackStopped()
        }
    }

    /**
     * Utility to get a string for the player's playback state.
     */
    private fun getPlayerStateString(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
    }

    /**
     * Search for a file in the music directory (tree URI) if available.
     */
    private fun findFileInDirectory(filename: String): Uri? {
        playerListener.onDebugInfo("Searching for file: $filename")

        // If we have a directory content URI
        if (musicDirUri != null) {
            try {
                val docFile = DocumentFile.fromTreeUri(context, musicDirUri)
                playerListener.onDebugInfo("Music directory URI: $musicDirUri")
                playerListener.onDebugInfo("Is valid directory: ${docFile?.isDirectory}")

                if (docFile == null || !docFile.isDirectory) {
                    playerListener.onDebugInfo("Invalid music directory")
                    return null
                }

                // Direct match in root folder
                try {
                    val directMatch = docFile.findFile(filename)
                    if (directMatch != null && directMatch.exists()) {
                        playerListener.onDebugInfo("Found file directly: ${directMatch.uri}")
                        return directMatch.uri
                    }
                } catch (e: Exception) {
                    playerListener.onDebugInfo("Error finding direct match: ${e.message}")
                }

                // If there are subfolders, split the path
                val pathComponents = filename.split("/")
                playerListener.onDebugInfo("Path components: ${pathComponents.joinToString(", ")}")
                var currentDoc = docFile
                for (i in 0 until pathComponents.size - 1) {
                    val component = pathComponents[i]
                    currentDoc = currentDoc?.findFile(component)
                    if (currentDoc == null || !currentDoc.isDirectory) {
                        playerListener.onDebugInfo("Subdirectory not found: $component")
                        return null
                    }
                    playerListener.onDebugInfo("Navigated to subdirectory: $component")
                }
                // Finally, the target file
                val targetFileName = pathComponents.last()
                val targetFile = currentDoc?.findFile(targetFileName)
                if (targetFile == null) {
                    playerListener.onDebugInfo("Target file not found: $targetFileName")
                    return null
                }
                playerListener.onDebugInfo("Found target file: ${targetFile.uri}")
                return targetFile.uri

            } catch (e: Exception) {
                e.printStackTrace()
                playerListener.onDebugInfo("Error finding file: ${e.message}")
                return null
            }
        } else {
            playerListener.onDebugInfo("No music directory URI available")
        }
        return null
    }

    /**
     * Clean up player resources when done.
     */
    fun release() {
        playbackJob?.cancel()
        player.release()
    }

    /**
     * Interface for communicating important player events back to a controller or UI.
     */
    interface PlayerListener {
        fun onPreparingToPlay(filename: String, delayMs: Long)
        fun onPlaybackStarted(filename: String)
        fun onPlaybackStopped()
        fun onPlaybackEnded()
        fun onPlaybackError(message: String)
        fun onDebugInfo(message: String)
        fun onPlayerReady(player: ExoPlayer)
    }
}
