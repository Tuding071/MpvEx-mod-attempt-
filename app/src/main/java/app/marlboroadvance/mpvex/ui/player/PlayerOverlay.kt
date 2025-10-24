package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
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
    
    // For smooth seeking animation
    var userSeekPosition by remember { mutableStateOf<Float?>(null) }
    
    // Drag seeking variables
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // ⚡ OPTIMIZED FOR 60FPS: 16ms debounce for buttery smooth seeking
    var lastSeekTime by remember { mutableStateOf(0L) }
    val seekDebounceMs = 16L // 16ms = perfect 60fps seeking
    
    // Tap detection variables
    var leftTapStartTime by remember { mutableStateOf(0L) }
    var rightTapStartTime by remember { mutableStateOf(0L) }
    var leftIsHolding by remember { mutableStateOf(false) }
    var rightIsHolding by remember { mutableStateOf(false) }
    
    // Auto-hide control
    var autoHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Video pause state with optimized refresh
    var isVideoPaused by remember { mutableStateOf(false) }
    var refreshPauseState by remember { mutableStateOf(0) }
    
    // Video title and filename state
    var showVideoInfo by remember { mutableStateOf(0) }
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("file.mp4") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // ⚡ PERFORMANCE: Dedicated IO dispatcher for background operations
    val coroutineScope = remember { CoroutineScope(Dispatchers.IO) }

    // Function to trigger pause state refresh - MOVED BEFORE FIRST USAGE
    fun refreshPauseState() {
        refreshPauseState++ // Increment to trigger LaunchedEffect
    }
    
    // ⚡ PURE SPEED OPTIMIZATION - REMOVE ALL QUALITY FEATURES
    LaunchedEffect(Unit) {
        // MAXIMUM HARDWARE ACCELERATION - VULKAN
        MPVLib.setPropertyString("hwdec", "vulkan")
        MPVLib.setPropertyString("gpu-api", "vulkan")
        MPVLib.setPropertyString("vo", "gpu")
        
        // ⚡ DISABLE ALL POST-PROCESSING FOR MAXIMUM SPEED
        MPVLib.setPropertyString("scale", "bilinear") // Fastest scaling
        MPVLib.setPropertyString("dscale", "bilinear") // Fastest downscaling
        MPVLib.setPropertyString("cscale", "bilinear") // Fastest chroma scaling
        MPVLib.setPropertyString("tscale", "box") // Fastest interpolation
        
        // ⚡ DISABLE ALL QUALITY ENHANCEMENTS
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("deband-iterations", "0")
        MPVLib.setPropertyString("deband-threshold", "0")
        MPVLib.setPropertyString("deband-range", "0")
        MPVLib.setPropertyString("deband-grain", "0")
        
        // ⚡ DISABLE COLOR MANAGEMENT AND HDR PROCESSING
        MPVLib.setPropertyString("target-colorspace-hint", "no")
        MPVLib.setPropertyString("hdr-compute-peak", "no")
        MPVLib.setPropertyString("tone-mapping", "no")
        MPVLib.setPropertyString("gamut-mapping-mode", "no")
        
        // ⚡ MAXIMUM PERFORMANCE DECODING
        MPVLib.setPropertyString("vd-lavc-dr", "yes") // Direct rendering
        MPVLib.setPropertyString("vd-lavc-threads", "8") // Optimized thread count
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all") // Skip all filters
        MPVLib.setPropertyString("vd-lavc-skipidct", "all") // Skip IDCT
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes") // Fast h264 assembly
        
        // ⚡ MINIMAL VIDEO PROCESSING
        MPVLib.setPropertyString("video-threads", "2") // Minimal video threads
        MPVLib.setPropertyString("demuxer-lavf-threads", "2") // Minimal demux threads
        
        // ⚡ OPTIMIZED CACHE FOR SPEED
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 256 * 1024 * 1024) // 256MB cache
        MPVLib.setPropertyString("demuxer-readahead-secs", "60")
        MPVLib.setPropertyString("cache-secs", "60")
        
        // ⚡ DISABLE GPU SHADER CACHE AND COMPLEX FEATURES
        MPVLib.setPropertyString("gpu-shader-cache", "no")
        MPVLib.setPropertyString("gpu-early-flush", "no")
        MPVLib.setPropertyString("opengl-pbo", "no") // Disable for Vulkan
        
        // ⚡ SIMPLE AUDIO PROCESSING
        MPVLib.setPropertyString("audio-client-name", "MPVEx-Speed")
        MPVLib.setPropertyString("audio-channels", "stereo") // Simple stereo
        MPVLib.setPropertyString("audio-samplerate", "48000") // Standard quality
        MPVLib.setPropertyString("audio-buffer", "0.1") // Minimal latency
        
        // ⚡ NETWORK OPTIMIZATIONS
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1")
        MPVLib.setPropertyString("network-timeout", "5") // Very aggressive timeout
        
        // ⚡ PERFORMANCE PROFILE
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("msg-level", "all=error") // Reduce logging overhead
        
        // ⚡ DISABLE UNNECESSARY FEATURES
        MPVLib.setPropertyString("sub-auto", "no") // No subtitles
        MPVLib.setPropertyString("audio-display", "no") // No cover art
        MPVLib.setPropertyString("cover-art-auto", "no") // No cover art
        
        // Get video info
        val title = MPVLib.getPropertyString("media-title") ?: "Video"
        videoTitle = title
        
        val path = MPVLib.getPropertyString("path") ?: "file.mp4"
        fileName = path.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "file" }
        
        showVideoInfo = 1
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
        }
    }

    // Start auto-hide when seekbar is shown
    LaunchedEffect(showSeekbar, isSeeking) {
        if (showSeekbar && !isSeeking) {
            autoHideJob?.cancel()
            autoHideJob = coroutineScope.launch {
                delay(3000) // Shorter delay for performance
                if (!isSeeking) {
                    showSeekbar = false
                }
            }
        }
    }
    
    // Handle speed reset when not holding
    LaunchedEffect(leftIsHolding, rightIsHolding) {
        if (!leftIsHolding && !rightIsHolding && isSpeedingUp) {
            delay(50) // Faster reset
            MPVLib.setPropertyDouble("speed", 1.0)
            isSpeedingUp = false
        }
    }
    
    // ⚡ OPTIMIZED: High-frequency state updates with reduced overhead
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            
            currentTime = formatTimeSimple(currentPos)
            totalTime = formatTimeSimple(duration)
            
            currentPosition = currentPos
            videoDuration = duration
            
            seekbarPosition = currentPos.toFloat()
            seekbarDuration = duration.toFloat()
            
            // ⚡ OPTIMIZED: Adaptive update rate based on seeking state
            delay(if (isSeeking) 16L else 33L) // 60fps when seeking, 30fps otherwise
        }
    }
    
    // ⚡ OPTIMIZED: Faster speed transitions
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            delay(50) // Faster speed activation
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            delay(50)
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    // ⚡ OPTIMIZED: Faster pause/resume handling
    LaunchedEffect(pendingPauseResume) {
        if (pendingPauseResume) {
            delay(25) // Minimal delay
            viewModel.pauseUnpause()
            pendingPauseResume = false
            refreshPauseState()
        }
    }
    
    // Optimized pause state refresh
    LaunchedEffect(refreshPauseState) {
        if (refreshPauseState > 0) {
            isVideoPaused = MPVLib.getPropertyBoolean("pause") ?: false
            delay(100)
            isVideoPaused = MPVLib.getPropertyBoolean("pause") ?: false
        }
    }
    
    fun toggleVideoInfo() {
        showVideoInfo = when (showVideoInfo) {
            0 -> 1
            1 -> 2
            else -> 0
        }
        
        if (showVideoInfo != 0) {
            videoInfoJob?.cancel()
            videoInfoJob = coroutineScope.launch {
                delay(3000)
                showVideoInfo = 0
            }
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        2 -> videoTitle
        else -> ""
    }
    
    // ⚡ ULTRA PERFORMANCE SEEKING: Direct hardware-accelerated seeking
    fun performRealTimeSeek(targetPosition: Double) {
        // Use exact seeking with hardware acceleration
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
    }
    
    fun showSeekbarWithAutoHide() {
        showSeekbar = true
        autoHideJob?.cancel()
        if (!isSeeking) {
            autoHideJob = coroutineScope.launch {
                delay(3000)
                if (!isSeeking) {
                    showSeekbar = false
                }
            }
        }
    }
    
    // ⚡ ULTRA-RESPONSIVE: 16ms debounced seeking for 60fps
    fun handleSeekbarValueChange(newPosition: Float) {
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            lastSeekTime = 0L
            
            autoHideJob?.cancel()
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        
        userSeekPosition = newPosition
        val targetPosition = newPosition.toDouble()
        
        val now = System.currentTimeMillis()
        if (now - lastSeekTime >= seekDebounceMs) {
            performRealTimeSeek(targetPosition)
            lastSeekTime = now
        }
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
    }
    
    fun handleSeekbarValueChangeFinished() {
        if (isSeeking) {
            userSeekPosition = null
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(50) // Faster resume
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            wasPlayingBeforeSeek = false
            
            autoHideJob?.cancel()
            if (showSeekbar) {
                autoHideJob = coroutineScope.launch {
                    delay(3000)
                    if (!isSeeking) {
                        showSeekbar = false
                    }
                }
            }
        }
    }
    
    // ⚡ HIGH-PRECISION DRAG SEEKING: 16ms debounce for 60fps
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                seekStartX = event.x
                seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                isSeeking = true
                showSeekTime = true
                lastSeekTime = 0L
                
                autoHideJob?.cancel()
                
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    val currentX = event.x
                    val deltaX = currentX - seekStartX
                    
                    // ⚡ 60FPS OPTIMIZED: Perfect precision for 60fps
                    val pixelsPerSecond = 3f / 0.033f // Optimized for 60fps
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
                    val pixelsPerSecond = 3f / 0.033f // 60fps optimized
                    val timeDeltaSeconds = deltaX / pixelsPerSecond
                    val newPositionSeconds = seekStartPosition + timeDeltaSeconds
                    val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                    val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
                    
                    performRealTimeSeek(clampedPosition)
                    
                    if (wasPlayingBeforeSeek) {
                        coroutineScope.launch {
                            delay(50) // Faster resume
                            MPVLib.setPropertyBoolean("pause", false)
                        }
                    }
                    
                    isSeeking = false
                    showSeekTime = false
                    seekStartX = 0f
                    seekStartPosition = 0.0
                    wasPlayingBeforeSeek = false
                    
                    autoHideJob = coroutineScope.launch {
                        delay(3000)
                        if (!isSeeking) {
                            showSeekbar = false
                        }
                    }
                }
                return true
            }
        }
        return false
    }
    
    // ⚡ OPTIMIZED: Faster gesture detection
    fun handleLeftAreaGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                leftTapStartTime = System.currentTimeMillis()
                leftIsHolding = true
                
                coroutineScope.launch {
                    delay(250) // Faster long-press detection
                    if (leftIsHolding) {
                        isSpeedingUp = true
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - leftTapStartTime
                leftIsHolding = false
                
                if (tapDuration < 150) { // Faster tap detection
                    refreshPauseState()
                    if (showSeekbar) {
                        showSeekbar = false
                        autoHideJob?.cancel()
                    } else {
                        showSeekbarWithAutoHide()
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                leftIsHolding = false
                true
            }
            else -> false
        }
    }
    
    fun handleRightAreaGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                rightTapStartTime = System.currentTimeMillis()
                rightIsHolding = true
                
                coroutineScope.launch {
                    delay(250)
                    if (rightIsHolding) {
                        isSpeedingUp = true
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - rightTapStartTime
                rightIsHolding = false
                
                if (tapDuration < 150) {
                    refreshPauseState()
                    if (showSeekbar) {
                        showSeekbar = false
                        autoHideJob?.cancel()
                    } else {
                        showSeekbarWithAutoHide()
                    }
                }
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
                            refreshPauseState()
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
                    // Time display with pause indicator
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
                            
                            if (isVideoPaused) {
                                Text(
                                    text = "Pause",
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
                    
                    // Seekbar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    ) {
                        CustomSeekbar(
                            position = if (isSeeking) (userSeekPosition ?: seekbarPosition) else seekbarPosition,
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
        
        // VIDEO INFO - Top Center (filename/title toggle)
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
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
                    color = Color.Yellow, // More visible color
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 60.dp)
                    .background(Color.DarkGray.copy(alpha = 0.9f))
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
                    .background(Color.DarkGray.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        
        // ⚡ PERFORMANCE INDICATOR - Shows when speed mode is active
        Text(
            text = "SPEED",
            style = TextStyle(
                color = Color.Green,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-8).dp, y = 8.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
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
            progressColor = Color.Yellow, // High visibility
            thumbColor = Color.White,
            trackColor = Color.Gray.copy(alpha = 0.6f),
            readAheadColor = Color.Gray,
        ),
    )
}

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
