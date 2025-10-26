package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    
    // Seekbar states
    var isSeeking by remember { mutableStateOf(false) }
    
    // ⭐ NEW: Simple flag to freeze ALL seekbar updates during speed changes
    var freezeSeekbarUpdates by remember { mutableStateOf(false) }
    
    // Drag seeking variables
    var seekStartX by remember { mutableStateOf(0f) }
    var dragSeekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // DEBOUNCING: Track last seek time for both drag and seekbar seeking
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
    var fileName by remember { mutableStateOf("file.mp4") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Get video title and filename at start and show filename
    LaunchedEffect(Unit) {
        // Get video title from MPV
        val title = MPVLib.getPropertyString("media-title") ?: "Video"
        videoTitle = title
        
        // Get filename from path
        val path = MPVLib.getPropertyString("path") ?: "file.mp4"
        fileName = path.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "file" }
        
        // Show filename at start
        showVideoInfo = 1
        
        // Auto-hide after 4 seconds
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
        }
    }
    
    // ⭐ SIMPLE UNIFIED SPEED CONTROL with seekbar freezing
    LaunchedEffect(leftIsHolding, rightIsHolding) {
        val shouldSpeedUp = leftIsHolding || rightIsHolding
        
        if (shouldSpeedUp && !isSpeedingUp) {
            // Transition to 2x - FREEZE seekbar first
            freezeSeekbarUpdates = true
            MPVLib.setPropertyDouble("speed", 2.0)
            isSpeedingUp = true
            delay(200) // Keep frozen for 200ms
            freezeSeekbarUpdates = false
            
        } else if (!shouldSpeedUp && isSpeedingUp) {
            // Transition back to normal - FREEZE seekbar first
            freezeSeekbarUpdates = true
            MPVLib.setPropertyDouble("speed", 1.0)
            isSpeedingUp = false
            delay(200) // Keep frozen for 200ms
            freezeSeekbarUpdates = false
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
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        
        // OPTIMIZED: 150MB cache allocation (reduced from 250MB)
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 150 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "60")
        MPVLib.setPropertyString("cache-secs", "60")
        MPVLib.setPropertyString("cache-pause", "no")
        MPVLib.setPropertyString("cache-initial", "0.5")
        
        // QUALITY REDUCTION FOR SPEED
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-skipidct", "all")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
    }
    
    // OPTIMIZED: Update time and progress with seekbar freezing
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
                
                // ⭐ SIMPLE: Only update seekbar when NOT frozen
                if (showSeekbar && !freezeSeekbarUpdates) {
                    seekbarPosition = currentPos.toFloat()
                    seekbarDuration = duration.toFloat()
                }
            }
            
            // Always update these for internal logic
            currentPosition = currentPos
            videoDuration = duration
            
            delay(500)
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
    
    // Function to show seekbar (no auto-hide)
    fun showSeekbar() {
        showSeekbar = true
    }
    
    // ⭐ SIMPLIFIED: Handle seekbar value change for single seekbar
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
        
        val targetPosition = newPosition.toDouble()
        
        val now = System.currentTimeMillis()
        if (now - lastSeekTime >= seekDebounceMs) {
            performRealTimeSeek(targetPosition)
            lastSeekTime = now
        }
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
    }
    
    // ⭐ SIMPLIFIED: Handle seekbar value change finished for single seekbar
    fun handleSeekbarValueChangeFinished() {
        if (isSeeking) {
            isSeeking = false
            showSeekTime = false
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            wasPlayingBeforeSeek = false
        }
    }
    
    // DEBOUNCED: Continuous drag seeking gesture handler
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                seekStartX = event.x
                dragSeekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
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
                    
                    val newPositionSeconds = dragSeekStartPosition + timeDeltaSeconds
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
                    val newPositionSeconds = dragSeekStartPosition + timeDeltaSeconds
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
                    dragSeekStartPosition = 0.0
                    wasPlayingBeforeSeek = false
                }
                return true
            }
        }
        return false
    }
    
    // Left area gesture handler - RESTORED to 400ms
    fun handleLeftAreaGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                leftTapStartTime = System.currentTimeMillis()
                leftIsHolding = true
                
                coroutineScope.launch {
                    delay(400) // ⭐ RESTORED to 400ms
                    if (leftIsHolding) {
                        // Speed up will be handled by the unified LaunchedEffect
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - leftTapStartTime
                leftIsHolding = false
                
                if (tapDuration < 200) {
                    if (showSeekbar) {
                        showSeekbar = false
                    } else {
                        showSeekbar()
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
    
    // Right area gesture handler - RESTORED to 400ms
    fun handleRightAreaGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                rightTapStartTime = System.currentTimeMillis()
                rightIsHolding = true
                
                coroutineScope.launch {
                    delay(400) // ⭐ RESTORED to 400ms
                    if (rightIsHolding) {
                        // Speed up will be handled by the unified LaunchedEffect
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - rightTapStartTime
                rightIsHolding = false
                
                if (tapDuration < 200) {
                    if (showSeekbar) {
                        showSeekbar = false
                    } else {
                        showSeekbar()
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
                    onClick = { toggleVideoInfo() }
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
                    .pointerInteropFilter { event -> handleLeftAreaGesture(event) }
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
                            if (currentPaused) {
                                coroutineScope.launch {
                                    val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                                    MPVLib.command("seek", currentPos.toString(), "absolute", "exact")
                                    delay(100)
                                    MPVLib.setPropertyBoolean("pause", false)
                                }
                            } else {
                                MPVLib.setPropertyBoolean("pause", true)
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
                    .pointerInteropFilter { event -> handleRightAreaGesture(event) }
            )
        }
        
        // BOTTOM 25% - Horizontal drag seeking area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                .align(Alignment.BottomStart)
                .pointerInteropFilter { event -> handleDragSeekGesture(event) }
        )
        
        // BOTTOM AREA - Times and Seekbar
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
                    
                    // ⭐ SINGLE SEEKBAR with YouTube styling
                    YouTubeStyleSeekbar(
                        position = seekbarPosition,
                        duration = seekbarDuration,
                        isSeeking = isSeeking,
                        onValueChange = { handleSeekbarValueChange(it) },
                        onValueChangeFinished = { handleSeekbarValueChangeFinished() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    )
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
        
        // TOP CENTER AREA - 2X Speed and Seek Time
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 80.dp)
        ) {
            if (isSpeedingUp) {
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
            
            if (showSeekTime && !isSpeedingUp) {
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
        }
    }
}

// ⭐ SINGLE SEEKBAR that mimics YouTube's feel
@Composable
fun YouTubeStyleSeekbar(
    position: Float,
    duration: Float,
    isSeeking: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Background track (always visible)
        Seeker(
            value = 0f,
            range = 0f..duration,
            onValueChange = { },
            onValueChangeFinished = { },
            modifier = Modifier.fillMaxSize(),
            colors = SeekerDefaults.seekerColors(
                progressColor = Color.Transparent,
                thumbColor = Color.Transparent,
                trackColor = Color.Gray.copy(alpha = 0.4f),
                readAheadColor = Color.Transparent,
            )
        )
        
        // Progress (changes color during seeking)
        Seeker(
            value = position.coerceIn(0f, duration),
            range = 0f..duration,
            onValueChange = { },
            onValueChangeFinished = { },
            modifier = Modifier.fillMaxSize(),
            colors = SeekerDefaults.seekerColors(
                progressColor = if (isSeeking) Color.Red else Color.White,
                thumbColor = Color.Transparent,
                trackColor = Color.Transparent,
                readAheadColor = Color.Transparent,
            )
        )
        
        // Interactive layer with thumb
        Seeker(
            value = position.coerceIn(0f, duration),
            range = 0f..duration,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxSize(),
            colors = SeekerDefaults.seekerColors(
                progressColor = Color.Transparent,
                thumbColor = Color.White,
                trackColor = Color.Transparent,
                readAheadColor = Color.Transparent,
            )
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
