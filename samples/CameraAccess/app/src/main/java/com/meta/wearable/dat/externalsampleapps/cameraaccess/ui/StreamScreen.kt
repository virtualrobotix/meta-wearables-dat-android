/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI with LiveKit Integration
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices, handle photo capture, and stream to LiveKit WebRTC.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import io.livekit.android.room.track.RemoteVideoTrack
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.DebugLogger
import com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val liveKitState by streamViewModel.liveKitState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  
  // State for debug log dialog
  var isDebugLogDialogVisible by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  Box(modifier = modifier.fillMaxSize()) {
    // If we have remote video, show it full screen, otherwise show local video
    if (liveKitState.remoteVideoTrack != null) {
      // Remote video from other participants (full screen)
      RemoteVideoView(
          track = liveKitState.remoteVideoTrack!!,
          modifier = Modifier.fillMaxSize()
      )
      
      // Local video from glasses (picture-in-picture in top-right corner)
      streamUiState.videoFrame?.let { videoFrame ->
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = "Local video",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(120.dp, 90.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
      }
    } else {
      // No remote video, show local video full screen
      streamUiState.videoFrame?.let { videoFrame ->
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    // LiveKit status bar at top
    LiveKitStatusBar(
        liveKitState = liveKitState,
        onSettingsClick = { streamViewModel.showLiveKitConfigDialog() },
        onDebugLogClick = { isDebugLogDialogVisible = true },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )

    // Bottom controls
    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Column(
          modifier = Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // LiveKit controls row
        LiveKitControlsRow(
            liveKitState = liveKitState,
            onConnectClick = { 
              if (liveKitState.isConnected) {
                streamViewModel.disconnectFromLiveKit()
              } else {
                streamViewModel.connectToLiveKit()
              }
            },
            onGlassesVideoClick = {
              if (liveKitState.isPublishingVideo) {
                streamViewModel.stopPublishingVideo()
              } else {
                streamViewModel.startPublishingVideo()
              }
            },
            onPhoneCameraClick = {
              if (liveKitState.isPublishingPhoneCamera) {
                streamViewModel.stopPublishingPhoneCamera()
              } else {
                streamViewModel.startPublishingPhoneCamera()
              }
            },
            onSwitchCameraClick = {
              streamViewModel.switchPhoneCamera()
            },
            onAudioClick = {
              if (liveKitState.isPublishingAudio) {
                streamViewModel.stopPublishingAudio()
              } else {
                streamViewModel.startPublishingAudio()
              }
            },
            onMuteClick = { streamViewModel.toggleAudioMute() },
            onAgentClick = { streamViewModel.toggleAgentMode() }
        )
        
        // Original controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          SwitchButton(
              label = stringResource(R.string.stop_stream_button_title),
              onClick = {
                streamViewModel.disconnectFromLiveKit()
                streamViewModel.stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              },
              isDestructive = true,
              modifier = Modifier.weight(1f),
          )

          // Timer button
          TimerButton(
              timerMode = streamUiState.timerMode,
              onClick = { streamViewModel.cycleTimerMode() },
          )
          // Photo capture button
          CaptureButton(
              onClick = { streamViewModel.capturePhoto() },
          )
        }
      }
    }

    // Countdown timer display
    streamUiState.remainingTimeSeconds?.let { seconds ->
      val minutes = seconds / 60
      val remainingSeconds = seconds % 60
      Text(
          text = stringResource(id = R.string.time_remaining, minutes, remainingSeconds),
          color = Color.White,
          modifier = Modifier
              .align(Alignment.BottomCenter)
              .navigationBarsPadding()
              .padding(bottom = 140.dp),
          textAlign = TextAlign.Center,
      )
    }
  }

  // Share photo dialog
  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
  
  // LiveKit config dialog
  if (liveKitState.isConfigDialogVisible) {
    LiveKitConfigDialog(
        currentUrl = liveKitState.serverUrl,
        currentRoom = liveKitState.roomName,
        currentParticipant = liveKitState.participantName,
        onDismiss = { streamViewModel.hideLiveKitConfigDialog() },
        onSave = { url, room, participant ->
          streamViewModel.updateLiveKitConfig(url, room, participant)
          streamViewModel.hideLiveKitConfigDialog()
        }
    )
  }
  
  // Debug dialog (shows automatically on connection error)
  if (liveKitState.isDebugDialogVisible) {
    DebugInfoDialog(
        debugInfo = liveKitState.debugInfo,
        phoneIp = liveKitState.phoneIpAddress,
        serverUrl = liveKitState.serverUrl,
        onDismiss = { streamViewModel.hideDebugDialog() }
    )
  }
  
  // Debug log viewer dialog
  if (isDebugLogDialogVisible) {
    DebugLogViewerDialog(
        onDismiss = { isDebugLogDialogVisible = false },
        context = context
    )
  }
}

