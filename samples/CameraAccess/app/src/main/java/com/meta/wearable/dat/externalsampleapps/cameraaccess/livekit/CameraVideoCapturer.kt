/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoCapturer that captures video from phone camera using Camera2 API
 * 
 * Supports both front and back cameras
 */
class CameraVideoCapturer(
    private val context: Context,
    private val cameraFacing: Int, // CameraCharacteristics.LENS_FACING_BACK or FRONT
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 480,
    private val targetFps: Int = 24
) : VideoCapturer {
    
    companion object {
        private const val TAG = "CameraVideoCapturer"
    }
    
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val isRunning = AtomicBoolean(false)
    
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    private var frameCount = 0L
    
    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        this.cameraManager = (context ?: this.context).getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        
        // Create handler thread for camera operations
        handlerThread = HandlerThread("CameraVideoCapturer").apply { start() }
        handler = Handler(handlerThread!!.looper)
        
        Log.d(TAG, "Initialized with camera facing: $cameraFacing, ${targetWidth}x${targetHeight} @ ${targetFps}fps")
    }
    
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        // #region agent log
        DebugLogger.log("D", "CameraVideoCapturer.startCapture", "startCapture called", mapOf("isRunning" to isRunning.get(), "handlerNull" to (handler == null), "width" to width, "height" to height))
        // #endregion
        if (isRunning.get()) {
            Log.w(TAG, "Already capturing")
            return
        }
        
        Log.d(TAG, "Starting capture: ${width}x${height} @ ${framerate}fps")
        
        isRunning.set(true)
        
        handler?.post {
            try {
                // #region agent log
                DebugLogger.log("D", "CameraVideoCapturer.startCapture.openCamera", "Opening camera", emptyMap())
                // #endregion
                openCamera()
            } catch (e: SecurityException) {
                Log.e(TAG, "Camera permission denied in startCapture: ${e.message}", e)
                // #region agent log
                DebugLogger.log("D", "CameraVideoCapturer.startCapture.securityError", "SecurityException", mapOf("error" to (e.message ?: "unknown")))
                // #endregion
                isRunning.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture: ${e.message}", e)
                // #region agent log
                DebugLogger.log("D", "CameraVideoCapturer.startCapture.error", "Exception", mapOf("error" to (e.message ?: "unknown")))
                // #endregion
                isRunning.set(false)
            }
        }
    }
    
    override fun stopCapture() {
        if (!isRunning.get()) {
            return
        }
        
        isRunning.set(false)
        
        handler?.post {
            closeCamera()
        }
    }
    
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "Change capture format: ${width}x${height} @ ${framerate}fps")
        // Restart capture with new format
        if (isRunning.get()) {
            stopCapture()
            startCapture(width, height, framerate)
        }
    }
    
    override fun dispose() {
        stopCapture()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        capturerObserver = null
        surfaceTextureHelper = null
        cameraManager = null
        Log.d(TAG, "Disposed")
    }
    
    override fun isScreencast(): Boolean = false
    
    /**
     * Open camera device
     */
    private fun openCamera() {
        try {
            cameraId = getCameraId(cameraFacing)
            if (cameraId == null) {
                Log.e(TAG, "No camera found with facing: $cameraFacing")
                return
            }
            
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId!!)
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            
            // Find closest size to target
            val bestSize = sizes?.minByOrNull { 
                Math.abs(it.width - targetWidth) + Math.abs(it.height - targetHeight)
            } ?: Size(targetWidth, targetHeight)
            
            Log.d(TAG, "Opening camera $cameraId with size ${bestSize.width}x${bestSize.height}")
            
            // Create ImageReader for YUV frames
            imageReader = ImageReader.newInstance(
                bestSize.width,
                bestSize.height,
                ImageFormat.YUV_420_888,
                2 // Buffer count
            )
            
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let { processImage(it) }
            }, handler)
            
            // Open camera
            cameraManager?.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    closeCamera()
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    closeCamera()
                }
            }, handler)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
        }
    }
    
    /**
     * Create capture session
     */
    private fun createCaptureSession() {
        try {
            val camera = cameraDevice ?: return
            val reader = imageReader ?: return
            
            val surfaces = listOf(reader.surface)
            
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startRepeatingRequest()
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure capture session")
                    }
                },
                handler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session: ${e.message}", e)
        }
    }
    
    /**
     * Start repeating capture request
     */
    private fun startRepeatingRequest() {
        try {
            val camera = cameraDevice ?: return
            val session = captureSession ?: return
            
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            
            session.setRepeatingRequest(
                requestBuilder.build(),
                null,
                handler
            )
            
            Log.d(TAG, "Started repeating capture request")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start repeating request: ${e.message}", e)
        }
    }
    
    /**
     * Process captured image
     */
    private fun processImage(image: Image) {
        if (!isRunning.get()) {
            image.close()
            return
        }
        
        try {
            // Convert YUV_420_888 to I420
            val i420Buffer = yuv420888ToI420(image)
            
            // Create video frame
            val timestampNs = System.nanoTime()
            val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
            
            // Send frame to WebRTC
            capturerObserver?.onFrameCaptured(videoFrame)
            
            // Release frame
            videoFrame.release()
            
            frameCount++
            if (frameCount % 100 == 0L) {
                Log.d(TAG, "Sent $frameCount frames")
                // #region agent log
                DebugLogger.log("D", "CameraVideoCapturer.processImage", "Sent frames", mapOf("frameCount" to frameCount, "capturerObserverNull" to (capturerObserver == null)))
                // #endregion
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
        } finally {
            image.close()
        }
    }
    
    /**
     * Convert YUV_420_888 Image to I420 buffer
     */
    private fun yuv420888ToI420(image: Image): JavaI420Buffer {
        val width = image.width
        val height = image.height
        
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        // Allocate direct buffers
        val yDirect = ByteBuffer.allocateDirect(ySize)
        val uDirect = ByteBuffer.allocateDirect(uSize)
        val vDirect = ByteBuffer.allocateDirect(vSize)
        
        // Copy data
        yDirect.put(yBuffer)
        uDirect.put(uBuffer)
        vDirect.put(vBuffer)
        
        // Rewind
        yDirect.rewind()
        uDirect.rewind()
        vDirect.rewind()
        
        // Create I420 buffer
        return JavaI420Buffer.wrap(
            width, height,
            yDirect, yPlane.rowStride,
            uDirect, uPlane.rowStride,
            vDirect, vPlane.rowStride,
            null
        )
    }
    
    /**
     * Get camera ID for given facing direction
     */
    private fun getCameraId(facing: Int): String? {
        try {
            cameraManager?.cameraIdList?.forEach { id ->
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                if (characteristics?.get(CameraCharacteristics.LENS_FACING) == facing) {
                    return id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID: ${e.message}", e)
        }
        return null
    }
    
    /**
     * Close camera and release resources
     */
    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
            
            Log.d(TAG, "Camera closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}", e)
        }
    }
}
