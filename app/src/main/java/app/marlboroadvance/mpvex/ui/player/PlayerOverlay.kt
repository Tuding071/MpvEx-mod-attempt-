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
import androidx.compose.ui.platform.LocalDensity
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
    var pendingPauseResume by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    
    var currentPosition by remember { mutableStateOf(0.0) }
    var videoDuration by remember { mutableStateOf(1.0) }
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    
    var isDragging by remember { mutableStateOf(false) }
    
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // Seek direction for feedback
    var seekDirection by remember { mutableStateOf("") } // "+" or "-" or ""
    
    // Simple throttle control
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 50L // Small delay between seek commands
    
    // Gesture states
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Thresholds
    val longTapThreshold = 300L // ms
    val horizontalSwipeThreshold = 30f // pixels
    val verticalSwipeThreshold = 40f // pixels
    val maxVerticalMovement = 50f // pixels
    val maxHorizontalMovement = 50f // pixels
    
    // Quick seek amount in seconds
    val quickSeekAmount = 5
    
    var showVideoInfo by remember { mutableStateOf(0) }
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Quick seek feedback
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var quickSeekFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // AUDIO PREVIEW SYSTEM - Enabled by default
    var audioPreviewEnabled by remember { mutableStateOf(true) } // Enabled by default
    var lastAudioPreviewTime by remember { mutableStateOf(0L) }
    var audioPreviewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val audioPreviewCooldownMs = 50L // Minimum 300ms between previews
    val audioPreviewDurationMs = 40L // Play audio for 250ms
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Function to play smart audio preview
    fun playSmartAudioPreviewAt(position: Double) {
        if (!audioPreviewEnabled) return
        
        val now = System.currentTimeMillis()
        
        // Check cooldown - prevent too frequent previews
        if (now - lastAudioPreviewTime < audioPreviewCooldownMs) {
            return // Skip this preview, too soon after last one
        }
        
        // Cancel any ongoing preview
        audioPreviewJob?.cancel()
        
        // Store current playback state
        val wasPaused = MPVLib.getPropertyBoolean("pause") ?: true
        val currentSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0
        
        // Play audio preview
        audioPreviewJob = coroutineScope.launch {
            try {
                // 1. Seek to exact position
                MPVLib.command("seek", position.toString(), "absolute", "exact")
                
                // 2. Temporarily unpause and play at normal speed
                MPVLib.setPropertyBoolean("pause", false)
                MPVLib.setPropertyDouble("speed", 1.0)
                
                // 3. Play for short duration
                delay(audioPreviewDurationMs)
                
                // 4. Restore original state
                MPVLib.setPropertyBoolean("pause", wasPaused)
                MPVLib.setPropertyDouble("speed", currentSpeed)
                
                // 5. If we were paused, seek back to exact frame
                if (wasPaused) {
                    delay(50) // Small delay for clean transition
                    MPVLib.command("seek", position.toString(), "absolute", "exact")
                }
                
            } catch (e: Exception) {
                // Silently fail - audio preview is non-critical
            }
        }
        
        lastAudioPreviewTime = now
    }
    
    // Clean up audio preview
    fun cleanupAudioPreview() {
        audioPreviewJob?.cancel()
        audioPreviewJob = null
    }
    
    // performRealTimeSeek with throttle
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        
        isSeekInProgress = true
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
        
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    // Function to get fresh position from MPV
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    // Quick seek function
    fun performQuickSeek(seconds: Int) {
        val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val newPosition = (currentPos + seconds).coerceIn(0.0, duration)
        
        // Show feedback
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        quickSeekFeedbackJob?.cancel()
        quickSeekFeedbackJob = coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        
        // Perform seek
        MPVLib.command("seek", seconds.toString(), "relative", "exact")
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
        isPausing = !currentPaused
    }
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        longTapJob?.cancel()
        longTapJob = coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe && !isVerticalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                MPVLib.setPropertyDouble("speed", 2.0)
            }
        }
    }
    
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return ""
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        // Check for horizontal swipe
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            return "horizontal"
        }
        
        // Check for vertical swipe
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            return "vertical"
        }
        
        return ""
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        val currentY = startY
        val deltaY = currentY - touchStartY
        
        if (deltaY < 0) {
            // Swipe up - seek forward
            seekDirection = "+"
            performQuickSeek(quickSeekAmount)
        } else {
            // Swipe down - seek backward
            seekDirection = "-"
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    // UPDATED: handleHorizontalSeeking with audio preview
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 2f / 0.032f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        // SMART AUDIO PREVIEW LOGIC:
        val isSlowMovement = kotlin.math.abs(deltaX) < 50f // Pixels
        val isHoldingStill = System.currentTimeMillis() - touchStartTime > 500L
        
        if (audioPreviewEnabled && (isSlowMovement || isHoldingStill)) {
            playSmartAudioPreviewAt(clampedPosition)
        }
        
        // Update UI
        seekDirection = if (deltaX > 0) "+" else "-"
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        // Send seek command
        performRealTimeSeek(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: seekStartPosition
            performRealTimeSeek(currentPos)
            
            // Clean up audio preview
            cleanupAudioPreview()
            
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
            seekDirection = ""
            scheduleSeekbarHide()
        }
    }
    
    fun endVerticalSwipe() {
        isVerticalSwipe = false
        scheduleSeekbarHide()
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            // Long tap ended - reset speed
            isLongTap = false
            isSpeedingUp = false
            MPVLib.setPropertyDouble("speed", 1.0)
            cleanupAudioPreview()
        } else if (isHorizontalSwipe) {
            // Horizontal swipe ended
            endHorizontalSeeking()
            isHorizontalSwipe = false
        } else if (isVerticalSwipe) {
            // Vertical swipe ended
            endVerticalSwipe()
            isVerticalSwipe = false
        } else if (touchDuration < 150) {
            // Short tap (less than 150ms)
            handleTap()
        }
        // Reset all gesture states
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    // UPDATED: handleProgressBarDrag with audio preview
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
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        
        // Update seek direction
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        
        // Progress bar audio preview - only on significant changes
        val positionChange = kotlin.math.abs(newPosition - oldPosition)
        if (audioPreviewEnabled && positionChange > 2.0f) { // More than 2 seconds change
            playSmartAudioPreviewAt(targetPosition)
        }
        
        // Update UI
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Send seek command
        performRealTimeSeek(targetPosition)
    }
    
    // UPDATED: handleDragFinished with audio cleanup
    fun handleDragFinished() {
        isDragging = false
        cleanupAudioPreview()
        
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
        isSeeking = false
        showSeekTime = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        scheduleSeekbarHide()
    }
    
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
    
    // Backup speed control
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "8")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        MPVLib.setPropertyString("cache-initial", "0.5")
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-skipidct", "all")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
        MPVLib.setPropertyString("gpu-dumb-mode", "yes")
        MPVLib.setPropertyString("opengl-pbo", "yes")
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1:reconnect_at_eof=1:reconnect_streamed=1")
        MPVLib.setPropertyString("network-timeout", "30")
        MPVLib.setPropertyString("audio-client-name", "MPVEx-Software-4Core")
        MPVLib.setPropertyString("audio-samplerate", "auto")
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("video-aspect-override", "no")
    }
    
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
            delay(100)
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // MAIN GESTURE AREA - Full screen divided into areas
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
                
                // CENTER 90% - All gestures (tap, long tap, horizontal swipe, vertical swipe)
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
                                    if (!isHorizontalSwipe && !isVerticalSwipe && !isLongTap) {
                                        when (checkForSwipeDirection(event.x, event.y)) {
                                            "horizontal" -> {
                                                startHorizontalSeeking(event.x)
                                            }
                                            "vertical" -> {
                                                startVerticalSwipe(event.y)
                                            }
                                        }
                                    } else if (isHorizontalSwipe) {
                                        // Continue horizontal seeking
                                        handleHorizontalSeeking(event.x)
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
                    .offset(y = (3).dp) 
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(modifier = Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$currentTime / $totalTime",
                                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
                            modifier = Modifier.fillMaxSize().height(48.dp)
                        )
                    }
                }
            }
        }
        
        // VIDEO INFO - Top Left
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // FEEDBACK AREA
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(currentVolume.toFloat() / viewModel.maxVolume.toFloat() * 100).toInt()}%",
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showSeekTime -> Text(
                    text = if (seekDirection.isNotEmpty()) "$seekTargetTime $seekDirection" else seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
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
    modifier: Modifier = Modifier
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0f) }
    var hasPassedThreshold by remember { mutableStateOf(false) }
    var thresholdStartX by remember { mutableStateOf(0f) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(48.dp)) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.Gray.copy(alpha = 0.6f)))
        
        Box(modifier = Modifier
            .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.White))
        
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .align(Alignment.CenterStart)
            .pointerInput(Unit) {
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
            }
        )
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
