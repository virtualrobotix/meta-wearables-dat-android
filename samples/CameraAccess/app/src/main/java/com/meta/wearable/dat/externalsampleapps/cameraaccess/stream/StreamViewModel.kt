/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo with LiveKit Integration
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> NV21 conversion)
// - Streaming video to LiveKit WebRTC server for AI agent interaction

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.DebugLogger
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val INITIAL_LIVEKIT_STATE = LiveKitUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  // LiveKit state
  private val _liveKitState = MutableStateFlow(INITIAL_LIVEKIT_STATE)
  val liveKitState: StateFlow<LiveKitUiState> = _liveKitState.asStateFlow()
  
  // LiveKit Manager
  val liveKitManager = LiveKitManager(application)

  private val streamTimer = StreamTimer()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var timerJob: Job? = null
  private var liveKitStateJob: Job? = null

  init {
    // Collect timer state
    timerJob =
        viewModelScope.launch {
          launch {
            streamTimer.timerMode.collect { mode -> _uiState.update { it.copy(timerMode = mode) } }
          }

          launch {
            streamTimer.remainingTimeSeconds.collect { seconds ->
              _uiState.update { it.copy(remainingTimeSeconds = seconds) }
            }
          }

          launch {
            streamTimer.isTimerExpired.collect { expired ->
              if (expired) {
                // Stop streaming and navigate back
                stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              }
            }
          }
        }
    
    // Collect LiveKit state
    liveKitStateJob = viewModelScope.launch {
      launch {
        liveKitManager.connectionState.collect { state ->
          _liveKitState.update { it.copy(connectionState = state) }
          // Auto-show debug dialog on error
          if (state == LiveKitConnectionState.ERROR) {
            _liveKitState.update { 
              it.copy(
                isDebugDialogVisible = true,
                debugInfo = liveKitManager.getLastDebugInfo(),
                phoneIpAddress = liveKitManager.getDeviceIpAddress()
              )
            }
          }
        }
      }
      launch {
        liveKitManager.errorMessage.collect { error ->
          _liveKitState.update { it.copy(errorMessage = error) }
        }
      }
      launch {
        liveKitManager.remoteAudioTrack.collect { track ->
          _liveKitState.update { it.copy(hasRemoteAudioTrack = track != null) }
        }
      }
      launch {
        liveKitManager.remoteVideoTrack.collect { track ->
          _liveKitState.update { it.copy(remoteVideoTrack = track) }
        }
      }
      launch {
        liveKitManager.debugInfo.collect { info ->
          _liveKitState.update { it.copy(debugInfo = info) }
        }
      }
      launch {
        liveKitManager.agentModeActive.collect { active ->
          _liveKitState.update { it.copy(agentModeActive = active) }
        }
      }
    }
    
    // Initialize phone IP
    _liveKitState.update { it.copy(phoneIpAddress = liveKitManager.getDeviceIpAddress()) }
  }

  fun startStream() {
    resetTimer()
    streamTimer.startTimer()
    videoJob?.cancel()
    stateJob?.cancel()
    val streamSession =
        Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
            )
            .also { streamSession = it }
    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } }
    stateJob =
        viewModelScope.launch {
          streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }

            // navigate back when state transitioned to STOPPED
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    streamSession?.close()
    streamSession = null
    streamTimer.stopTimer()
    _uiState.update { INITIAL_STATE }
    
    // Also stop LiveKit publishing
    liveKitManager.stopPublishingVideo()
    liveKitManager.stopPublishingAudio()
    _liveKitState.update { it.copy(isPublishingVideo = false, isPublishingAudio = false) }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        streamSession
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure {
              Log.e(TAG, "Photo capture failed")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  fun cycleTimerMode() {
    streamTimer.cycleTimerMode()
    if (_uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      streamTimer.startTimer()
    }
  }

  fun resetTimer() {
    streamTimer.resetTimer()
  }

  // =====================================================
  // LiveKit Integration Methods
  // =====================================================
  
  /**
   * Update LiveKit server configuration
   */
  fun updateLiveKitConfig(serverUrl: String, roomName: String, participantName: String) {
    _liveKitState.update { 
      it.copy(
        serverUrl = serverUrl,
        roomName = roomName,
        participantName = participantName
      )
    }
  }
  
  /**
   * Show/hide LiveKit config dialog
   */
  fun showLiveKitConfigDialog() {
    _liveKitState.update { it.copy(isConfigDialogVisible = true) }
  }
  
  fun hideLiveKitConfigDialog() {
    _liveKitState.update { it.copy(isConfigDialogVisible = false) }
  }
  
  /**
   * Show/hide debug dialog
   */
  fun showDebugDialog() {
    _liveKitState.update { 
      it.copy(
        isDebugDialogVisible = true,
        debugInfo = liveKitManager.getLastDebugInfo(),
        phoneIpAddress = liveKitManager.getDeviceIpAddress()
      )
    }
  }
  
  fun hideDebugDialog() {
    _liveKitState.update { it.copy(isDebugDialogVisible = false) }
  }
  
  /**
   * Connect to LiveKit server
   */
  fun connectToLiveKit() {
    val state = _liveKitState.value
    
    // Configure LiveKit with default dev credentials
    liveKitManager.configure(
      serverUrl = state.serverUrl,
      apiKey = "devkey",
      apiSecret = "secret_dev_key_change_in_production"
    )
    
    viewModelScope.launch {
      val connected = liveKitManager.connect(
        roomName = state.roomName,
        participantName = state.participantName
      )
      
      if (connected) {
        Log.d(TAG, "Connected to LiveKit")
        // Don't automatically start publishing - let user choose which video source
      } else {
        Log.e(TAG, "Failed to connect to LiveKit")
      }
    }
  }
  
  /**
   * Disconnect from LiveKit
   */
  fun disconnectFromLiveKit() {
    liveKitManager.disconnect()
    _liveKitState.update { 
      it.copy(
        isPublishingVideo = false,
        isPublishingAudio = false
      )
    }
  }
  
  /**
   * Start publishing video to LiveKit
   */
  fun startPublishingVideo() {
    viewModelScope.launch {
      val success = liveKitManager.startPublishingVideo(
        width = 640,
        height = 480,
        fps = 24
      )
      _liveKitState.update { it.copy(isPublishingVideo = success) }
    }
  }
  
  /**
   * Stop publishing video to LiveKit
   */
  fun stopPublishingVideo() {
    liveKitManager.stopPublishingVideo()
    _liveKitState.update { it.copy(isPublishingVideo = false) }
  }
  
  /**
   * Start publishing audio to LiveKit
   */
  fun startPublishingAudio() {
    viewModelScope.launch {
      val success = liveKitManager.startPublishingAudio()
      _liveKitState.update { it.copy(isPublishingAudio = success) }
    }
  }
  
  /**
   * Stop publishing audio to LiveKit
   */
  fun stopPublishingAudio() {
    liveKitManager.stopPublishingAudio()
    _liveKitState.update { it.copy(isPublishingAudio = false) }
  }
  
  /**
   * Toggle audio mute
   */
  fun toggleAudioMute() {
    val newMuted = !_liveKitState.value.isAudioMuted
    liveKitManager.setAudioMuted(newMuted)
    _liveKitState.update { it.copy(isAudioMuted = newMuted) }
  }
  
  /**
   * Start publishing phone camera video to LiveKit
   */
  fun startPublishingPhoneCamera(cameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK) {
    viewModelScope.launch {
      val success = liveKitManager.startPublishingPhoneCamera(cameraFacing)
      _liveKitState.update { 
        it.copy(
          isPublishingPhoneCamera = success,
          phoneCameraFacing = if (success) cameraFacing else it.phoneCameraFacing
        ) 
      }
    }
  }
  
  /**
   * Stop publishing phone camera video to LiveKit
   */
  fun stopPublishingPhoneCamera() {
    liveKitManager.stopPublishingPhoneCamera()
    _liveKitState.update { it.copy(isPublishingPhoneCamera = false) }
  }
  
  /**
   * Switch phone camera between front and back
   */
  fun switchPhoneCamera() {
    viewModelScope.launch {
      val success = liveKitManager.switchPhoneCamera()
      if (success) {
        val newFacing = liveKitManager.getCurrentCameraFacing()
        _liveKitState.update { 
          it.copy(phoneCameraFacing = newFacing) 
        }
      }
    }
  }
  
  /**
   * Toggle agent mode
   */
  fun toggleAgentMode() {
    viewModelScope.launch {
      liveKitManager.toggleAgentMode()
      // State will be updated via StateFlow collection
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // #region agent log
    DebugLogger.log("A", "StreamViewModel.handleVideoFrame", "Frame received", mapOf("isPublishingVideo" to _liveKitState.value.isPublishingVideo, "isPublishingPhoneCamera" to _liveKitState.value.isPublishingPhoneCamera))
    // #endregion
    // VideoFrame contains raw I420 video data in a ByteBuffer
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    // Save current position
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    // Restore position
    buffer.position(originalPosition)

    // Convert I420 to NV21 format which is supported by Android's YuvImage
    val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
    val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
    val out =
        ByteArrayOutputStream().use { stream ->
          image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
          stream.toByteArray()
        }

    val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
    _uiState.update { it.copy(videoFrame = bitmap) }
    
    // Send frame to LiveKit if publishing
    // #region agent log
    DebugLogger.log("A", "StreamViewModel.handleVideoFrame.sendCheck", "Checking send", mapOf("isPublishingVideo" to _liveKitState.value.isPublishingVideo, "bitmapNull" to (bitmap == null)))
    // #endregion
    if (_liveKitState.value.isPublishingVideo && bitmap != null) {
      // #region agent log
      DebugLogger.log("A", "StreamViewModel.handleVideoFrame.sending", "Sending frame to LiveKit", emptyMap())
      // #endregion
      liveKitManager.sendVideoFrame(bitmap)
    }
  }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
    timerJob?.cancel()
    liveKitStateJob?.cancel()
    streamTimer.cleanup()
    liveKitManager.cleanup()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
