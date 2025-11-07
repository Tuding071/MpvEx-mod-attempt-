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
    
    // PRE-DECODING STATES
    var isPreDecodingActive by remember { mutableStateOf(true) }
    var lastPreDecodePosition by remember { mutableStateOf(0.0) }
    var preDecodeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // SIMPLE THROTTLE CONTROL
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 30L
    
    // MEMORY OPTIMIZATION VARIABLES
    var lastCleanupTime by remember { mutableStateOf(0L) }
    var lastCleanPosition by remember { mutableStateOf(0.0) }
    val cleanupInterval = 5 * 60 * 1000L // 5 minutes
    
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
    var videoTitle by remember { mutableStateOf("Video") }
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

    // =========================================================================
    // PRE-DECODING MANAGEMENT FUNCTIONS
    // =========================================================================
    
    fun startPreDecoding() {
        isPreDecodingActive = true
        // Set aggressive cache for pre-decoding
        MPVLib.setPropertyString("demuxer-readahead-secs", "15")
        MPVLib.setPropertyString("cache-secs", "15")
        MPVLib.setPropertyInt("demuxer-max-bytes", 150 * 1024 * 1024)
        MPVLib.setPropertyString("cache-pause", "no")
    }
    
    fun stopPreDecoding() {
        isPreDecodingActive = false
        preDecodeJob?.cancel()
        // Reduce cache during seeking
        MPVLib.setPropertyString("demuxer-readahead-secs", "5")
        MPVLib.setPropertyString("cache-secs", "5")
        MPVLib.setPropertyInt("demuxer-max-bytes", 50 * 1024 * 1024)
        MPVLib.setPropertyString("cache-pause", "yes")
    }
    
    // SIMPLIFIED PRE-DECODING - Based on your concept
    fun updatePreDecoding(position: Double, duration: Double) {
        if (!isPreDecodingActive || isSeeking || isDragging || isSpeedingUp) return
        
        // Only update if position changed significantly
        if (abs(position - lastPreDecodePosition) > 2.0) {
            lastPreDecodePosition = position
            
            // Cancel any existing pre-decode job
            preDecodeJob?.cancel()
            
            // Start new pre-decode job
            preDecodeJob = coroutineScope.launch {
                // Set aggressive caching for the current window
                val windowStart = (position - 15.0).coerceAtLeast(0.0)
                val windowEnd = (position + 15.0).coerceAtMost(duration)
                
                // Force MPV to cache this range
                MPVLib.setPropertyString("demuxer-readahead-secs", "15")
                MPVLib.setPropertyString("cache-secs", "15")
                MPVLib.setPropertyInt("demuxer-max-bytes", 150 * 1024 * 1024)
                
                // Pre-decode key areas by seeking to them briefly
                val keyPoints = listOf(
                    position, // Current position
                    (position - 5.0).coerceAtLeast(0.0), // 5 seconds back
                    (position + 5.0).coerceAtMost(duration), // 5 seconds forward
                    (position - 10.0).coerceAtLeast(0.0), // 10 seconds back  
                    (position + 10.0).coerceAtMost(duration), // 10 seconds forward
                )
                
                for (point in keyPoints.distinct()) {
                    if (!isActive) break
                    MPVLib.command("seek", point.toString(), "absolute", "exact")
                    delay(10) // Brief delay
                }
                
                // Return to actual position
                MPVLib.command("seek", position.toString(), "absolute", "exact")
            }
        }
    }

    // =========================================================================
    // MEMORY MANAGEMENT FUNCTIONS
    // =========================================================================
    
    fun gentleCleanup() {
        MPVLib.setPropertyString("demuxer-readahead-secs", "10")
        MPVLib.setPropertyString("cache-secs", "10")
        MPVLib.setPropertyInt("demuxer-max-bytes", 80 * 1024 * 1024)
    }
    
    fun moderateCleanup() {
        MPVLib.setPropertyString("demuxer-readahead-secs", "5")
        MPVLib.setPropertyString("cache-secs", "5")
        MPVLib.setPropertyInt("demuxer-max-bytes", 40 * 1024 * 1024)
    }
    
    fun aggressiveCleanup() {
        MPVLib.setPropertyString("demuxer-readahead-secs", "0")
        MPVLib.setPropertyString("cache-secs", "0")
        MPVLib.setPropertyInt("demuxer-max-bytes", 10 * 1024 * 1024)
        try { System.gc() } catch (e: Exception) { }
    }

    // =========================================================================
    // SEEKING FUNCTIONS
    // =========================================================================
    
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        
        isSeekInProgress = true
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
        
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }

    // =========================================================================
    // GESTURE HANDLERS (UPDATED WITH PRE-DECODING COORDINATION)
    // =========================================================================
    
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
            // Resume playback - restart pre-decoding
            coroutineScope.launch {
                val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                MPVLib.command("seek", currentPos.toString(), "absolute", "exact")
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
                startPreDecoding() // Restart pre-decoding on resume
            }
            showPlaybackFeedback("Resume")
        } else {
            // Pause playback - maintain pre-decoding for potential seeking
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
            if (isTouching && !isHorizontalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                // Stop pre-decoding during 2x speed
                stopPreDecoding()
                MPVLib.setPropertyDouble("speed", 2.0)
            }
        }
    }
    
    fun checkForHorizontalSwipe(currentX: Float, currentY: Float): Boolean {
        if (isHorizontalSwipe || isLongTap) return false
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            isHorizontalSwipe = true
            longTapJob?.cancel()
            return true
        }
        return false
    }
    
    fun startHorizontalSeeking(startX: Float) {
        // STOP pre-decoding when seeking starts
        stopPreDecoding()
        
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
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 6f / 0.033f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        performRealTimeSeek(clampedPosition)
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: seekStartPosition
            performRealTimeSeek(currentPos)
            
            // RESTART pre-decoding after seeking ends
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                    startPreDecoding() // Restart pre-decoding after seek
                }
            } else {
                // If was paused, still restart pre-decoding for potential future seeks
                startPreDecoding()
            }
            
            isSeeking = false
            showSeekTime = false
            seekStartX = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            scheduleSeekbarHide()
        }
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            // Long tap ended - reset speed and RESTART pre-decoding
            isLongTap = false
            isSpeedingUp = false
            MPVLib.setPropertyDouble("speed", 1.0)
            startPreDecoding() // Restart pre-decoding after 2x speed ends
        } else if (isHorizontalSwipe) {
            // Horizontal swipe ended
            endHorizontalSeeking()
            isHorizontalSwipe = false
        } else if (touchDuration < 150) {
            // Short tap
            handleTap()
        }
        
        isHorizontalSwipe = false
        isLongTap = false
    }

    // =========================================================================
    // PROGRESS BAR DRAG HANDLERS (UPDATED WITH PRE-DECODING)
    // =========================================================================
    
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            // STOP pre-decoding when seekbar drag starts
            stopPreDecoding()
            
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
        
        performRealTimeSeek(targetPosition)
    }
    
    fun handleDragFinished() {
        isDragging = false
        
        // RESTART pre-decoding after seekbar drag ends
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
                startPreDecoding() // Restart pre-decoding
            }
        } else {
            // If was paused, still restart pre-decoding
            startPreDecoding()
        }
        
        isSeeking = false
        showSeekTime = false
        wasPlayingBeforeSeek = false
        scheduleSeekbarHide()
    }

    // =========================================================================
    // LAUNCHED EFFECTS - CORE LOGIC
    // =========================================================================
    
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    // SMART PRE-DECODING MANAGEMENT - SIMPLIFIED
    LaunchedEffect(currentPosition, videoDuration, isSeeking, isDragging, isSpeedingUp) {
        val shouldPreDecode = isPreDecodingActive && 
                             !isSeeking && 
                             !isDragging && 
                             !isSpeedingUp
        
        if (shouldPreDecode && videoDuration > 0) {
            updatePreDecoding(currentPosition, videoDuration)
        }
    }
    
    // SMART MEMORY CLEANUP (Event-based)
    LaunchedEffect(currentPosition, videoDuration, isPausing) {
        val currentTime = System.currentTimeMillis()
        
        // Condition 1: Video completed
        if (videoDuration > 0 && currentPosition >= videoDuration - 2) {
            gentleCleanup()
            lastCleanupTime = currentTime
        }
        // Condition 2: Large seek (more than 30 seconds)
        else if (abs(currentPosition - lastCleanPosition) > 30) {
            gentleCleanup()
            lastCleanPosition = currentPosition
            lastCleanupTime = currentTime
        }
        // Condition 3: Paused for 3+ minutes
        else if (isPausing && currentTime - lastCleanupTime > 3 * 60 * 1000) {
            moderateCleanup()
            lastCleanupTime = currentTime
        }
        // Condition 4: Periodic (every 5 minutes) but only if not interacting
        else if (currentTime - lastCleanupTime > 5 * 60 * 1000 && 
                !userInteracting && !isSeeking && !isDragging) {
            gentleCleanup()
            lastCleanupTime = currentTime
        }
    }
    
    // INITIAL SETUP
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
        
        // START pre-decoding initially
        startPreDecoding()
        scheduleSeekbarHide()
    }
    
    // BACKUP SPEED CONTROL WITH PRE-DECODING COORDINATION
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            stopPreDecoding() // Stop pre-decoding during 2x speed
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
            startPreDecoding() // Restart pre-decoding when returning to normal speed
        }
    }
    
    // OPTIMIZED MPV CONFIGURATION
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "4")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        
        // Initial cache settings (will be overridden by pre-decoding)
        MPVLib.setPropertyString("cache", "yes")
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
    
    // POSITION UPDATES
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
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }

    // =========================================================================
    // UI COMPOSITION
    // =========================================================================
    
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
                
                // CENTER 90% - All gestures
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
                                            startHorizontalSeeking(event.x)
                                        }
                                    } else if (isHorizontalSwipe) {
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
                    .offset(y = (-1).dp) 
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
                    Box(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
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
                showSeekTime -> Text(
                    text = seekTargetTime,
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

    // CLEANUP ON EXIT
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Aggressive cleanup when exiting
            MPVLib.command("stop")
            aggressiveCleanup()
        }
    }
}

// ... rest of your existing code (SimpleDraggableProgressBar and utility functions) remains the same ...
