/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * LiveKit connection state
 */
enum class LiveKitConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

/**
 * LiveKit Manager - Handles WebRTC connection to LiveKit server
 * 
 * This manager handles:
 * - Connection to LiveKit server
 * - Publishing video frames from Ray-Ban Meta glasses
 * - Publishing audio from phone microphone
 * - Receiving audio responses from AI agent
 */
class LiveKitManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LiveKitManager"
    }
    
    init {
        // Initialize debug logger
        DebugLogger.init(context)
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private var room: Room? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private var videoCapturer: BitmapVideoCapturer? = null
    
    // Phone camera video track
    private var localPhoneVideoTrack: LocalVideoTrack? = null
    private var phoneVideoCapturer: CameraVideoCapturer? = null
    private var currentCameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    
    private var eventCollectJob: Job? = null
    
    // State flows
    private val _connectionState = MutableStateFlow(LiveKitConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveKitConnectionState> = _connectionState.asStateFlow()
    
    private val _remoteAudioTrack = MutableStateFlow<RemoteAudioTrack?>(null)
    val remoteAudioTrack: StateFlow<RemoteAudioTrack?> = _remoteAudioTrack.asStateFlow()
    
    private val _remoteVideoTrack = MutableStateFlow<RemoteVideoTrack?>(null)
    val remoteVideoTrack: StateFlow<RemoteVideoTrack?> = _remoteVideoTrack.asStateFlow()
    
    private val _agentModeActive = MutableStateFlow<Boolean>(false)
    val agentModeActive: StateFlow<Boolean> = _agentModeActive.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _roomName = MutableStateFlow("")
    val roomName: StateFlow<String> = _roomName.asStateFlow()
    
    // Debug info
    private val _debugInfo = MutableStateFlow("")
    val debugInfo: StateFlow<String> = _debugInfo.asStateFlow()
    
    // Configuration
    private var serverUrl: String = ""
    private var httpServerUrl: String = "" // HTTP server for token generation
    
    /**
     * Configure LiveKit server connection
     */
    fun configure(serverUrl: String, apiKey: String, apiSecret: String) {
        this.serverUrl = serverUrl
        // Extract HTTP URL from WebSocket URL
        this.httpServerUrl = serverUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .replace(":7880", ":8080") // Assuming HTTP server is on port 8080
        Log.d(TAG, "Configured with server: $serverUrl, HTTP: $httpServerUrl")
    }
    
    /**
     * Connect to LiveKit room
     */
    suspend fun connect(roomName: String, participantName: String): Boolean {
        if (_connectionState.value == LiveKitConnectionState.CONNECTED) {
            Log.w(TAG, "Already connected")
            return true
        }
        
        _connectionState.value = LiveKitConnectionState.CONNECTING
        _roomName.value = roomName
        
        // Build debug info
        val debugBuilder = StringBuilder()
        debugBuilder.appendLine("=== DEBUG INFO ===")
        debugBuilder.appendLine("Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        debugBuilder.appendLine("")
        debugBuilder.appendLine("📱 PHONE INFO:")
        debugBuilder.appendLine("  IP Address: ${getDeviceIpAddress()}")
        debugBuilder.appendLine("  Network: ${getNetworkType()}")
        debugBuilder.appendLine("")
        debugBuilder.appendLine("🖥️ SERVER CONFIG:")
        debugBuilder.appendLine("  WebSocket URL: $serverUrl")
        debugBuilder.appendLine("  HTTP URL: $httpServerUrl")
        debugBuilder.appendLine("  Token endpoint: $httpServerUrl/api/token")
        debugBuilder.appendLine("")
        debugBuilder.appendLine("🚪 ROOM CONFIG:")
        debugBuilder.appendLine("  Room: $roomName")
        debugBuilder.appendLine("  Participant: $participantName")
        debugBuilder.appendLine("")
        
        return try {
            // First, check server health
            debugBuilder.appendLine("⏳ Checking server health...")
            val healthResult = checkServerHealth()
            debugBuilder.appendLine(healthResult)
            
            // Get token from server
            debugBuilder.appendLine("")
            debugBuilder.appendLine("⏳ Requesting token...")
            val token = getTokenFromServer(roomName, participantName)
            if (token == null) {
                Log.e(TAG, "Failed to get token from server")
                debugBuilder.appendLine("❌ TOKEN ERROR:")
                debugBuilder.appendLine("  Failed to get token from server")
                debugBuilder.appendLine("  Check if server is reachable")
                debugBuilder.appendLine("  Check if phone is on same network")
                _debugInfo.value = debugBuilder.toString()
                _connectionState.value = LiveKitConnectionState.ERROR
                _errorMessage.value = "Failed to get token from server"
                return false
            }
            
            debugBuilder.appendLine("✅ Token received!")
            debugBuilder.appendLine("  Token length: ${token.length} chars")
            debugBuilder.appendLine("")
            debugBuilder.appendLine("⏳ Connecting to LiveKit...")
            
            // Create room
            room = LiveKit.create(context)
            
            // Setup event listeners
            setupRoomEventListeners()
            
            // Connect to room
            room?.connect(serverUrl, token)
            
            debugBuilder.appendLine("✅ Connected successfully!")
            _debugInfo.value = debugBuilder.toString()
            _connectionState.value = LiveKitConnectionState.CONNECTED
            Log.d(TAG, "Connected to room: $roomName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}", e)
            debugBuilder.appendLine("❌ CONNECTION ERROR:")
            debugBuilder.appendLine("  ${e.javaClass.simpleName}")
            debugBuilder.appendLine("  ${e.message}")
            e.cause?.let { cause ->
                debugBuilder.appendLine("  Cause: ${cause.message}")
            }
            _debugInfo.value = debugBuilder.toString()
            _connectionState.value = LiveKitConnectionState.ERROR
            _errorMessage.value = "Connection failed: ${e.message}"
            false
        }
    }
    
    /**
     * Get device IP address
     */
    fun getDeviceIpAddress(): String {
        try {
            // Try to get WiFi IP first
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ipAddress?.let { ip ->
                if (ip != 0) {
                    val ipString = String.format(
                        java.util.Locale.US,
                        "%d.%d.%d.%d",
                        ip and 0xff,
                        ip shr 8 and 0xff,
                        ip shr 16 and 0xff,
                        ip shr 24 and 0xff
                    )
                    if (ipString != "0.0.0.0") {
                        return ipString
                    }
                }
            }
            
            // Fallback: iterate network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}")
        }
        return "Unable to get IP"
    }
    
    /**
     * Get network type
     */
    private fun getNetworkType(): String {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            
            return when {
                capabilities == null -> "No network"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
        } catch (e: Exception) {
            return "Unknown"
        }
    }
    
    /**
     * Get last debug info
     */
    fun getLastDebugInfo(): String = _debugInfo.value
    
    /**
     * Check server health
     */
    private suspend fun checkServerHealth(): String {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val healthUrl = "$httpServerUrl/api/health"
            try {
                Log.d(TAG, "Checking health at: $healthUrl")
                
                val request = Request.Builder()
                    .url(healthUrl)
                    .get()
                    .build()
                
                val startTime = System.currentTimeMillis()
                val response = httpClient.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startTime
                
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "Health check OK: $body")
                    "✅ Health check OK (${elapsed}ms)\n  URL: $healthUrl\n  Response: $body"
                } else {
                    Log.e(TAG, "Health check failed: ${response.code}")
                    "⚠️ Health check returned ${response.code}\n  URL: $healthUrl"
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Health check - Unknown host", e)
                "❌ Health: DNS Error - Host not found\n  URL: $healthUrl\n  Error: ${e.message}"
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Health check - Connection refused", e)
                "❌ Health: Connection REFUSED\n  URL: $healthUrl\n  Il server non è in ascolto sulla porta 8080\n  O il firewall blocca la connessione"
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Health check - Timeout", e)
                "❌ Health: TIMEOUT (10 sec)\n  URL: $healthUrl\n  Il server non risponde"
            } catch (e: Exception) {
                Log.e(TAG, "Health check error", e)
                "❌ Health: ${e.javaClass.simpleName}\n  URL: $healthUrl\n  Error: ${e.message}"
            }
        }
    }
    
    /**
     * Get token from the LiveKit server's HTTP API
     */
    private suspend fun getTokenFromServer(roomName: String, participantName: String): String? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("room_name", roomName)
                    put("participant_name", participantName)
                }
                
                val tokenUrl = "$httpServerUrl/api/token"
                Log.d(TAG, "Requesting token from: $tokenUrl with room=$roomName, participant=$participantName")
                
                // Update debug info
                _debugInfo.update { current -> 
                    current + "  URL: $tokenUrl\n  Body: ${jsonBody.toString()}\n"
                }
                
                val request = Request.Builder()
                    .url(tokenUrl)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()
                
                Log.d(TAG, "Executing HTTP request...")
                val response = httpClient.newCall(request).execute()
                Log.d(TAG, "Token response code: ${response.code}")
                
                _debugInfo.update { current -> 
                    current + "  Response code: ${response.code}\n"
                }
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Token response body: $responseBody")
                    _debugInfo.update { current -> 
                        current + "  Response: $responseBody\n"
                    }
                    val json = JSONObject(responseBody ?: "{}")
                    val token = json.optString("token", "")
                    if (token.isNotEmpty()) token else null
                } else {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Token request failed: ${response.code} - $errorBody")
                    _debugInfo.update { current -> 
                        current + "  ❌ HTTP Error: ${response.code}\n  Body: $errorBody\n"
                    }
                    null
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Unknown host: ${e.message}", e)
                _debugInfo.update { current -> 
                    current + "  ❌ DNS Error: Host not found\n  Check server IP address\n"
                }
                null
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection refused: ${e.message}", e)
                _debugInfo.update { current -> 
                    current + "  ❌ Connection refused\n  Server might not be running\n  Or firewall blocking port 8080\n"
                }
                null
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Timeout: ${e.message}", e)
                _debugInfo.update { current -> 
                    current + "  ❌ Timeout (10 sec)\n  Server not responding\n  Check if on same network\n"
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting token: ${e.message}", e)
                _debugInfo.update { current -> 
                    current + "  ❌ Error: ${e.javaClass.simpleName}\n  ${e.message}\n"
                }
                null
            }
        }
    }
    
    /**
     * Disconnect from LiveKit room
     */
    fun disconnect() {
        scope.launch {
            try {
                stopPublishingVideo()
                stopPublishingPhoneCamera()
                stopPublishingAudio()
                eventCollectJob?.cancel()
                room?.disconnect()
                room = null
                _connectionState.value = LiveKitConnectionState.DISCONNECTED
                _remoteAudioTrack.value = null
                _remoteVideoTrack.value = null
                _agentModeActive.value = false
                Log.d(TAG, "Disconnected from room")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}", e)
            }
        }
    }
    
    /**
     * Start publishing video track with bitmap frames
     */
    suspend fun startPublishingVideo(width: Int = 640, height: Int = 480, fps: Int = 24): Boolean {
        // #region agent log
        DebugLogger.log("C", "LiveKitManager.startPublishingVideo.entry", "Starting video publish", mapOf("roomNull" to (room == null), "width" to width, "height" to height))
        // #endregion
        val currentRoom = room ?: run {
            Log.e(TAG, "Not connected to room")
            return false
        }
        
        return try {
            // Create bitmap video capturer
            videoCapturer = BitmapVideoCapturer(width, height, fps)
            // #region agent log
            DebugLogger.log("C", "LiveKitManager.startPublishingVideo.capturerCreated", "Capturer created", mapOf("videoCapturerNull" to (videoCapturer == null)))
            // #endregion
            
            // Create local video track
            localVideoTrack = currentRoom.localParticipant.createVideoTrack(
                name = "glasses-camera",
                capturer = videoCapturer!!
            )
            // #region agent log
            DebugLogger.log("C", "LiveKitManager.startPublishingVideo.trackCreated", "Track created", mapOf("localVideoTrackNull" to (localVideoTrack == null)))
            // #endregion
            
            // Publish track
            currentRoom.localParticipant.publishVideoTrack(localVideoTrack!!)
            
            // Force start capture - LiveKit may not call startCapture() automatically
            videoCapturer?.startCapture(width, height, fps)
            
            // #region agent log
            val publishedTracks = currentRoom.localParticipant.trackPublications.values.filter { it.kind == io.livekit.android.room.track.Track.Kind.VIDEO }
            DebugLogger.log("C", "LiveKitManager.startPublishingVideo.published", "Track published successfully", mapOf(
                "trackName" to localVideoTrack!!.name,
                "publishedTracksCount" to publishedTracks.size,
                "publishedTrackNames" to publishedTracks.map { it.name }.joinToString(","),
                "capturerStarted" to true
            ))
            // #endregion
            
            Log.d(TAG, "Started publishing video track: ${localVideoTrack!!.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish video: ${e.message}", e)
            // #region agent log
            DebugLogger.log("C", "LiveKitManager.startPublishingVideo.error", "Failed to publish", mapOf("error" to (e.message ?: "unknown")))
            // #endregion
            false
        }
    }
    
    /**
     * Stop publishing video track
     */
    fun stopPublishingVideo() {
        localVideoTrack?.let { track ->
            room?.localParticipant?.unpublishTrack(track)
            track.stop()
        }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack = null
        Log.d(TAG, "Stopped publishing video")
    }
    
    /**
     * Send a video frame (bitmap) to LiveKit
     */
    fun sendVideoFrame(bitmap: Bitmap) {
        // #region agent log
        DebugLogger.log("B", "LiveKitManager.sendVideoFrame", "sendVideoFrame called", mapOf("videoCapturerNull" to (videoCapturer == null)))
        // #endregion
        videoCapturer?.onBitmapFrame(bitmap)
    }
    
    /**
     * Start publishing phone camera video track
     */
    suspend fun startPublishingPhoneCamera(cameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK): Boolean {
        // #region agent log
        DebugLogger.log("D", "LiveKitManager.startPublishingPhoneCamera.entry", "Starting phone camera", mapOf("roomNull" to (room == null), "cameraFacing" to cameraFacing))
        // #endregion
        val currentRoom = room ?: run {
            Log.e(TAG, "Not connected to room")
            return false
        }
        
        return try {
            // Check camera permission
            val hasPermission = PackageManager.PERMISSION_GRANTED ==
                context.checkSelfPermission(android.Manifest.permission.CAMERA)
            
            // #region agent log
            DebugLogger.log("D", "LiveKitManager.startPublishingPhoneCamera.permission", "Permission check", mapOf("hasPermission" to hasPermission))
            // #endregion
            
            if (!hasPermission) {
                Log.e(TAG, "Camera permission not granted")
                _errorMessage.value = "Permesso fotocamera non concesso. Vai in Impostazioni > App > CameraAccess > Permessi"
                return false
            }
            
            // Stop existing phone camera if any
            stopPublishingPhoneCamera()
            
            currentCameraFacing = cameraFacing
            
            Log.d(TAG, "Creating camera video capturer (facing: $cameraFacing)")
            
            // Create camera video capturer
            phoneVideoCapturer = CameraVideoCapturer(
                context = context,
                cameraFacing = cameraFacing,
                targetWidth = 640,
                targetHeight = 480,
                targetFps = 24
            )
            // #region agent log
            DebugLogger.log("D", "LiveKitManager.startPublishingPhoneCamera.capturerCreated", "Phone capturer created", mapOf("phoneVideoCapturerNull" to (phoneVideoCapturer == null)))
            // #endregion
            
            Log.d(TAG, "Creating video track for phone camera")
            
            // Create local video track
            localPhoneVideoTrack = currentRoom.localParticipant.createVideoTrack(
                name = if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK) 
                    "phone-camera-back" else "phone-camera-front",
                capturer = phoneVideoCapturer!!
            )
            // #region agent log
            DebugLogger.log("E", "LiveKitManager.startPublishingPhoneCamera.trackCreated", "Phone track created", mapOf("localPhoneVideoTrackNull" to (localPhoneVideoTrack == null)))
            // #endregion
            
            Log.d(TAG, "Publishing phone camera video track")
            
            // Publish track
            currentRoom.localParticipant.publishVideoTrack(localPhoneVideoTrack!!)
            
            // Force start capture - LiveKit may not call startCapture() automatically
            phoneVideoCapturer?.startCapture(640, 480, 24)
            
            // #region agent log
            val publishedTracks = currentRoom.localParticipant.trackPublications.values.filter { it.kind == io.livekit.android.room.track.Track.Kind.VIDEO }
            DebugLogger.log("E", "LiveKitManager.startPublishingPhoneCamera.published", "Phone track published", mapOf(
                "trackName" to localPhoneVideoTrack!!.name,
                "publishedTracksCount" to publishedTracks.size,
                "publishedTrackNames" to publishedTracks.map { it.name }.joinToString(","),
                "capturerStarted" to true
            ))
            // #endregion
            
            Log.d(TAG, "Started publishing phone camera track (facing: $cameraFacing): ${localPhoneVideoTrack!!.name}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied: ${e.message}", e)
            // #region agent log
            DebugLogger.log("D", "LiveKitManager.startPublishingPhoneCamera.securityError", "SecurityException", mapOf("error" to (e.message ?: "unknown")))
            // #endregion
            _errorMessage.value = "Permesso fotocamera negato: ${e.message}"
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish phone camera: ${e.message}", e)
            // #region agent log
            DebugLogger.log("D", "LiveKitManager.startPublishingPhoneCamera.error", "Exception", mapOf("error" to (e.message ?: "unknown")))
            // #endregion
            _errorMessage.value = "Errore pubblicazione fotocamera: ${e.message}"
            false
        }
    }
    
    /**
     * Stop publishing phone camera video track
     */
    fun stopPublishingPhoneCamera() {
        localPhoneVideoTrack?.let { track ->
            room?.localParticipant?.unpublishTrack(track)
            track.stop()
        }
        phoneVideoCapturer?.dispose()
        phoneVideoCapturer = null
        localPhoneVideoTrack = null
        Log.d(TAG, "Stopped publishing phone camera")
    }
    
    /**
     * Switch phone camera between front and back
     */
    suspend fun switchPhoneCamera(): Boolean {
        val newFacing = if (currentCameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        
        return if (localPhoneVideoTrack != null) {
            // Restart with new facing
            startPublishingPhoneCamera(newFacing)
        } else {
            false
        }
    }
    
    /**
     * Get current camera facing
     */
    fun getCurrentCameraFacing(): Int = currentCameraFacing
    
    /**
     * Toggle agent mode - send force_agent_response message
     */
    suspend fun toggleAgentMode(): Boolean {
        val currentRoom = room ?: run {
            Log.e(TAG, "Room non connessa")
            return false
        }
        
        if (_connectionState.value != LiveKitConnectionState.CONNECTED) {
            Log.w(TAG, "Room non connessa")
            return false
        }
        
        val newState = !_agentModeActive.value
        _agentModeActive.value = newState
        
        return try {
            val message = JSONObject().apply {
                put("type", "force_agent_response")
                put("force", newState)
            }
            
            val data = message.toString().toByteArray(Charsets.UTF_8)
            
            currentRoom.localParticipant.publishData(data)
            
            Log.d(TAG, "Agent mode toggled: $newState")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Errore toggle agent mode", e)
            _agentModeActive.value = !newState // Revert on error
            false
        }
    }
    
    /**
     * Start publishing audio from phone microphone
     */
    suspend fun startPublishingAudio(): Boolean {
        val currentRoom = room ?: run {
            Log.e(TAG, "Not connected to room")
            return false
        }
        
        return try {
            // Create local audio track from microphone
            localAudioTrack = currentRoom.localParticipant.createAudioTrack(
                name = "phone-microphone"
            )
            
            // Publish track
            currentRoom.localParticipant.publishAudioTrack(localAudioTrack!!)
            
            Log.d(TAG, "Started publishing audio track")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish audio: ${e.message}", e)
            false
        }
    }
    
    /**
     * Stop publishing audio track
     */
    fun stopPublishingAudio() {
        localAudioTrack?.let { track ->
            room?.localParticipant?.unpublishTrack(track)
            track.stop()
        }
        localAudioTrack = null
        Log.d(TAG, "Stopped publishing audio")
    }
    
    /**
     * Mute/unmute local audio
     */
    fun setAudioMuted(muted: Boolean) {
        localAudioTrack?.let { track ->
            scope.launch {
                room?.localParticipant?.setMicrophoneEnabled(!muted)
            }
        }
    }
    
    /**
     * Setup room event listeners
     */
    private fun setupRoomEventListeners() {
        eventCollectJob = scope.launch {
            room?.events?.collect { event ->
                when (event) {
                    is RoomEvent.Disconnected -> {
                        Log.d(TAG, "Room disconnected")
                        _connectionState.value = LiveKitConnectionState.DISCONNECTED
                    }
                    is RoomEvent.Reconnecting -> {
                        Log.d(TAG, "Room reconnecting")
                        _connectionState.value = LiveKitConnectionState.RECONNECTING
                    }
                    is RoomEvent.Reconnected -> {
                        Log.d(TAG, "Room reconnected")
                        _connectionState.value = LiveKitConnectionState.CONNECTED
                    }
                    is RoomEvent.TrackSubscribed -> {
                        Log.d(TAG, "Track subscribed: ${event.track.kind}, name: ${event.track.name}, participant: ${event.participant?.identity}")
                        when (event.track) {
                            is RemoteAudioTrack -> {
                                _remoteAudioTrack.value = event.track as RemoteAudioTrack
                                Log.d(TAG, "Remote audio track received from ${event.participant?.identity}")
                            }
                            is RemoteVideoTrack -> {
                                _remoteVideoTrack.value = event.track as RemoteVideoTrack
                                Log.d(TAG, "Remote video track received from ${event.participant?.identity}")
                            }
                            else -> {
                                Log.d(TAG, "Other track type subscribed: ${event.track.kind}")
                            }
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        Log.d(TAG, "Track unsubscribed: ${event.track.kind}")
                        when (event.track) {
                            is RemoteAudioTrack -> {
                                _remoteAudioTrack.value = null
                            }
                            is RemoteVideoTrack -> {
                                _remoteVideoTrack.value = null
                            }
                            else -> {
                                // Other track types
                            }
                        }
                    }
                    is RoomEvent.ParticipantConnected -> {
                        Log.d(TAG, "Participant connected: ${event.participant.identity}")
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        Log.d(TAG, "Participant disconnected: ${event.participant.identity}")
                    }
                    is RoomEvent.DataReceived -> {
                        Log.d(TAG, "Data received from: ${event.participant?.identity}")
                        handleDataMessage(event.data)
                    }
                    else -> {
                        // Handle other events if needed
                    }
                }
            }
        }
    }
    
    /**
     * Handle incoming data messages from server/agent
     */
    private fun handleDataMessage(data: ByteArray) {
        try {
            val message = String(data, Charsets.UTF_8)
            Log.d(TAG, "Received data message: $message")
            // Parse and handle message (e.g., transcript updates)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling data message: ${e.message}")
        }
    }
    
    /**
     * Send data message to room
     */
    suspend fun sendData(data: ByteArray) {
        room?.localParticipant?.publishData(data)
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
