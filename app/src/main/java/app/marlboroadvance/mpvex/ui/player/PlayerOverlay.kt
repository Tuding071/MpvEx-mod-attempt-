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
import androidx.compose.ui.text.style.TextOverflow
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
    
    // ⭐ DETACHED BALL ANIMATION: Separate states for ball vs progress bar
    var visualProgressPosition by remember { mutableStateOf(0f) } // White progress bar position
    var ballPosition by remember { mutableStateOf(0f) } // White ball position
    var isDragging by remember { mutableStateOf(false) } // Track if ball is being dragged
    
    // For smooth seeking animation
    var userSeekPosition by remember { mutableStateOf<Float?>(null) }
    
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
    
    // ⭐ SIMPLIFIED: Video name state from URI/URL
    var showVideoName by remember { mutableStateOf(false) } // Show/hide video name
    var displayName by remember { mutableStateOf("") } // Extracted from URI
    var videoNameJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // ⭐ ANIMATED PROGRESS POSITION: Smooth animation for progress bar
    val animatedProgressPosition by animateFloatAsState(
        targetValue = if (isDragging) visualProgressPosition else seekbarPosition,
        animationSpec = if (isDragging) {
            tween(durationMillis = 0) // Instant freeze during dragging
        } else {
            tween(durationMillis = 200) // Smooth catch-up after release
        },
        label = "progressAnimation"
    )
    
    // ⭐ SIMPLIFIED: Get display name from URI/URL path
    LaunchedEffect(Unit) {
        // Get the path/URI from MPV
        val path = MPVLib.getPropertyString("path") ?: ""
        
        // Extract clean filename from URI/URL path
        displayName = when {
            path.isNotEmpty() && path != "null" -> {
                // Extract filename from path/URI
                val fileName = if (path.contains("/")) {
                    path.substringAfterLast("/")
                } else {
                    path
                }
                // Remove file extension and clean up
                val cleanName = fileName.substringBeforeLast(".").trim()
                if (cleanName.isNotEmpty()) cleanName else "Video"
            }
            else -> "Video"
        }
        
        // Show briefly at start (4 seconds)
        showVideoName = true
        videoNameJob?.cancel()
        videoNameJob = coroutineScope.launch {
            delay(4000)
            showVideoName = false
        }
    }
    
    // Handle speed reset when not holding
    LaunchedEffect(leftIsHolding, rightIsHolding) {
        if (!leftIsHolding && !rightIsHolding && isSpeedingUp) {
            delay(100)
            MPVLib.setPropertyDouble("speed", 1.0)
            isSpeedingUp = false
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
            
            // OPTIMIZED: Only update seekbar position when NOT seeking AND seekbar is visible
            if (showSeekbar && !isSeeking && !isDragging) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            
            // Always update these for internal logic (they're cheap)
            currentPosition = currentPos
            videoDuration = duration
            
            delay(500) // OPTIMIZED: Reduced from 50ms to 500ms
        }
    }
    
    // Handle speed transitions with 100ms delay
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            delay(100)
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            delay(100)
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    // OPTIMIZED: Handle pause/resume WITHOUT delays
    LaunchedEffect(pendingPauseResume) {
        if (pendingPauseResume) {
            viewModel.pauseUnpause() // No delays - instant response
            pendingPauseResume = false
        }
    }
    
    // ⭐ SIMPLIFIED: Toggle video name (show/hide)
    fun toggleVideoName() {
        showVideoName = !showVideoName
        
        if (showVideoName) {
            // Auto-hide after 4 seconds when shown
            videoNameJob?.cancel()
            videoNameJob = coroutineScope.launch {
                delay(4000)
                showVideoName = false
            }
        } else {
            // Cancel auto-hide when manually hidden
            videoNameJob?.cancel()
        }
    }
    
    // Simple real-time seeking function
    fun performRealTimeSeek(targetPosition: Double) {
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
    }
    
    // Function to show seekbar (no auto-hide)
    fun showSeekbar() {
        showSeekbar = true
    }
    
    // ⭐ DEBOUNCED: Handle seekbar value change with 16ms debouncing
    fun handleSeekbarValueChange(newPosition: Float) {
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            lastSeekTime = 0L
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        
        // ⭐ DETACHED BALL: Set up dragging state
        if (!isDragging) {
            isDragging = true
            visualProgressPosition = seekbarPosition // Freeze progress bar at current position
        }
        
        userSeekPosition = newPosition
        ballPosition = newPosition // Only ball moves during dragging
        
        val targetPosition = newPosition.toDouble()
        
        val now = System.currentTimeMillis()
        if (now - lastSeekTime >= seekDebounceMs) {
            performRealTimeSeek(targetPosition)
            lastSeekTime = now
        }
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
    }
    
    // Handle seekbar value change finished
    fun handleSeekbarValueChangeFinished() {
        if (isSeeking) {
            userSeekPosition = null
            
            // ⭐ DETACHED BALL: End dragging and let progress bar catch up
            isDragging = false
            visualProgressPosition = ballPosition // Progress bar catches up to ball position
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            wasPlayingBeforeSeek = false
        }
    }
    
    // ⭐ DEBOUNCED: Continuous drag seeking gesture handler with 16ms debouncing
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
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
                        // Long press detected - activate 2x speed
                        isSpeedingUp = true
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
                        showSeekbar()
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
                        // Long press detected - activate 2x speed
                        isSpeedingUp = true
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
                        showSeekbar()
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
        // TOP 5% - Video name toggle area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        toggleVideoName()
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
            
            // CENTER 46% - Tap for pause/resume
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
                            isPausing = !currentPaused
                            pendingPauseResume = true
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
        
        // BOTTOM AREA - Times and Seekbar (all toggle together)
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = (-14).dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    
                    // Seekbar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    ) {
                        CustomSeekbar(
                            // ⭐ DETACHED BALL: Use animated position for smooth progress bar
                            position = animatedProgressPosition,
                            duration = seekbarDuration,
                            readAheadValue = 0f,
                            onValueChange = { handleSeekbarValueChange(it) },
                            onValueChangeFinished = { handleSeekbarValueChangeFinished() },
                            chapters = persistentListOf(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        
        // ⭐ SIMPLIFIED: Video name display (from URI/URL)
        if (showVideoName && displayName.isNotEmpty()) {
            Text(
                text = displayName,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // 2X Speed feedback - Top Center
        if (isSpeedingUp) {
            Text(
                text = "2X",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 60.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        
        // Center seek time - shows target position during seeking
        if (showSeekTime) {
            Text(
                text = seekTargetTime,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 60.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun CustomSeekbar(
    position: Float,
    duration: Float,
    readAheadValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    chapters: ImmutableList<Segment>,
    modifier: Modifier = Modifier
) {
    Seeker(
        value = position.coerceIn(0f, duration),
        range = 0f..duration,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        readAheadValue = readAheadValue,
        segments = chapters
            .filter { it.start in 0f..duration }
            .let { (if (it.isNotEmpty() && it[0].start != 0f) persistentListOf(Segment("", 0f)) + it else it) + it },
        modifier = modifier,
        colors = SeekerDefaults.seekerColors(
            progressColor = Color.White,
            thumbColor = Color.White,
            trackColor = Color.Gray.copy(alpha = 0.6f),
            readAheadColor = Color.Gray,
        ),
    )
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
