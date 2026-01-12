/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom VideoCapturer that converts Bitmap frames to WebRTC VideoFrames
 * 
 * This capturer receives Bitmap frames from the DAT SDK camera stream
 * and converts them to I420 format for WebRTC transmission.
 */
class BitmapVideoCapturer(
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 480,
    private val targetFps: Int = 24
) : VideoCapturer {
    
    companion object {
        private const val TAG = "BitmapVideoCapturer"
    }
    
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val isRunning = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    private var frameCount = 0L
    private var lastFrameTime = System.nanoTime()
    private val frameIntervalNs = 1_000_000_000L / targetFps
    
    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        // #region agent log
        DebugLogger.log("B", "BitmapVideoCapturer.initialize", "Initializing", mapOf("capturerObserverNull" to (capturerObserver == null), "surfaceTextureHelperNull" to (surfaceTextureHelper == null)))
        // #endregion
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        
        // Create handler thread for frame processing
        handlerThread = HandlerThread("BitmapVideoCapturer").apply { start() }
        handler = Handler(handlerThread!!.looper)
        
        Log.d(TAG, "Initialized with ${targetWidth}x${targetHeight} @ ${targetFps}fps")
        // #region agent log
        DebugLogger.log("B", "BitmapVideoCapturer.initialize.done", "Initialized", mapOf("handlerNull" to (handler == null)))
        // #endregion
    }
    
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        // #region agent log
        DebugLogger.log("B", "BitmapVideoCapturer.startCapture", "startCapture called", mapOf("width" to width, "height" to height, "framerate" to framerate, "capturerObserverNull" to (capturerObserver == null)))
        // #endregion
        isRunning.set(true)
        Log.d(TAG, "Started capture")
        // #region agent log
        DebugLogger.log("B", "BitmapVideoCapturer.startCapture.done", "Capture started", mapOf("isRunning" to isRunning.get()))
        // #endregion
    }
    
    override fun stopCapture() {
        isRunning.set(false)
        Log.d(TAG, "Stopped capture")
    }
    
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "Change capture format: ${width}x${height} @ ${framerate}fps")
    }
    
    override fun dispose() {
        isRunning.set(false)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        capturerObserver = null
        surfaceTextureHelper = null
        Log.d(TAG, "Disposed")
    }
    
    override fun isScreencast(): Boolean = false
    
    /**
     * Called when a new Bitmap frame is available from DAT SDK
     */
    fun onBitmapFrame(bitmap: Bitmap) {
        // #region agent log
        DebugLogger.log("B", "BitmapVideoCapturer.onBitmapFrame", "Frame received", mapOf("isRunning" to isRunning.get(), "handlerNull" to (handler == null), "capturerObserverNull" to (capturerObserver == null)))
        // #endregion
        if (!isRunning.get()) {
            // #region agent log
            DebugLogger.log("B", "BitmapVideoCapturer.onBitmapFrame.skipped", "Frame SKIPPED - isRunning=false! startCapture() not called?", emptyMap())
            // #endregion
            return
        }
        
        handler?.post {
            try {
                // Rate limiting - skip frame if too soon
                val now = System.nanoTime()
                if (now - lastFrameTime < frameIntervalNs) {
                    return@post
                }
                lastFrameTime = now
                
                // Scale bitmap if needed
                val scaledBitmap = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                } else {
                    bitmap
                }
                
                // Convert bitmap to I420 buffer
                val i420Buffer = bitmapToI420Buffer(scaledBitmap)
                
                // Create video frame
                val timestampNs = System.nanoTime()
                val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
                
                // Send frame to WebRTC
                val observer = capturerObserver
                if (observer != null) {
                    observer.onFrameCaptured(videoFrame)
                    // #region agent log
                    if (frameCount % 30 == 0L) {
                        DebugLogger.log("B", "BitmapVideoCapturer.onBitmapFrame.sent", "Frame sent to WebRTC", mapOf("frameCount" to frameCount))
                    }
                    // #endregion
                } else {
                    // #region agent log
                    DebugLogger.log("B", "BitmapVideoCapturer.onBitmapFrame.error", "capturerObserver is NULL!", emptyMap())
                    // #endregion
                }
                
                // Release frame
                videoFrame.release()
                
                frameCount++
                if (frameCount % 100 == 0L) {
                    Log.d(TAG, "Sent $frameCount frames")
                }
                
                // Clean up scaled bitmap if we created a new one
                if (scaledBitmap !== bitmap) {
                    scaledBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame: ${e.message}", e)
            }
        }
    }
    
    /**
     * Convert Bitmap to I420 buffer for WebRTC
     */
    private fun bitmapToI420Buffer(bitmap: Bitmap): JavaI420Buffer {
        val width = bitmap.width
        val height = bitmap.height
        
        // Get pixels from bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Calculate buffer sizes
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        
        // Allocate buffers
        val yBuffer = ByteBuffer.allocateDirect(ySize)
        val uBuffer = ByteBuffer.allocateDirect(uvSize)
        val vBuffer = ByteBuffer.allocateDirect(uvSize)
        
        // Convert ARGB to I420 (YUV420P)
        convertArgbToI420(pixels, width, height, yBuffer, uBuffer, vBuffer)
        
        // Rewind buffers
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()
        
        // Create I420 buffer
        return JavaI420Buffer.wrap(
            width, height,
            yBuffer, width,
            uBuffer, width / 2,
            vBuffer, width / 2,
            null // No release callback needed for direct buffers
        )
    }
    
    /**
     * Convert ARGB pixels to I420 (YUV420P) format
     */
    private fun convertArgbToI420(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer
    ) {
        var yIndex = 0
        var uvIndex = 0
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argbPixels[j * width + i]
                
                // Extract RGB components
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // Convert to YUV using BT.601 coefficients
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                
                // Store Y value
                yBuffer.put(yIndex++, clamp(y).toByte())
                
                // Subsample U and V (4:2:0)
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    
                    uBuffer.put(uvIndex, clamp(u).toByte())
                    vBuffer.put(uvIndex, clamp(v).toByte())
                    uvIndex++
                }
            }
        }
    }
    
    /**
     * Clamp value to 0-255 range
     */
    private fun clamp(value: Int): Int {
        return when {
            value < 0 -> 0
            value > 255 -> 255
            else -> value
        }
    }
}