@Composable
private fun LiveKitStatusBar(
    liveKitState: com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitUiState,
    onSettingsClick: () -> Unit,
    onDebugLogClick: () -> Unit,
    modifier: Modifier = Modifier
) {
  Row(
      modifier = modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color.Black.copy(alpha = 0.7f))
          .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
  ) {
    // Connection status
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      // Status indicator
      Box(
          modifier = Modifier
              .size(12.dp)
              .clip(CircleShape)
              .background(
                  when (liveKitState.connectionState) {
                    LiveKitConnectionState.CONNECTED -> AppColor.Green
                    LiveKitConnectionState.CONNECTING,
                    LiveKitConnectionState.RECONNECTING -> AppColor.Yellow
                    LiveKitConnectionState.ERROR -> AppColor.Red
                    LiveKitConnectionState.DISCONNECTED -> Color.Gray
                  }
              )
      )
      
      Column {
        Text(
            text = "LiveKit",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = liveKitState.statusText,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
      }
    }
    
    // Publishing status icons
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
      if (liveKitState.isPublishingVideo) {
        Icon(
            imageVector = Icons.Default.Videocam,
            contentDescription = "Video occhiale",
            tint = AppColor.Green,
            modifier = Modifier.size(18.dp)
        )
      }
      if (liveKitState.isPublishingPhoneCamera) {
        Icon(
            imageVector = Icons.Default.Camera,
            contentDescription = "Fotocamera telefono",
            tint = AppColor.Green,
            modifier = Modifier.size(18.dp)
        )
      }
      if (liveKitState.isPublishingAudio) {
        Icon(
            imageVector = if (liveKitState.isAudioMuted) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = "Audio publishing",
            tint = if (liveKitState.isAudioMuted) AppColor.Yellow else AppColor.Green,
            modifier = Modifier.size(18.dp)
        )
      }
      if (liveKitState.agentModeActive) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = "Agente attivo",
            tint = AppColor.Green,
            modifier = Modifier.size(18.dp)
        )
      }
      if (liveKitState.hasRemoteAudioTrack) {
        Text(
            text = "🔊",
            fontSize = 14.sp
        )
      }
      
      // Debug log button
      IconButton(
          onClick = onDebugLogClick,
          modifier = Modifier.size(32.dp)
      ) {
        Icon(
            imageVector = Icons.Default.BugReport,
            contentDescription = "Debug Log",
            tint = AppColor.Yellow,
            modifier = Modifier.size(20.dp)
        )
      }
      
      // Settings button
      IconButton(
          onClick = onSettingsClick,
          modifier = Modifier.size(32.dp)
      ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "LiveKit Settings",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
      }
    }
  }
}

