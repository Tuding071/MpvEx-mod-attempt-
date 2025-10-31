package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vivvvek.seeker.Seeker
import dev.vivvvek.seeker.SeekerDefaults
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import android.net.Uri

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var showSeekTime by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pendingPauseResume by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    
    // Video progress for seekbar
    var currentPosition by remember { mutableStateOf(0.0) }
    var videoDuration by remember { mutableStateOf(1.0) }
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    
    // Simple draggable progress bar
    var isDragging by remember { mutableStateOf(false) }
    
    // Drag seeking variables
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // Debouncing
    var lastSeekTime by remember { mutableStateOf(0L) }
    val seekDebounceMs = 16L
    
    // Tap detection variables
    var leftTapStartTime by remember { mutableStateOf(0L) }
    var rightTapStartTime by remember { mutableStateOf(0L) }
    var leftIsHolding by remember { mutableStateOf(false) }
    var rightIsHolding by remember { mutableStateOf(false) }
    
    // Video title and filename state
    var showVideoInfo by remember { mutableStateOf(0) }
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Auto-hide timeout for seekbar
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Pause/Resume feedback
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // 480p downscaling during seeking
    var isDownscaled by remember { mutableStateOf(false) }
    var downscaleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Volume feedback
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // NEW: Combined gesture state
    var pendingTap by remember { mutableStateOf(false) }
    var pendingLongPress by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Volume feedback function
    val showVolumeFeedback: (Int) -> Unit = { volume ->
        volumeFeedbackJob?.cancel()
        showVolumeFeedbackState = true
        
        volumeFeedbackJob = coroutineScope.launch {
            delay(1000)
            showVolumeFeedbackState = false
        }
    }
    
    // Observe volume changes from ViewModel
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    // Function to revert to normal quality
    fun revertToNormalQuality() {
        if (isDownscaled) {
            MPVLib.setPropertyString("scale", "no")
            isDownscaled = false
        }
    }
    
    // Function to activate 480p downscaling for seeking
    fun activateSeekingMode() {
        if (!isDownscaled) {
            coroutineScope.launch {
                delay(30)
                MPVLib.setPropertyString("scale", "480")
                isDownscaled = true
            }
        }
        
        downscaleJob?.cancel()
        downscaleJob = coroutineScope.launch {
            delay(600)
            revertToNormalQuality()
        }
    }
    
    // Function to schedule seekbar hide
    fun scheduleSeekbarHide() {
        if (userInteracting) return
        
        hideSeekbarJob?.cancel()
        hideSeekbarJob = coroutineScope.launch {
            delay(4000)
            showSeekbar = false
        }
    }
    
    // Function to cancel auto-hide when user interacts
    fun cancelAutoHide() {
        userInteracting = true
        hideSeekbarJob?.cancel()
        
        coroutineScope.launch {
            delay(100)
            userInteracting = false
        }
    }
    
    // Show seekbar and cancel auto-hide
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        scheduleSeekbarHide()
    }
    
    // Show playback feedback
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackJob?.cancel()
        showPlaybackFeedback = true
        playbackFeedbackText = text
        
        playbackFeedbackJob = coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    // NEW: Handle merged tap gesture (seekbar toggle + pause/resume)
    fun handleMergedTap() {
        val currentPaused = MPVLib.getPropertyBoolean("pause") ?: false
        
        // Toggle pause/resume
        if (currentPaused) {
            coroutineScope.launch {
                val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                MPVLib.command("seek", currentPos.toString(), "absolute", "exact")
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
            showPlaybackFeedback("Resume")
        } else {
            MPVLib.setPropertyBoolean("pause", true)
            showPlaybackFeedback("Pause")
        }
        
        // Toggle seekbar visibility
        if (showSeekbar) {
            showSeekbar = false
        } else {
            showSeekbarWithTimeout()
        }
        
        isPausing = !currentPaused
    }
    
    // NEW: Handle long press for 2x speed
    fun handleLongPress() {
        isSpeedingUp = true
        MPVLib.setPropertyDouble("speed", 2.0)
    }
    
    // NEW: Cancel long press
    fun cancelLongPress() {
        longPressJob?.cancel()
        pendingLongPress = false
        isSpeedingUp = false
        MPVLib.setPropertyDouble("speed", 1.0)
    }
    
    // Get video filename from multiple sources
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        fileName = when {
            intent?.action == Intent.ACTION_SEND -> {
                getFileNameFromUri(intent.getParcelableExtra(Intent.EXTRA_STREAM), context)
            }
            intent?.action == Intent.ACTION_VIEW -> {
                getFileNameFromUri(intent.data, context)
            }
            else -> {
                getBestAvailableFileName(context)
            }
        }
        
        val title = MPVLib.getPropertyString("media-title") ?: "Video"
        videoTitle = title
        
        showVideoInfo = 1
        
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
        }
        
        scheduleSeekbarHide()
    }
    
    // Handle speed reset when not holding
    LaunchedEffect(leftIsHolding, rightIsHolding) {
        if (!leftIsHolding && !rightIsHolding && isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 1.0)
            isSpeedingUp = false
        }
    }
    
    // Handle speed transitions
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    // Software decoding optimization
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "4")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 150 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "60")
        MPVLib.setPropertyString("cache-secs", "60")
        MPVLib.setPropertyString("cache-pause", "no")
        MPVLib.setPropertyString("cache-initial", "0.5")
        
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-skipidct", "all")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
        
        MPVLib.setPropertyString("demuxer-max-back-bytes", "50M")
        MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
        
        MPVLib.setPropertyString("gpu-dumb-mode", "yes")
        MPVLib.setPropertyString("opengl-pbo", "yes")
        
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1:reconnect_at_eof=1:reconnect_streamed=1")
        MPVLib.setPropertyString("network-timeout", "30")
        
        MPVLib.setPropertyString("audio-client-name", "MPVEx-Software-4Core")
        MPVLib.setPropertyString("audio-samplerate", "auto")
        
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("video-aspect-override", "no")
    }
    
    // Update time and progress
    LaunchedEffect(Unit) {
        var lastSeconds = -1
        
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            val currentSeconds = currentPos.toInt()
            
            if (isSeeking) {
                currentTime = seekTargetTime
                totalTime = formatTimeSimple(duration)
            } else {
                if (currentSeconds != lastSeconds) {
                    currentTime = formatTimeSimple(currentPos)
                    totalTime = formatTimeSimple(duration)
                    lastSeconds = currentSeconds
                }
            }
            
            if (!isDragging) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            
            currentPosition = currentPos
            videoDuration = duration
            
            delay(500)
        }
    }
    
    // Function to toggle video info
    fun toggleVideoInfo() {
        showVideoInfo = if (showVideoInfo == 0) 1 else 0
        
        if (showVideoInfo != 0) {
            videoInfoJob?.cancel()
            videoInfoJob = coroutineScope.launch {
                delay(4000)
                showVideoInfo = 0
            }
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }
    
    // Simple real-time seeking function
    fun performRealTimeSeek(targetPosition: Double) {
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
    }
    
    // Simple draggable progress bar handler
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        activateSeekingMode()
        
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            lastSeekTime = 0L
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        
        isDragging = true
        seekbarPosition = newPosition
        
        val targetPosition = newPosition.toDouble()
        
        val now = System.currentTimeMillis()
        if (now - lastSeekTime >= seekDebounceMs) {
            performRealTimeSeek(targetPosition)
            lastSeekTime = now
        }
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
    }
    
    // Handle drag finished
    fun handleDragFinished() {
        isDragging = false
        
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
        
        isSeeking = false
        showSeekTime = false
        wasPlayingBeforeSeek = false
        
        scheduleSeekbarHide()
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // TOP 5% - Video info toggle area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        toggleVideoInfo()
                    }
                )
        )
        
        // MAIN GESTURE AREA - 80% Center (ALL GESTURES)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.95f)
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Cancel any pending tap/long press
                            cancelLongPress()
                            pendingTap = false
                            
                            // Activate seeking mode
                            cancelAutoHide()
                            activateSeekingMode()
                            
                            seekStartX = offset.x
                            seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                            isSeeking = true
                            showSeekTime = true
                            lastSeekTime = 0L
                            
                            if (wasPlayingBeforeSeek) {
                                MPVLib.setPropertyBoolean("pause", true)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            
                            if (isSeeking) {
                                val currentX = change.position.x
                                val deltaX = currentX - seekStartX
                                
                                val pixelsPerSecond = 3f / 0.033f
                                val timeDeltaSeconds = deltaX / pixelsPerSecond
                                
                                val newPositionSeconds = seekStartPosition + timeDeltaSeconds
                                val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                                val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
                                
                                val now = System.currentTimeMillis()
                                if (now - lastSeekTime >= seekDebounceMs) {
                                    performRealTimeSeek(clampedPosition)
                                    lastSeekTime = now
                                }
                                
                                seekTargetTime = formatTimeSimple(clampedPosition)
                                currentTime = formatTimeSimple(clampedPosition)
                            }
                        },
                        onDragEnd = {
                            if (isSeeking) {
                                // Calculate final position based on current drag state
                                val currentPos = MPVLib.getPropertyDouble("time-pos") ?: seekStartPosition
                                performRealTimeSeek(currentPos)
                                
                                if (wasPlayingBeforeSeek) {
                                    coroutineScope.launch {
                                        delay(100)
                                        MPVLib.setPropertyBoolean("pause", false)
                                    }
                                }
                                
                                isSeeking = false
                                showSeekTime = false
                                seekStartX = 0f
                                seekStartPosition = 0.0
                                wasPlayingBeforeSeek = false
                                
                                scheduleSeekbarHide()
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { 
                            // Start long press detection
                            pendingLongPress = true
                            longPressJob = coroutineScope.launch {
                                delay(300) // Long press threshold
                                if (pendingLongPress) {
                                    handleLongPress()
                                }
                            }
                        },
                        onTap = { 
                            // Cancel long press and handle tap
                            cancelLongPress()
                            handleMergedTap()
                        },
                        onLongPress = { 
                            // Long press handled separately
                            pendingLongPress = false
                        }
                    )
                }
        )
        
        // BOTTOM AREA - Times and Seekbar
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = (-1).dp) 
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Time display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "$currentTime / $totalTime",
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = 0.8f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    // Simple Draggable Progress Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    ) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        
        // VIDEO INFO - Left Side
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // FEEDBACK AREA - Top Center
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 80.dp)
        ) {
            when {
                showVolumeFeedbackState -> {
                    Text(
                        text = "Volume: ${(currentVolume.toFloat() / viewModel.maxVolume.toFloat() * 100).toInt()}%",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .background(Color.DarkGray.copy(alpha = 0.8f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                isSpeedingUp -> {
                    Text(
                        text = "2X",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .background(Color.DarkGray.copy(alpha = 0.8f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                showSeekTime -> {
                    Text(
                        text = seekTargetTime,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .background(Color.DarkGray.copy(alpha = 0.8f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                showPlaybackFeedback -> {
                    Text(
                        text = playbackFeedbackText,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .background(Color.DarkGray.copy(alpha = 0.8f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SimpleDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(24.dp)
    ) {
        // Background track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.Gray.copy(alpha = 0.6f))
        )
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.White)
        )
        
        // Draggable area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .align(Alignment.CenterStart)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val newPosition = (offset.x / size.width) * duration
                            onValueChange(newPosition.coerceIn(0f, duration))
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newPosition = (change.position.x / size.width) * duration
                            onValueChange(newPosition.coerceIn(0f, duration))
                        },
                        onDragEnd = {
                            onValueChangeFinished()
                        }
                    )
                }
        )
    }
}

// Helper functions (keep the same as original)
private fun formatTimeSimple(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}

private fun getFileNameFromUri(uri: Uri?, context: android.content.Context): String {
    if (uri == null) return getBestAvailableFileName(context)
    return when {
        uri.scheme == "file" -> {
            uri.lastPathSegment?.substringBeforeLast(".") ?: getBestAvailableFileName(context)
        }
        uri.scheme == "content" -> {
            getDisplayNameFromContentUri(uri, context) ?: getBestAvailableFileName(context)
        }
        uri.scheme in listOf("http", "https") -> {
            uri.lastPathSegment?.substringBeforeLast(".") ?: "Online Video"
        }
        else -> getBestAvailableFileName(context)
    }
}

private fun getDisplayNameFromContentUri(uri: Uri, context: android.content.Context): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex("_display_name")
                val displayName = if (displayNameIndex != -1) {
                    cursor.getString(displayNameIndex)?.substringBeforeLast(".")
                } else null
                displayName ?: uri.lastPathSegment?.substringBeforeLast(".")
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun getBestAvailableFileName(context: android.content.Context): String {
    val mediaTitle = MPVLib.getPropertyString("media-title")
    if (mediaTitle != null && mediaTitle != "Video" && mediaTitle.isNotBlank()) {
        return mediaTitle.substringBeforeLast(".")
    }
    val mpvPath = MPVLib.getPropertyString("path")
    if (mpvPath != null && mpvPath.isNotBlank()) {
        return mpvPath.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "Video" }
    }
    return "Video"
}
