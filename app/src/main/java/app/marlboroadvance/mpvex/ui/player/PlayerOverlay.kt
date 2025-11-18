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
    
    // ADD PREPROCESSING STATES
    var isPreprocessing by remember { mutableStateOf(false) }
    var preprocessingProgress by remember { mutableStateOf(0) }
    var isStreamPrepared by remember { mutableStateOf(false) }
    
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
    
    // ADD: Seek direction for feedback
    var seekDirection by remember { mutableStateOf("") } // "+" or "-" or ""
    
    // REMOVED: lastSeekTime and seekDebounceMs
    // ADD: Simple throttle control
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 0L // Small delay between seek commands
    
    // CLEAR GESTURE STATES WITH MUTUAL EXCLUSION
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) } // ADD: Vertical swipe state
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // THRESHOLDS
    val longTapThreshold = 300L // ms
    val horizontalSwipeThreshold = 30f // pixels - minimum horizontal movement to trigger seeking
    val verticalSwipeThreshold = 40f // pixels - minimum vertical movement to trigger quick seek
    val maxVerticalMovement = 50f // pixels - maximum vertical movement allowed for horizontal swipe
    val maxHorizontalMovement = 50f // pixels - maximum horizontal movement allowed for vertical swipe
    
    // ADD: Quick seek amount in seconds
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
    
    // ADD: Quick seek feedback
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var quickSeekFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // VELOCITY-BASED SMOOTHING VARIABLES
    var lastSeekX by remember { mutableStateOf(0f) }
    var lastSeekTime by remember { mutableStateOf(0L) }
    var velocity by remember { mutableStateOf(0f) } // pixels per second
    val smoothingFactor = 0.7f // How much smoothing to apply (0 = no smoothing, 1 = max smoothing)
    val minVelocityForSmoothing = 50f // pixels/sec - minimum velocity to apply smoothing
    
    // VELOCITY-BASED SMOOTHING FUNCTIONS
    fun calculateVelocity(currentX: Float, currentTimeMillis: Long): Float {
        if (lastSeekTime == 0L) {
            lastSeekX = currentX
            lastSeekTime = currentTimeMillis
            return 0f
        }
        
        val deltaX = currentX - lastSeekX
        val deltaTime = (currentTimeMillis - lastSeekTime).coerceAtLeast(1) // Avoid division by zero
        val currentVelocity = (deltaX / deltaTime) * 1000f // Convert to pixels per second
        
        lastSeekX = currentX
        lastSeekTime = currentTimeMillis
        
        return currentVelocity
    }
    
    fun applySmoothing(rawPosition: Double, velocity: Float): Double {
        // Only apply smoothing if we're moving fast enough
        if (abs(velocity) < minVelocityForSmoothing) {
            return rawPosition
        }
        
        // Calculate smoothing factor based on velocity
        // Faster movement = more smoothing
        val dynamicSmoothing = smoothingFactor * (abs(velocity) / 1000f).coerceIn(0f, 1f)
        
        // Simple low-pass filter for smoothing
        val currentPos = MPVLib.getPropertyDouble("time-pos") ?: rawPosition
        val smoothedPosition = currentPos + (rawPosition - currentPos) * (1f - dynamicSmoothing).toDouble()
        
        return smoothedPosition
    }

    // IMPROVED: Better segment scanning - scans multiple key points
    fun preprocessOfflineFile() {
        isPreprocessing = true
        preprocessingProgress = 0
        
        coroutineScope.launch {
            // MUTE VIDEO DURING PREPROCESSING
            val wasMuted = MPVLib.getPropertyBoolean("mute") ?: false
            if (!wasMuted) {
                MPVLib.setPropertyBoolean("mute", true)
            }
            
            // STEP 1: Configure MPV for TS-in-MP4 files
            MPVLib.setPropertyString("demuxer-mkv-subtitle-preroll", "yes")
            MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
            MPVLib.setPropertyString("demuxer-thread", "yes")
            MPVLib.setPropertyBoolean("correct-pts", true)
            
            preprocessingProgress = 10
            delay(100)

            // STEP 2: Scan multiple key points for better segment coverage
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            
            if (duration > 10) {
                // Scan multiple key points for comprehensive segment mapping
                val scanPoints = listOf(
                    0.05,  // 5% - beginning
                    0.15,  // 15% 
                    0.30,  // 30%
                    0.50,  // 50% - middle
                    0.70,  // 70%
                    0.85,  // 85%
                    0.95   // 95% - near end
                )
                
                val progressPerPoint = 70 / scanPoints.size // 70% of progress for scanning
                
                for ((index, point) in scanPoints.withIndex()) {
                    val targetTime = duration * point
                    MPVLib.command("seek", targetTime.toString(), "absolute", "keyframes")
                    delay(80) // Short delay between seeks
                    
                    preprocessingProgress = 10 + (index + 1) * progressPerPoint
                }
            } else {
                // For short videos, just scan beginning, middle, end
                MPVLib.command("seek", (duration * 0.3).toString(), "absolute", "keyframes")
                preprocessingProgress = 40
                delay(80)
                
                MPVLib.command("seek", (duration * 0.7).toString(), "absolute", "keyframes")
                preprocessingProgress = 70
                delay(80)
            }
            
            // STEP 3: Return to start
            MPVLib.command("seek", "0", "absolute", "keyframes")
            preprocessingProgress = 90
            delay(100)

            // STEP 4: Final configuration
            MPVLib.setPropertyString("hr-seek", "absolute")
            MPVLib.setPropertyString("hr-seek-framedrop", "no")
            
            preprocessingProgress = 100
            delay(50)
            
            // RESTORE MUTE STATE AFTER PREPROCESSING
            if (!wasMuted) {
                MPVLib.setPropertyBoolean("mute", false)
            }
            
            isPreprocessing = false
            isStreamPrepared = true
            
            // Start playback
            MPVLib.setPropertyBoolean("pause", false)
        }
    }

    // UPDATED: performRealTimeSeek with throttle
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return // Skip if we're already processing a seek
        
        isSeekInProgress = true
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
        
        // Reset after throttle period
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    // NEW: Function to get fresh position from MPV
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    // ADD: Quick seek function
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
    
    // UPDATED: checkForHorizontalSwipe to also check for vertical swipes
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return "" // Already determined or long tap active
        
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
    
    // UPDATED: startHorizontalSeeking - INITIALIZE VELOCITY TRACKING
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        
        // INITIALIZE VELOCITY TRACKING
        lastSeekX = startX
        lastSeekTime = System.currentTimeMillis()
        velocity = 0f
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    // ADD: Start vertical swipe detection
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        // Determine direction based on initial movement
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
    
    // UPDATED: handleHorizontalSeeking - WITH VELOCITY-BASED SMOOTHING
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        // CALCULATE VELOCITY
        val currentTimeMillis = System.currentTimeMillis()
        velocity = calculateVelocity(currentX, currentTimeMillis)
        
        val deltaX = currentX - seekStartX
        
        // Convert pixel movement to time using percentage-based calculation
        // while maintaining your original sensitivity
        val gestureAreaWidth = 1000f // Approximate width of your gesture area (90% of screen)
        val sensitivity = 0.0075f // Adjusted to match your original sensitivity
        
        val percentageDelta = (deltaX / gestureAreaWidth) * sensitivity
        val timeDeltaSeconds = percentageDelta * videoDuration
        val rawPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        
        // APPLY VELOCITY-BASED SMOOTHING
        val smoothedPosition = applySmoothing(rawPositionSeconds, velocity)
        val clampedPosition = smoothedPosition.coerceIn(0.0, duration)
        
        // UPDATE: Set seek direction based on movement
        seekDirection = if (deltaX > 0) "+" else "-"
        
        // ALWAYS update UI instantly
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        // Send seek command with throttle for real-time frame updates
        performRealTimeSeek(clampedPosition)
    }
    
    // UPDATED: endHorizontalSeeking - RESET VELOCITY TRACKING
    fun endHorizontalSeeking() {
        if (isSeeking) {
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
            seekDirection = "" // Reset direction
            
            // RESET VELOCITY TRACKING
            lastSeekX = 0f
            lastSeekTime = 0L
            velocity = 0f
            
            scheduleSeekbarHide()
        }
    }
    
    // ADD: End vertical swipe
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
    
    // MODIFIED: Enhanced LaunchedEffect for video initialization
    LaunchedEffect(Unit) {
        // Load video file info first
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
        
        // START PREPROCESSING
        preprocessOfflineFile()
        
        // Show video info briefly (after preprocessing)
        if (!isPreprocessing) {
            showVideoInfo = 1
            videoInfoJob?.cancel()
            videoInfoJob = coroutineScope.launch {
                delay(4000)
                showVideoInfo = 0
            }
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
    
    // MODIFIED: Enhanced MPV config for TS-in-MP4 files
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
        
        // ENHANCED CONFIG FOR TS-IN-MP4 FILES
        MPVLib.setPropertyString("demuxer-lavf-o", "seekable=1:fflags=+fastseek")
        MPVLib.setPropertyBoolean("correct-pts", true)
        MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
        MPVLib.setPropertyString("demuxer-thread", "yes")
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
    
    // UPDATED: handleProgressBarDrag - NO SMOOTHING (keep original behavior for seekbar)
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
        
        // UPDATE: Set seek direction based on movement
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        
        // ALWAYS update UI instantly
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Send seek command with throttle for real-time frame updates
        performRealTimeSeek(targetPosition)
    }
    
    // UPDATED: handleDragFinished
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
        seekDirection = "" // Reset direction
        scheduleSeekbarHide()
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // PREPROCESSING OVERLAY - Shows before video starts
        if (isPreprocessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black) // SOLID BLACK BACKGROUND
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // SIMPLE PERCENTAGE TEXT ONLY - NO SPINNER
                    Text(
                        text = "$preprocessingProgress%",
                        style = TextStyle(
                            color = Color.White, 
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    
                    Text(
                        text = "Preparing video for smooth seeking...",
                        style = TextStyle(
                            color = Color.White, 
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        
        // YOUR EXISTING UI - Only show when not preprocessing
        if (!isPreprocessing) {
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
                            // USE SINGLE pointerInteropFilter FOR ALL GESTURES TO AVOID CONFLICTS
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
                                            // Check if this should become a horizontal or vertical swipe
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
                                        // If it's a long tap or vertical swipe, ignore movement (allow slight finger movement during hold)
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
                        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) { // CHANGED: Increased height for better touch area
                            SimpleDraggableProgressBar(
                                position = seekbarPosition,
                                duration = seekbarDuration,
                                onValueChange = { handleProgressBarDrag(it) },
                                onValueChangeFinished = { handleDragFinished() },
                                getFreshPosition = { getFreshPosition() },
                                modifier = Modifier.fillMaxSize().height(48.dp) // CHANGED: Increased height
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
                    showQuickSeekFeedback -> Text( // ADD: Quick seek feedback
                        text = quickSeekFeedbackText,
                        style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    showSeekTime -> Text(
                        // UPDATED: Add direction indicator to seek time
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
    
    // Convert 25dp to pixels for the movement threshold
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(48.dp)) { // CHANGED: Increased container height
        // Progress bar background
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.Gray.copy(alpha = 0.6f)))
        
        // Progress bar fill
        Box(modifier = Modifier
            .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.White))
        
        // CHANGED: Increased touch area to full 48dp height
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(48.dp) // This makes the entire 48dp area draggable
            .align(Alignment.CenterStart)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStartX = offset.x
                        // GET FRESH POSITION IMMEDIATELY WHEN DRAG STARTS
                        dragStartPosition = getFreshPosition()
                        hasPassedThreshold = false // Reset threshold flag
                        thresholdStartX = 0f // Reset threshold start position
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val currentX = change.position.x
                        val totalMovementX = abs(currentX - dragStartX)
                        
                        // Check if we've passed the movement threshold
                        if (!hasPassedThreshold) {
                            if (totalMovementX > movementThresholdPx) {
                                hasPassedThreshold = true
                                thresholdStartX = currentX // NEW: Store position where threshold was passed
                            } else {
                                // Haven't passed threshold yet, don't seek
                                return@detectDragGestures
                            }
                        }
                        
                        // Calculate delta from the threshold start position, not the original drag start
                        val effectiveStartX = if (hasPassedThreshold) thresholdStartX else dragStartX
                        val deltaX = currentX - effectiveStartX
                        val deltaPosition = (deltaX / size.width) * duration
                        val newPosition = (dragStartPosition + deltaPosition).coerceIn(0f, duration)
                        onValueChange(newPosition)
                    },
                    onDragEnd = { 
                        hasPassedThreshold = false // Reset for next drag
                        thresholdStartX = 0f // Reset threshold start
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
