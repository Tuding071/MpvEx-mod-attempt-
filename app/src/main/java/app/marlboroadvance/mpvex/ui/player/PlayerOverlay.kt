package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import `is`.xyz.mpv.MPVLib
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import android.net.Uri
import kotlin.math.abs

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
    var showSeekbar by remember { mutableStateOf(true) }
    
    var currentPosition by remember { mutableStateOf(0.0) }
    var videoDuration by remember { mutableStateOf(1.0) }
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    
    var isDragging by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // FRAME SCRUBBING WITH INTELLIGENT DEBOUNCING
    var isFrameScrubbing by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableStateOf(0) }
    var totalFrames by remember { mutableStateOf(0) }
    var videoFPS by remember { mutableStateOf(30.0) }
    
    // DEBOUNCING SYSTEM VARIABLES
    var lastFrameCommandTime by remember { mutableStateOf(0L) }
    var pendingFrameCommand by remember { mutableStateOf<FrameCommand?>(null) }
    var frameCommandJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // VELOCITY TRACKING
    var lastTouchTime by remember { mutableStateOf(0L) }
    var lastTouchX by remember { mutableStateOf(0f) }
    var currentVelocity by remember { mutableStateOf(0f) }
    
    // CONSTANTS FOR DEBOUNCING AND THROTTLING
    val HARD_DEBOUNCE_MS = 20L        // Minimum time between commands
    val VELOCITY_WINDOW_MS = 100L     // Time window for velocity calculation
    val MAX_FRAMES_PER_COMMAND = 10   // Maximum frames to skip in one command
    val PIXELS_PER_FRAME_BASE = 30f   // Base sensitivity
    
    // GESTURE STATES
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // THRESHOLDS
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val maxVerticalMovement = 50f
    
    var showVideoInfo by remember { mutableStateOf(0) }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // FRAME COMMAND DATA CLASS
    data class FrameCommand(
        val direction: Int, // 1 for forward, -1 for backward
        val frameCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // UTILITY FUNCTIONS
    fun scheduleSeekbarHide() {
        if (userInteracting) return
        hideSeekbarJob?.cancel()
        hideSeekbarJob = coroutineScope.launch {
            delay(4000)
            showSeekbar = false
        }
    }
    
    fun cancelAutoHide() {
        userInteracting = true
        hideSeekbarJob?.cancel()
        coroutineScope.launch {
            delay(100)
            userInteracting = false
        }
    }
    
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        scheduleSeekbarHide()
    }
    
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackJob?.cancel()
        showPlaybackFeedback = true
        playbackFeedbackText = text
        playbackFeedbackJob = coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    // FRAME MANAGEMENT FUNCTIONS
    fun calculateFrameFromTime(time: Double): Int {
        return (time * videoFPS).toInt()
    }
    
    fun calculateTimeFromFrame(frame: Int): Double {
        return frame / videoFPS
    }
    
    fun seekToExactFrame(frame: Int) {
        val targetTime = calculateTimeFromFrame(frame)
        MPVLib.command("seek", targetTime.toString(), "absolute", "exact")
        currentFrame = frame.coerceIn(0, totalFrames)
    }
    
    // VELOCITY-BASED FRAME CALCULATION
    fun calculateFramesFromVelocity(deltaPixels: Float, deltaTime: Long): Int {
        if (deltaTime == 0L) return 1
        
        val pixelsPerSecond = (deltaPixels / deltaTime) * 1000
        val baseFrames = (abs(pixelsPerSecond) / PIXELS_PER_FRAME_BASE).toInt()
        
        // Apply non-linear scaling for better feel
        return when {
            baseFrames <= 1 -> 1
            baseFrames <= 3 -> 2
            baseFrames <= 6 -> 3
            baseFrames <= 10 -> 5
            else -> MAX_FRAMES_PER_COMMAND
        }.coerceIn(1, MAX_FRAMES_PER_COMMAND)
    }
    
    // INTELLIGENT DEBOUNCING SYSTEM
    fun executeFrameCommand(command: FrameCommand) {
        val now = System.currentTimeMillis()
        
        // HARD DEBOUNCE CHECK - reject if within minimum time window
        if (now - lastFrameCommandTime < HARD_DEBOUNCE_MS) {
            return
        }
        
        // Cancel any pending command (we have a newer one)
        frameCommandJob?.cancel()
        pendingFrameCommand = null
        
        // Execute the command immediately
        val targetFrame = currentFrame + (command.direction * command.frameCount)
        val clampedFrame = targetFrame.coerceIn(0, totalFrames)
        
        if (clampedFrame != currentFrame) {
            seekToExactFrame(clampedFrame)
            lastFrameCommandTime = now
            
            // Update UI
            seekTargetTime = formatTimeSimple(calculateTimeFromFrame(clampedFrame))
            currentTime = formatTimeSimple(calculateTimeFromFrame(clampedFrame))
        }
    }
    
    fun queueFrameCommand(direction: Int, frameCount: Int) {
        val command = FrameCommand(direction, frameCount)
        pendingFrameCommand = command
        
        // Execute immediately if possible, otherwise schedule
        executeFrameCommand(command)
    }
    
    // FRAME SCRUBBING FUNCTIONS WITH DEBOUNCING
    fun startFrameScrubbing(startX: Float) {
        isFrameScrubbing = true
        isHorizontalSwipe = true
        cancelAutoHide()
        
        // Reset tracking variables
        lastTouchX = startX
        lastTouchTime = System.currentTimeMillis()
        currentVelocity = 0f
        
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        currentFrame = calculateFrameFromTime(seekStartPosition)
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        showSeekTime = true
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun handleFrameScrubbing(currentX: Float) {
        if (!isFrameScrubbing) return
        
        val now = System.currentTimeMillis()
        val deltaX = currentX - lastTouchX
        val deltaTime = now - lastTouchTime
        
        // Calculate velocity (pixels per second)
        if (deltaTime > 0) {
            currentVelocity = (deltaX / deltaTime) * 1000
        }
        
        // Determine direction and calculate frames
        val direction = if (deltaX > 0) 1 else -1
        val frameCount = calculateFramesFromVelocity(abs(deltaX), deltaTime)
        
        // Queue the frame command (debouncing handled internally)
        queueFrameCommand(direction, frameCount)
        
        // Update tracking variables
        lastTouchX = currentX
        lastTouchTime = now
    }
    
    fun endFrameScrubbing() {
        if (isFrameScrubbing) {
            coroutineScope.launch {
                // Clear any pending commands
                frameCommandJob?.cancel()
                pendingFrameCommand = null
                
                // Resume playback if needed
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", false)
                }
                
                // Reset states
                isFrameScrubbing = false
                isHorizontalSwipe = false
                showSeekTime = false
                wasPlayingBeforeSeek = false
                currentVelocity = 0f
                scheduleSeekbarHide()
            }
        }
    }
    
    // CACHE CLEARING FUNCTION
    fun clearVideoCache() {
        MPVLib.setPropertyString("cache", "no")
        MPVLib.setPropertyString("demuxer-readahead-secs", "0")
        
        coroutineScope.launch {
            delay(16)
            MPVLib.setPropertyString("cache", "yes")
            MPVLib.setPropertyString("demuxer-readahead-secs", "10")
        }
    }
    
    // Function to get fresh position from MPV
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
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
    
    val showVolumeFeedback: (Int) -> Unit = { volume ->
        volumeFeedbackJob?.cancel()
        showVolumeFeedbackState = true
        volumeFeedbackJob = coroutineScope.launch {
            delay(1000)
            showVolumeFeedbackState = false
        }
    }
    
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    fun handleTap() {
        val currentPaused = MPVLib.getPropertyBoolean("pause") ?: false
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
        if (showSeekbar) {
            showSeekbar = false
        } else {
            showSeekbarWithTimeout()
        }
    }
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        longTapJob?.cancel()
        longTapJob = coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                MPVLib.setPropertyDouble("speed", 2.0)
            }
        }
    }
    
    fun checkForHorizontalSwipe(currentX: Float, currentY: Float): Boolean {
        if (isHorizontalSwipe || isLongTap) return false
        
        val deltaX = abs(currentX - touchStartX)
        val deltaY = abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            isHorizontalSwipe = true
            longTapJob?.cancel()
            return true
        }
        return false
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            isLongTap = false
            isSpeedingUp = false
            MPVLib.setPropertyDouble("speed", 1.0)
        } else if (isHorizontalSwipe) {
            endFrameScrubbing()
            isHorizontalSwipe = false
        } else if (touchDuration < 150) {
            handleTap()
        }
        isHorizontalSwipe = false
        isLongTap = false
    }
    
    // VIDEO DETECTION AND INITIALIZATION
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
        showVideoInfo = 1
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
        }
        scheduleSeekbarHide()
    }
    
    // DETECT VIDEO FPS
    LaunchedEffect(Unit) {
        delay(1000)
        
        val detectedFPS = MPVLib.getPropertyDouble("container-fps") ?: 
                         MPVLib.getPropertyDouble("video-params/fps") ?: 30.0
        videoFPS = detectedFPS.coerceIn(24.0, 60.0)
        
        val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
        totalFrames = calculateFrameFromTime(duration)
    }
    
    // Speed control
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    // OPTIMIZED MPV CONFIGURATION
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        
        // Frame-accurate seeking
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        MPVLib.setPropertyString("frame-drop", "no")
        
        // Cache settings
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 100 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "10")
    }
    
    // CONTINUOUS POSITION UPDATES
    LaunchedEffect(Unit) {
        var lastSeconds = -1
        
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            val currentSeconds = currentPos.toInt()
            
            // Update UI
            if (isFrameScrubbing) {
                currentTime = seekTargetTime
                totalTime = formatTimeSimple(duration)
            } else {
                if (currentSeconds != lastSeconds) {
                    currentTime = formatTimeSimple(currentPos)
                    totalTime = formatTimeSimple(duration)
                    currentFrame = calculateFrameFromTime(currentPos)
                    lastSeconds = currentSeconds
                }
            }
            
            if (!isDragging && !isFrameScrubbing) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            currentPosition = currentPos
            videoDuration = duration
            
            delay(500)
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }
    
    // PROGRESS BAR DRAG HANDLING
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        seekbarPosition = newPosition
        val targetPosition = newPosition.toDouble()
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        currentFrame = calculateFrameFromTime(targetPosition)
        
        seekToExactFrame(currentFrame)
    }
    
    fun handleDragFinished() {
        isDragging = false
        
        coroutineScope.launch {
            clearVideoCache()
            delay(16)
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", false)
            }
            
            isSeeking = false
            showSeekTime = false
            wasPlayingBeforeSeek = false
            scheduleSeekbarHide()
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // MAIN GESTURE AREA
        Box(modifier = Modifier.fillMaxSize()) {
            // TOP 5% - Ignore area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.05f)
                    .align(Alignment.TopStart)
            )
            
            // CENTER AREA - 95% height, divided into left/center/right
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .align(Alignment.BottomStart)
            ) {
                // LEFT 5% - Video info toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toggleVideoInfo() }
                        )
                )
                
                // CENTER 90% - Frame scrubbing gestures
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .pointerInteropFilter { event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    touchStartX = event.x
                                    touchStartY = event.y
                                    startLongTapDetection()
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (!isHorizontalSwipe && !isLongTap) {
                                        if (checkForHorizontalSwipe(event.x, event.y)) {
                                            startFrameScrubbing(event.x)
                                        }
                                    } else if (isHorizontalSwipe) {
                                        handleFrameScrubbing(event.x)
                                    }
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    endTouch()
                                    true
                                }
                                else -> false
                            }
                        }
                )
                
                // RIGHT 5% - Video info toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toggleVideoInfo() }
                        )
                )
            }
        }
        
        // BOTTOM SEEK BAR AREA
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = (-1).dp) 
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(modifier = Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$currentTime / $totalTime",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            if (isFrameScrubbing) {
                                Text(
                                    text = "Frame: $currentFrame | Vel: ${currentVelocity.toInt()}px/s",
                                    color = Color.Yellow,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
                            clearVideoCache = { clearVideoCache() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        
        // VIDEO INFO - Top Left
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // FEEDBACK AREA
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(currentVolume.toFloat() / viewModel.maxVolume.toFloat() * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showSeekTime -> Text(
                    text = if (isFrameScrubbing) "Frame: $currentFrame" else seekTargetTime,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                isFrameScrubbing -> Text(
                    text = "Frame Scrubbing (Debounced)",
                    color = Color.Yellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
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
    getFreshPosition: () -> Float,
    clearVideoCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0f) }
    var hasPassedThreshold by remember { mutableStateOf(false) }
    var thresholdStartX by remember { mutableStateOf(0f) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(24.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.CenterStart).background(Color.Gray.copy(alpha = 0.6f)))
        Box(modifier = Modifier.fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f).height(4.dp).align(Alignment.CenterStart).background(Color.White))
        Box(modifier = Modifier.fillMaxWidth().height(24.dp).align(Alignment.CenterStart).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    dragStartX = offset.x
                    dragStartPosition = getFreshPosition()
                    hasPassedThreshold = false
                    thresholdStartX = 0f
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val currentX = change.position.x
                    val totalMovementX = abs(currentX - dragStartX)
                    
                    if (!hasPassedThreshold) {
                        if (totalMovementX > movementThresholdPx) {
                            hasPassedThreshold = true
                            thresholdStartX = currentX
                            clearVideoCache()
                        } else {
                            return@detectDragGestures
                        }
                    }
                    
                    val effectiveStartX = if (hasPassedThreshold) thresholdStartX else dragStartX
                    val deltaX = currentX - effectiveStartX
                    val deltaPosition = (deltaX / size.width) * duration
                    val newPosition = (dragStartPosition + deltaPosition).coerceIn(0f, duration)
                    onValueChange(newPosition)
                },
                onDragEnd = { 
                    hasPassedThreshold = false
                    thresholdStartX = 0f
                    onValueChangeFinished() 
                }
            )
        })
    }
}

