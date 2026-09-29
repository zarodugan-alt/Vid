package com.example.ui.screens.finished

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.DownloadTask
import com.example.data.model.MediaType
import com.example.download.AppDownloadManager
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun AdvancedVideoPlayerDialog(
    task: DownloadTask,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var isEnded by remember { mutableStateOf(false) }

    // HUD overlays for gestures
    var gestureHudText by remember { mutableStateOf<String?>(null) }
    var gestureHudIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }

    // Seek feedback (+10s, -10s)
    var seekFeedbackText by remember { mutableStateOf<String?>(null) }

    // Playback speed
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    // Brightness state (0.0 to 1.0)
    val activity = context as? Activity
    var currentBrightness by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f)
    }

    // Auto-hide controls timer
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying && !isControlsLocked) {
            delay(3500)
            isControlsVisible = false
        }
    }

    // Progress update loop
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            videoViewRef?.let { vv ->
                currentPosition = vv.currentPosition
                duration = vv.duration.coerceAtLeast(0)
            }
            delay(300)
        }
    }

    val isAudioOnly = task.mediaType == MediaType.AUDIO

    DisposableEffect(Unit) {
        onDispose {
            try {
                videoViewRef?.stopPlayback()
            } catch (_: Exception) {}
            videoViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("advanced_player_dialog")
                .pointerInput(isControlsLocked) {
                    detectTapGestures(
                        onTap = {
                            if (!isControlsLocked) {
                                isControlsVisible = !isControlsVisible
                            } else {
                                isControlsVisible = true // allow unlocking
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!isControlsLocked) {
                                val halfWidth = size.width / 2
                                if (offset.x < halfWidth) {
                                    // Rewind 10s
                                    val newPos = (currentPosition - 10000).coerceAtLeast(0)
                                    videoViewRef?.seekTo(newPos)
                                    currentPosition = newPos
                                    seekFeedbackText = "-10s"
                                } else {
                                    // Fast forward 10s
                                    val newPos = (currentPosition + 10000).coerceAtMost(duration)
                                    videoViewRef?.seekTo(newPos)
                                    currentPosition = newPos
                                    seekFeedbackText = "+10s"
                                }
                            }
                        }
                    )
                }
                .pointerInput(isControlsLocked) {
                    if (isControlsLocked) return@pointerInput
                    detectVerticalDragGestures { change, dragAmount ->
                        val halfWidth = size.width / 2
                        if (change.position.x < halfWidth) {
                            // Left side: Brightness
                            val delta = -dragAmount / size.height
                            val newB = (currentBrightness + delta).coerceIn(0.05f, 1.0f)
                            currentBrightness = newB
                            activity?.let { act ->
                                val lp = act.window.attributes
                                lp.screenBrightness = newB
                                act.window.attributes = lp
                            }
                            gestureHudText = "${(newB * 100).toInt()}%"
                            gestureHudIcon = Icons.Default.BrightnessHigh
                        } else {
                            // Right side: Volume
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val volDelta = if (dragAmount < 0) 1 else -1
                            val newVol = (currentVol + volDelta).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            gestureHudText = "${(newVol * 100 / maxVol)}%"
                            gestureHudIcon = Icons.Default.VolumeUp
                        }
                    }
                }
        ) {
            // Dismiss HUD text after short delay
            LaunchedEffect(gestureHudText) {
                if (gestureHudText != null) {
                    delay(1200)
                    gestureHudText = null
                    gestureHudIcon = null
                }
            }

            LaunchedEffect(seekFeedbackText) {
                if (seekFeedbackText != null) {
                    delay(800)
                    seekFeedbackText = null
                }
            }

            // Media Player / Video View
            if (!isAudioOnly) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            videoViewRef = this
                            val uri = if (task.filePath != null && File(task.filePath).exists()) {
                                Uri.fromFile(File(task.filePath))
                            } else {
                                Uri.parse(task.url)
                            }
                            setVideoURI(uri)

                            setOnPreparedListener { mp ->
                                duration = mp.duration
                                mp.isLooping = false
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    try {
                                        mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
                                    } catch (_: Exception) {}
                                }
                                start()
                                isPlaying = true
                            }

                            setOnCompletionListener {
                                isPlaying = false
                                isEnded = true
                                isControlsVisible = true
                            }

                            setOnErrorListener { _, _, _ ->
                                isPlaying = false
                                true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Audio Player Mode with spinning vinyl
                val infiniteTransition = rememberInfiniteTransition(label = "audio_spin")
                val spinAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 6000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "vinyl_rotation"
                )

                // Invisible VideoView to play audio file
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            videoViewRef = this
                            val uri = if (task.filePath != null && File(task.filePath).exists()) {
                                Uri.fromFile(File(task.filePath))
                            } else {
                                Uri.parse(task.url)
                            }
                            setVideoURI(uri)
                            setOnPreparedListener { mp ->
                                duration = mp.duration
                                start()
                                isPlaying = true
                            }
                            setOnCompletionListener {
                                isPlaying = false
                                isEnded = true
                            }
                            setOnErrorListener { _, _, _ ->
                                isPlaying = false
                                true
                            }
                        }
                    },
                    modifier = Modifier.size(1.dp)
                )

                // Visual Vinyl Art
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .rotate(if (isPlaying) spinAngle else 0f)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color(0xFF1E293B),
                                        Color(0xFF0F172A),
                                        Color.Black
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Audio Track • ${task.quality}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )
                }
            }

            // HUD Gesture Overlay (Volume/Brightness)
            AnimatedVisibility(
                visible = gestureHudText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        gestureHudIcon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = gestureHudText ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Seek feedback indicator (+10s, -10s)
            AnimatedVisibility(
                visible = seekFeedbackText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = seekFeedbackText ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }

            // Fullscreen Controls Overlay
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("player_close_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!isControlsLocked) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Speed button
                                Box {
                                    IconButton(
                                        onClick = { showSpeedMenu = true },
                                        modifier = Modifier.testTag("player_speed_btn")
                                    ) {
                                        Text(
                                            text = "${currentSpeed}x",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSpeedMenu,
                                        onDismissRequest = { showSpeedMenu = false }
                                    ) {
                                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                            DropdownMenuItem(
                                                text = { Text("${speed}x") },
                                                onClick = {
                                                    currentSpeed = speed
                                                    showSpeedMenu = false
                                                    // Apply speed
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                                        try {
                                                            videoViewRef?.let {
                                                                // Reflect or apply playback params if possible
                                                            }
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                // Lock Controls
                                IconButton(
                                    onClick = { isControlsLocked = true },
                                    modifier = Modifier.testTag("player_lock_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LockOpen,
                                        contentDescription = "Lock",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Center Play/Pause button
                    if (!isControlsLocked) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(32.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val newPos = (currentPosition - 10000).coerceAtLeast(0)
                                    videoViewRef?.seekTo(newPos)
                                    currentPosition = newPos
                                    seekFeedbackText = "-10s"
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("player_rewind_10s_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FastRewind,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .testTag("player_play_pause_btn"),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isEnded) {
                                            videoViewRef?.seekTo(0)
                                            videoViewRef?.start()
                                            isPlaying = true
                                            isEnded = false
                                        } else if (isPlaying) {
                                            videoViewRef?.pause()
                                            isPlaying = false
                                        } else {
                                            videoViewRef?.start()
                                            isPlaying = true
                                        }
                                    },
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isEnded) Icons.Default.Replay else if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val newPos = (currentPosition + 10000).coerceAtMost(duration)
                                    videoViewRef?.seekTo(newPos)
                                    currentPosition = newPos
                                    seekFeedbackText = "+10s"
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("player_forward_10s_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FastForward,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    } else {
                        // If locked, show unlock button in center
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(16.dp)
                        ) {
                            IconButton(
                                onClick = { isControlsLocked = false },
                                modifier = Modifier.testTag("player_unlock_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Unlock",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // Bottom Bar: Timeline Scrubber & Timestamps
                    if (!isControlsLocked) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                                onValueChange = { frac ->
                                    val newPos = (frac * duration).toInt()
                                    videoViewRef?.seekTo(newPos)
                                    currentPosition = newPos
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("player_timeline_slider")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = AppDownloadManager.formatDuration(currentPosition / 1000L),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )

                                Text(
                                    text = AppDownloadManager.formatDuration(duration / 1000L),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
