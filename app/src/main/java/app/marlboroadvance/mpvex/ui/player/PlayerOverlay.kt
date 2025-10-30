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
    var isDragging by remember { mutableStateOf(false) } // Track if user is dragging
    
    // Drag seeking variables
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // ⭐ DEBOUNCING: Track last seek time for both drag and seekbar seeking
    var lastSeekTime by remember { mutableStateOf(0L) }
    val seekDebounceMs = 16L // 16ms = ~60fps for buttery smooth seeking
    
    // Tap detection variables
    var leftTapStartTime by remember { mutableStateOf(0L) }
    var rightTapStartTime by remember { mutableStateOf(0L) }
    var leftIsHolding by remember { mutableStateOf(false) }
    var rightIsHolding by remember { mutableStateOf(false) }
    
    // Video title and filename state
    var showVideoInfo by remember { mutableStateOf(0) } // 0=hide, 1=filename
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // ⭐ NEW: Auto-hide timeout for seekbar
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // ⭐ NEW: Pause/Resume feedback
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // ⭐ NEW: 480p downscaling during seeking
    var isDownscaled by remember { mutableStateOf(false) }
    var downscaleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // ⭐ NEW: Volume feedback
    var showVolumeFeedback by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // ⭐ NEW: Observe volume changes from ViewModel
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    // ⭐ NEW: Function to show volume feedback
    fun showVolumeFeedback(volume: Int) {
        volumeFeedbackJob?.cancel()
        showVolumeFeedback = true
        
        volumeFeedbackJob = coroutineScope.launch {
            delay(1000) // Show for 1 second
            showVolumeFeedback = false
        }
    }
    
    // ⭐ NEW: Function to revert to normal quality (DEFINED FIRST)
    fun revertToNormalQuality() {
        if (isDownscaled) {
            MPVLib.setPropertyString("scale", "no")
            isDownscaled = false
        }
    }
    
    // ⭐ NEW: Function to activate 480p downscaling for seeking
    fun activateSeekingMode() {
        if (!isDownscaled) {
            coroutineScope.launch {
                // Small delay to ensure smooth transition
                delay(30)
                MPVLib.setPropertyString("scale", "480")
                isDownscaled = true
            }
        }
        
        // Reset the auto-revert timer
        downscaleJob?.cancel()
        downscaleJob = coroutineScope.launch {
            delay(600) // Keep downscaled for 600ms after seeking ends
            revertToNormalQuality()
        }
    }
    
    // ⭐ NEW: Function to schedule seekbar hide
    fun scheduleSeekbarHide() {
        if (userInteracting) return // Don't hide if user is interacting
        
        hideSeekbarJob?.cancel()
        hideSeekbarJob = coroutineScope.launch {
            delay(4000)
            showSeekbar = false
        }
    }
    
    // ⭐ NEW: Function to cancel auto-hide when user interacts
    fun cancelAutoHide() {
        userInteracting = true
        hideSeekbarJob?.cancel()
        
        // Reset userInteracting after a short delay
        coroutineScope.launch {
            delay(100)
            userInteracting = false
        }
    }
    
    // ⭐ NEW: Show seekbar and cancel auto-hide
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        scheduleSeekbarHide()
    }
    
    // ⭐ NEW: Show playback feedback
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackJob?.cancel()
        showPlaybackFeedback = true
        playbackFeedbackText = text
        
        playbackFeedbackJob = coroutineScope.launch {
            delay(1000) // Show for 1 second
            showPlaybackFeedback = false
        }
    }
    
    // IMPROVED: Get video filename from multiple sources
    LaunchedEffect(Unit) {
        // Try to get filename from the Intent that started this activity
        val intent = (context as? android.app.Activity)?.intent
        fileName = when {
            intent?.action == Intent.ACTION_SEND -> {
                // Handle share intent
                getFileNameFromUri(intent.getParcelableExtra(Intent.EXTRA_STREAM), context)
            }
            intent?.action == Intent.ACTION_VIEW -> {
                // Handle view intent (most common)
                getFileNameFromUri(intent.data, context)
            }
            else -> {
                // Fallback: try multiple sources
                getBestAvailableFileName(context)
            }
        }
        
        // Get video title from MPV
        val title = MPVLib.getPropertyString("media-title") ?: "Video"
        videoTitle = title
        
        // Show filename at start
        showVideoInfo = 1
        
        // Auto-hide after 4 seconds
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
        }
        
        // Auto-hide seekbar after 4 seconds
        scheduleSeekbarHide()
    }
    
    // Handle speed reset when not holding
    LaunchedEffect(leftIsHolding, rightIsHolding) {
        if (!leftIsHolding && !rightIsHolding && isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 1.0)
            isSpeedingUp = false
        }
    }
    
    // Handle speed transitions IMMEDIATELY (no delays)
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0) // Immediate
        } else {
            MPVLib.setPropertyDouble("speed", 1.0) // Immediate
        }
    }
    
    // OPTIMIZED SOFTWARE DECODING - 4 cores & 150MB cache
    LaunchedEffect(Unit) {
        // PURE SOFTWARE DECODING (no GPU acceleration)
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        
        // OPTIMIZED: 4 cores for software decoding (reduced from 8)
        MPVLib.setPropertyString("vd-lavc-threads", "4")
        MPVLib.setPropertyString("audio-channels", "auto") // OPTIMIZED: Auto-detect instead of 8 channels
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        
        // OPTIMIZED: 150MB cache allocation (reduced from 250MB)
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 150 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "60") // Reduced from 120 to 60 seconds
        MPVLib.setPropertyString("cache-secs", "60")
        MPVLib.setPropertyString("cache-pause", "no")
        MPVLib.setPropertyString("cache-initial", "0.5")
        
        // OPTIMIZED: Software decoding optimizations with quality reduction
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        
        // QUALITY REDUCTION FOR SPEED (as requested)
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all") // Skip loop filter
        MPVLib.setPropertyString("vd-lavc-skipidct", "all") // Skip IDCT
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
        
        // Memory and buffer optimizations
        MPVLib.setPropertyString("demuxer-max-back-bytes", "50M")
        MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
        
        // Software rendering optimizations
        MPVLib.setPropertyString("gpu-dumb-mode", "yes")
        MPVLib.setPropertyString("opengl-pbo", "yes")
        
        // Network optimizations
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1:reconnect_at_eof=1:reconnect_streamed=1")
        MPVLib.setPropertyString("network-timeout", "30")
        
        // Audio processing
        MPVLib.setPropertyString("audio-client-name", "MPVEx-Software-4Core")
        MPVLib.setPropertyString("audio-samplerate", "auto")
        
        // Additional performance flags
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("video-aspect-override", "no")
    }
    
    // OPTIMIZED: Update time and progress every 500ms with smart updates
    LaunchedEffect(Unit) {
        var lastSeconds = -1
        
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            val currentSeconds = currentPos.toInt()
            
            if (isSeeking) {
                // During seeking: show seek target in bottom left
                currentTime = seekTargetTime
                totalTime = formatTimeSimple(duration)
            } else {
                // Normal playback: only update when seconds actually change
                if (currentSeconds != lastSeconds) {
                    currentTime = formatTimeSimple(currentPos)
                    totalTime = formatTimeSimple(duration)
                    lastSeconds = currentSeconds
                }
            }
            
            // OPTIMIZED: Only update seekbar position when NOT dragging
            if (!isDragging) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            
            // Always update these for internal logic (they're cheap)
            currentPosition = currentPos
            videoDuration = duration
            
            delay(500) // OPTIMIZED: Reduced from 50ms to 500ms
        }
    }
    
    // Function to toggle video info (simplified - just filename show/hide)
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
    
    // Function to show seekbar with timeout
    fun showSeekbar() {
        showSeekbar = true
        scheduleSeekbarHide()
    }
    
    // Simple draggable progress bar handler
    fun handleProgressBarDrag(newPosition: Float) {
        // ⭐ NEW: Cancel auto-hide when using seekbar
        cancelAutoHide()
        // ⭐ NEW: Activate 480p downscaling for smooth seeking
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
        seekbarPosition = newPosition // Progress bar moves directly with finger
        
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
        
        // ⭐ NEW: Schedule hide after drag finished
        scheduleSeekbarHide()
        // Downscale will auto-revert after 600ms via downscaleJob
    }
    
    // ⭐ DEBOUNCED: Continuous drag seeking gesture handler with 16ms debouncing
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        // ⭐ NEW: Cancel auto-hide when using horizontal drag seeking
        cancelAutoHide()
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // ⭐ NEW: Activate 480p downscaling for smooth seeking
                activateSeekingMode()
                
                seekStartX = event.x
                seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                isSeeking = true
                showSeekTime = true
                lastSeekTime = 0L
                
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    val currentX = event.x
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
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSeeking) {
                    val currentX = event.x
                    val deltaX = currentX - seekStartX
                    val pixelsPerSecond = 3f / 0.033f
                    val timeDeltaSeconds = deltaX / pixelsPerSecond
                    val newPositionSeconds = seekStartPosition + timeDeltaSeconds
                    val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                    val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
                    
                    performRealTimeSeek(clampedPosition)
                    
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
                    
                    // ⭐ NEW: Schedule hide after drag finished
                    scheduleSeekbarHide()
                    // Downscale will auto-revert after 600ms via downscaleJob
                }
                return true
            }
        }
        return false
    }
    
    // Left area gesture handler with proper tap/hold detection
    fun handleLeftAreaGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                leftTapStartTime = System.currentTimeMillis()
                leftIsHolding = true
                
                // Start checking for long press after 300ms
                coroutineScope.launch {
                    delay(300)
                    if (leftIsHolding) {
                        // Add 100ms buffer delay before speed-up
                        delay(100)
                        if (leftIsHolding) {
                            // Long press detected - activate 2x speed
                            isSpeedingUp = true
                        }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - leftTapStartTime
                leftIsHolding = false
                
                if (tapDuration < 200) {
                    // Short tap - toggle seekbar
                    if (showSeekbar) {
                        showSeekbar = false
                    } else {
                        showSeekbarWithTimeout()
                    }
                }
                // Long press speed will auto-reset via LaunchedEffect
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                leftIsHolding = false
                true
            }
            else -> false
        }
    }
    
    // Right area gesture handler with proper tap/hold detection
    fun handleRightAreaGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                rightTapStartTime = System.currentTimeMillis()
                rightIsHolding = true
                
                // Start checking for long press after 300ms
                coroutineScope.launch {
                    delay(300)
                    if (rightIsHolding) {
                        // Add 100ms buffer delay before speed-up
                        delay(100)
                        if (rightIsHolding) {
                            // Long press detected - activate 2x speed
                            isSpeedingUp = true
                        }
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - rightTapStartTime
                rightIsHolding = false
                
                if (tapDuration < 200) {
                    // Short tap - toggle seekbar
                    if (showSeekbar) {
                        showSeekbar = false
                    } else {
                        showSeekbarWithTimeout()
                    }
                }
                // Long press speed will auto-reset via LaunchedEffect
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                rightIsHolding = false
                true
            }
            else -> false
        }
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
        
        // CENTER 70% - Divided into 3 areas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .align(Alignment.Center)
        ) {
            // LEFT 27% - Tap to show/hide seekbar, hold for 2x speed
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.27f)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .pointerInteropFilter { event ->
                        handleLeftAreaGesture(event)
                    }
            )
            
            // CENTER 46% - Tap for pause/resume (using horizontal seek's smooth approach)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            val currentPaused = MPVLib.getPropertyBoolean("pause") ?: false
                            if (currentPaused) {
                                // Smooth resume like horizontal seek
                                coroutineScope.launch {
                                    val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                                    MPVLib.command("seek", currentPos.toString(), "absolute", "exact")
                                    delay(100) // Small delay like horizontal seek
                                    MPVLib.setPropertyBoolean("pause", false)
                                }
                                // ⭐ NEW: Show resume feedback
                                showPlaybackFeedback("Resume")
                            } else {
                                // Immediate pause
                                MPVLib.setPropertyBoolean("pause", true)
                                // ⭐ NEW: Show pause feedback
                                showPlaybackFeedback("Pause")
                            }
                            isPausing = !currentPaused
                        }
                    )
            )
            
            // RIGHT 27% - Tap to show/hide seekbar, hold for 2x speed
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.27f)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .pointerInteropFilter { event ->
                        handleRightAreaGesture(event)
                    }
            )
        }
        
        // BOTTOM 25% - Horizontal drag seeking area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                .align(Alignment.BottomStart)
                .pointerInteropFilter { event ->
                    handleDragSeekGesture(event)
                }
        )
        
        // BOTTOM AREA - Times and Seekbar (all toggle together) - LOWERED POSITION
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = (-5).dp) 
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
                            // Current time / total time
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
        
        // VIDEO INFO - Left Side (just below the 5% toggle area)
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
        
        // ⭐ UPDATED: TOP CENTER AREA - Now shows multiple feedback types with VOLUME priority
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 80.dp) // Position below the title area
        ) {
            // ⭐ UPDATED: Priority order: Volume > 2X Speed > Seek Time > Playback Feedback
            when {
                showVolumeFeedback -> {
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
        
        // White progress bar - moves directly with finger during drag
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .background(Color.White)
        )
        
        // Invisible draggable area - NO VISIBLE THUMB
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

// Function to format time simply without milliseconds
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

// IMPROVED: Helper functions for filename detection
private fun getFileNameFromUri(uri: Uri?, context: android.content.Context): String {
    if (uri == null) return getBestAvailableFileName(context)
    
    return when {
        uri.scheme == "file" -> {
            // Direct file path - this is what file managers use
            uri.lastPathSegment?.substringBeforeLast(".") ?: getBestAvailableFileName(context)
        }
        uri.scheme == "content" -> {
            // Content URI - try to extract real filename
            getDisplayNameFromContentUri(uri, context) ?: getBestAvailableFileName(context)
        }
        uri.scheme in listOf("http", "https") -> {
            // Network URL
            uri.lastPathSegment?.substringBeforeLast(".") ?: "Online Video"
        }
        else -> getBestAvailableFileName(context)
    }
}

private fun getDisplayNameFromContentUri(uri: Uri, context: android.content.Context): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                // Try multiple column names for display name
                val displayNameIndex = cursor.getColumnIndex("_display_name")
                val displayName = if (displayNameIndex != -1) {
                    cursor.getString(displayNameIndex)?.substringBeforeLast(".")
                } else null
                
                // If no display name, try to extract from URI itself as fallback
                displayName ?: uri.lastPathSegment?.substringBeforeLast(".")
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

// NEW: Function to try multiple fallback sources for filename
private fun getBestAvailableFileName(context: android.content.Context): String {
    // Try MPV media-title first
    val mediaTitle = MPVLib.getPropertyString("media-title")
    if (mediaTitle != null && mediaTitle != "Video" && mediaTitle.isNotBlank()) {
        return mediaTitle.substringBeforeLast(".")
    }
    
    // Try MPV path as last resort
    val mpvPath = MPVLib.getPropertyString("path")
    if (mpvPath != null && mpvPath.isNotBlank()) {
        return mpvPath.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "Video" }
    }
    
    // Final fallback
    return "Video"
}