@Composable
private fun LiveKitControlsRow(
    liveKitState: com.meta.wearable.dat.externalsampleapps.cameraaccess.livekit.LiveKitUiState,
    onConnectClick: () -> Unit,
    onGlassesVideoClick: () -> Unit,
    onPhoneCameraClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onAudioClick: () -> Unit,
    onMuteClick: () -> Unit,
    onAgentClick: () -> Unit,
    modifier: Modifier = Modifier
) {
  Column(
      modifier = modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    // First row: Connect button
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
      // Connect/Disconnect button
      Button(
          onClick = onConnectClick,
          colors = ButtonDefaults.buttonColors(
              containerColor = if (liveKitState.isConnected) AppColor.Red else AppColor.DeepBlue
          ),
          modifier = Modifier.weight(1f)
      ) {
        Icon(
            imageVector = if (liveKitState.isConnected) Icons.Default.WifiOff else Icons.Default.Wifi,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (liveKitState.isConnected) "Disconnetti" else "Connetti AI",
            fontSize = 12.sp
        )
      }
    }
    
    // Second row: Video sources (Glasses and Phone Camera)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
      // Glasses video button with label
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.weight(1f)
      ) {
        IconButton(
            onClick = onGlassesVideoClick,
            enabled = liveKitState.isConnected,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (liveKitState.isPublishingVideo) AppColor.Green.copy(alpha = 0.8f)
                    else Color.Gray.copy(alpha = 0.5f)
                )
        ) {
          Icon(
              imageVector = if (liveKitState.isPublishingVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
              contentDescription = "Occhiale",
              tint = Color.White,
              modifier = Modifier.size(28.dp)
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Occhiale",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = if (liveKitState.isPublishingVideo) FontWeight.Bold else FontWeight.Normal
        )
      }
      
      // Phone camera button with label
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.weight(1f)
      ) {
        IconButton(
            onClick = onPhoneCameraClick,
            enabled = liveKitState.isConnected,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (liveKitState.isPublishingPhoneCamera) AppColor.Green.copy(alpha = 0.8f)
                    else Color.Gray.copy(alpha = 0.5f)
                )
        ) {
          Icon(
              imageVector = Icons.Default.Camera,
              contentDescription = "Fotocamera",
              tint = Color.White,
              modifier = Modifier.size(28.dp)
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Telefono",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = if (liveKitState.isPublishingPhoneCamera) FontWeight.Bold else FontWeight.Normal
        )
      }
      
      // Agent button with label
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.weight(1f)
      ) {
        IconButton(
            onClick = onAgentClick,
            enabled = liveKitState.isConnected,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (liveKitState.agentModeActive) AppColor.Green.copy(alpha = 0.8f)
                    else Color.Gray.copy(alpha = 0.5f)
                )
        ) {
          Icon(
              imageVector = Icons.Default.SmartToy,
              contentDescription = "Agente",
              tint = Color.White,
              modifier = Modifier.size(28.dp)
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Agente",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = if (liveKitState.agentModeActive) FontWeight.Bold else FontWeight.Normal
        )
      }
    }
    
    // Third row: Switch camera (if phone camera active), Audio, Mute
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
      // Switch camera button (only when phone camera is active)
      if (liveKitState.isPublishingPhoneCamera) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
          IconButton(
              onClick = onSwitchCameraClick,
              modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape)
                  .background(Color.Gray.copy(alpha = 0.5f))
          ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Cambia fotocamera",
                tint = Color.White
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = if (liveKitState.phoneCameraFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) 
                  "Posteriore" else "Frontale",
              color = Color.White,
              fontSize = 9.sp
          )
        }
      } else {
        Spacer(modifier = Modifier.weight(1f))
      }
      
      // Audio publish button
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.weight(1f)
      ) {
        IconButton(
            onClick = onAudioClick,
            enabled = liveKitState.isConnected,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (liveKitState.isPublishingAudio) AppColor.Green.copy(alpha = 0.8f)
                    else Color.Gray.copy(alpha = 0.5f)
                )
        ) {
          Icon(
              imageVector = if (liveKitState.isPublishingAudio) Icons.Default.Mic else Icons.Default.MicOff,
              contentDescription = "Toggle audio",
              tint = Color.White
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Audio",
            color = Color.White,
            fontSize = 10.sp
        )
      }
      
      // Mute button (only when audio is publishing)
      if (liveKitState.isPublishingAudio) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
          IconButton(
              onClick = onMuteClick,
              modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape)
                  .background(
                      if (liveKitState.isAudioMuted) AppColor.Yellow.copy(alpha = 0.8f)
                      else Color.Transparent
                  )
          ) {
            Icon(
                imageVector = if (liveKitState.isAudioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Toggle mute",
                tint = if (liveKitState.isAudioMuted) Color.White else Color.White.copy(alpha = 0.5f)
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = if (liveKitState.isAudioMuted) "Mutato" else "Attivo",
              color = Color.White,
              fontSize = 9.sp
          )
        }
      }
    }
  }
}

@Composable
private fun LiveKitConfigDialog(
    currentUrl: String,
    currentRoom: String,
    currentParticipant: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
  var serverUrl by remember { mutableStateOf(currentUrl) }
  var roomName by remember { mutableStateOf(currentRoom) }
  var participantName by remember { mutableStateOf(currentParticipant) }
  
  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text("Configurazione LiveKit")
      },
      text = {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          OutlinedTextField(
              value = serverUrl,
              onValueChange = { serverUrl = it },
              label = { Text("Server URL") },
              placeholder = { Text("ws://192.168.1.100:7880") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
          )
          
          OutlinedTextField(
              value = roomName,
              onValueChange = { roomName = it },
              label = { Text("Nome Room") },
              placeholder = { Text("glasses-room") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
          )
          
          OutlinedTextField(
              value = participantName,
              onValueChange = { participantName = it },
              label = { Text("Nome Partecipante") },
              placeholder = { Text("rayban-user") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        Button(
            onClick = { onSave(serverUrl, roomName, participantName) }
        ) {
          Text("Salva")
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text("Annulla")
        }
      }
  )
}

@Composable
private fun DebugInfoDialog(
    debugInfo: String,
    phoneIp: String,
    serverUrl: String,
    onDismiss: () -> Unit
) {
  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text("🔍 Debug Info", fontWeight = FontWeight.Bold)
        }
      },
      text = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Quick summary
          Text(
              text = "📱 IP Telefono: $phoneIp",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = AppColor.DeepBlue
          )
          Text(
              text = "🖥️ Server: $serverUrl",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = AppColor.DeepBlue
          )
          
          Spacer(modifier = Modifier.height(8.dp))
          
          // Detailed debug info
          Text(
              text = if (debugInfo.isNotEmpty()) debugInfo else "Nessuna informazione di debug disponibile.\nProva a premere 'Connetti AI' per vedere i dettagli.",
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
              lineHeight = 16.sp
          )
          
          Spacer(modifier = Modifier.height(16.dp))
          
          // Tips
          Text(
              text = "💡 Suggerimenti:",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
          )
          Text(
              text = """
• Verifica che il telefono sia sulla stessa rete WiFi del server
• L'IP del telefono dovrebbe iniziare con lo stesso prefisso del server (es. 10.0.0.x)
• Prova a fare ping dal server verso l'IP del telefono
• Verifica che la porta 8080 sia aperta sul server
              """.trimIndent(),
              fontSize = 11.sp,
              color = Color.Gray
          )
        }
      },
      confirmButton = {
        Button(onClick = onDismiss) {
          Text("Chiudi")
        }
      }
  )
}

