package com.example.syncplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handles the network connection to the SyncPlayer server and processes commands
 */
class SyncClient(
    private val serverIp: String,
    private val serverPort: Int,
    private val commandListener: SyncCommandListener
) {
    
    private var socket: Socket? = null
    private var isConnected = false
    private val messageBuffer = StringBuilder()
    private val commandQueue = ConcurrentLinkedQueue<JSONObject>()
    
    /**
     * Connect to the server
     * @return true if connection successful, false otherwise
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            commandListener.onRawMessageReceived("Connecting to $serverIp:$serverPort...")
            socket = Socket(serverIp, serverPort)
            isConnected = true
            commandListener.onRawMessageReceived("Connection established")
            startListening()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            commandListener.onRawMessageReceived("Connection failed: ${e.message}")
            isConnected = false
            return@withContext false
        }
    }
    
    /**
     * Disconnect from the server
     */
    fun disconnect() {
        isConnected = false
        try {
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        socket = null
    }
    
    /**
     * @return true if connected to the server
     */
    fun isConnected(): Boolean {
        return isConnected && socket?.isConnected == true && !socket?.isClosed!!
    }
    
    /**
     * Start listening for commands from the server
     */
    private fun startListening() {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                commandListener.onRawMessageReceived("Socket listening started")
                
                while (isConnected) {
                    try {
                        // Check if we have data available to read
                        if (socket?.getInputStream()?.available() ?: 0 > 0 || reader.ready()) {
                            val line = reader.readLine()
                            if (line != null) {
                                processMessage(line + "\n") // Add the newline back which is stripped by readLine()
                            } else {
                                // null indicates end of stream
                                break
                            }
                        } else {
                            // No data available yet, sleep a bit to avoid busy waiting
                            Thread.sleep(50)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        commandListener.onRawMessageReceived("Read error: ${e.message}")
                    }
                }
                commandListener.onRawMessageReceived("Listening loop ended")
            } catch (e: Exception) {
                e.printStackTrace()
                isConnected = false
                commandListener.onRawMessageReceived("Listening thread exception: ${e.message}")
                commandListener.onConnectionLost(e.message ?: "Unknown error")
            }
        }.start()
    }
    
    /**
     * Process a message from the server
     */
    private fun processMessage(message: String) {
        // Debug - notify about received raw message
        commandListener.onRawMessageReceived("Received raw: ${message.replace("\n", "\\n")}")
        
        // Add message to buffer
        messageBuffer.append(message)
        
        // Try to extract complete messages (ending with newline)
        while (messageBuffer.contains("\n")) {
            val newlineIndex = messageBuffer.indexOf("\n")
            if (newlineIndex >= 0) {
                // Extract one complete message
                val completeMessage = messageBuffer.substring(0, newlineIndex).trim()
                
                // Remove the extracted message and newline from buffer
                messageBuffer.delete(0, newlineIndex + 1)
                
                // Process the complete message if not empty
                if (completeMessage.isNotEmpty()) {
                    commandListener.onRawMessageReceived("Processing JSON: $completeMessage")
                    try {
                        val jsonCommand = JSONObject(completeMessage)
                        commandQueue.add(jsonCommand)
                        commandListener.onCommandReceived(jsonCommand)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        commandListener.onRawMessageReceived("JSON parse error: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Interface for receiving commands from the server
     */
    interface SyncCommandListener {
        fun onCommandReceived(command: JSONObject)
        fun onConnectionLost(errorMessage: String)
        fun onRawMessageReceived(message: String)
    }
}