private fun formatTimeSimple(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, secs) else String.format("%02d:%02d", minutes, secs)
}

private fun getFileNameFromUri(uri: Uri?, context: android.content.Context): String {
    if (uri == null) return getBestAvailableFileName(context)
    return when {
        uri.scheme == "file" -> uri.lastPathSegment?.substringBeforeLast(".") ?: getBestAvailableFileName(context)
        uri.scheme == "content" -> getDisplayNameFromContentUri(uri, context) ?: getBestAvailableFileName(context)
        uri.scheme in listOf("http", "https") -> uri.lastPathSegment?.substringBeforeLast(".") ?: "Online Video"
        else -> getBestAvailableFileName(context)
    }
}

private fun getDisplayNameFromContentUri(uri: Uri, context: android.content.Context): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex("_display_name")
                val displayName = if (displayNameIndex != -1) cursor.getString(displayNameIndex)?.substringBeforeLast(".") else null
                displayName ?: uri.lastPathSegment?.substringBeforeLast(".")
            } else null
        }
    } catch (e: Exception) { null }
}

private fun getBestAvailableFileName(context: android.content.Context): String {
    val mediaTitle = MPVLib.getPropertyString("media-title")
    if (mediaTitle != null && mediaTitle != "Video" && mediaTitle.isNotBlank()) return mediaTitle.substringBeforeLast(".")
    val mpvPath = MPVLib.getPropertyString("path")
    if (mpvPath != null && mpvPath.isNotBlank()) return mpvPath.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "Video" }
    return "Video"
}