/**
 * Composable for rendering remote video track from LiveKit
 */
@Composable
fun RemoteVideoView(
    track: RemoteVideoTrack,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }
    
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setMirror(false)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        },
        modifier = modifier,
        update = { view ->
            // Remove any existing renderer first
            try {
                track.removeRenderer(view)
            } catch (e: Exception) {
                // Ignore if not already attached
            }
            // Add renderer
            track.addRenderer(view)
        },
        onRelease = { view ->
            try {
                track.removeRenderer(view)
            } catch (e: Exception) {
                // Ignore errors during cleanup
            }
            view.release()
        }
    )
    
    DisposableEffect(track) {
        onDispose {
            eglBase.release()
        }
    }
}

/**
 * Dialog for viewing and sharing debug logs
 * Limited to last 50 lines for WhatsApp sharing
 */
@Composable
private fun DebugLogViewerDialog(
    onDismiss: () -> Unit,
    context: Context
) {
    var logContent by remember { mutableStateOf("Caricamento log...") }
    var logStats by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // Read log file using DebugLogger
    LaunchedEffect(refreshTrigger) {
        // Initialize logger if needed
        DebugLogger.init(context)
        logContent = DebugLogger.readLogs().ifEmpty { 
            "Nessun log di debug.\n\nI log vengono creati quando:\n- Attivi video occhiali\n- Attivi fotocamera telefono\n- Invii frame a LiveKit"
        }
        logStats = DebugLogger.getLogStats()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "🔧 Debug Log",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = logStats,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                Row {
                    // Clear logs button
                    IconButton(
                        onClick = { 
                            DebugLogger.clearLogs()
                            refreshTrigger++
                            Toast.makeText(context, "Log cancellati", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Cancella",
                            tint = AppColor.Red
                        )
                    }
                    // Refresh button
                    IconButton(
                        onClick = { refreshTrigger++ }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Aggiorna",
                            tint = AppColor.DeepBlue
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
            ) {
                // Log content
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.9f))
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = logContent,
                            color = Color.Green,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Info text
                Text(
                    text = "⚠️ WhatsApp invia solo ultimi 50 log",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Copy to clipboard (last 50)
                    Button(
                        onClick = {
                            val lastLogs = DebugLogger.readLastLogs(50)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Debug Log", lastLogs))
                            Toast.makeText(context, "Ultimi 50 log copiati!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColor.DeepBlue),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Copia", fontSize = 11.sp)
                    }
                    
                    // Share via WhatsApp (last 50 logs only)
                    Button(
                        onClick = {
                            val lastLogs = DebugLogger.readLastLogs(50)
                            shareLogToWhatsApp(context, lastLogs)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), // WhatsApp green
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("WhatsApp", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Chiudi")
            }
        }
    )
}

/**
 * Share log content via WhatsApp (limited content)
 */
private fun shareLogToWhatsApp(context: Context, logContent: String) {
    try {
        // Limit total message size for WhatsApp
        val maxLength = 4000
        val truncatedLog = if (logContent.length > maxLength) {
            "... (troncato)\n" + logContent.takeLast(maxLength)
        } else {
            logContent
        }
        
        // Format log for sharing
        val formattedLog = "📱 *DEBUG LOG*\n" +
                "⏰ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n\n" +
                truncatedLog
        
        // Try WhatsApp first
        val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, formattedLog)
        }
        
        try {
            context.startActivity(whatsappIntent)
        } catch (e: Exception) {
            // WhatsApp not installed, use generic share
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, formattedLog)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Condividi Debug Log"))
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Errore condivisione: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
