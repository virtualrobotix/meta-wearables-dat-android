/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit

import android.hardware.camera2.CameraCharacteristics
import io.livekit.android.room.track.RemoteVideoTrack

/**
 * UI State for LiveKit connection and streaming
 */
data class LiveKitUiState(
    val connectionState: LiveKitConnectionState = LiveKitConnectionState.DISCONNECTED,
    val serverUrl: String = "ws://10.0.0.123:7880",
    val roomName: String = "voice-room",
    val participantName: String = "rayban-glasses",
    val isPublishingVideo: Boolean = false,
    val isPublishingAudio: Boolean = false,
    val isAudioMuted: Boolean = false,
    val hasRemoteAudioTrack: Boolean = false,
    val remoteVideoTrack: RemoteVideoTrack? = null,
    val isPublishingPhoneCamera: Boolean = false,
    val phoneCameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
    val agentModeActive: Boolean = false,
    val errorMessage: String? = null,
    val isConfigDialogVisible: Boolean = false,
    val isDebugDialogVisible: Boolean = false,
    val debugInfo: String = "",
    val phoneIpAddress: String = ""
) {
    val isConnected: Boolean
        get() = connectionState == LiveKitConnectionState.CONNECTED
    
    val canPublish: Boolean
        get() = isConnected
    
    val statusText: String
        get() = when (connectionState) {
            LiveKitConnectionState.DISCONNECTED -> "Disconnesso"
            LiveKitConnectionState.CONNECTING -> "Connessione..."
            LiveKitConnectionState.CONNECTED -> "Connesso a $roomName"
            LiveKitConnectionState.RECONNECTING -> "Riconnessione..."
            LiveKitConnectionState.ERROR -> errorMessage ?: "Errore"
        }
}

