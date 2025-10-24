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
    
    // Tap detection variables
    var leftTapStartTime by remember { mutableStateOf(0L) }
    var rightTapStartTime by remember { mutableStateOf(0L) }
    var leftIsHolding by remember { mutableStateOf(false) }
    var rightIsHolding by remember { mutableStateOf(false) }
    
    // Auto-hide control
    var autoHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Video pause state with optimized refresh
    var isVideoPaused by remember { mutableStateOf(false) }
    var refreshPauseState by remember { mutableStateOf(0) } // Counter to trigger refresh
    
    // Video title and filename state
    var showVideoInfo by remember { mutableStateOf(0) } // 0=hide, 1=filename, 2=title
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("file.mp4") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Start auto-hide when seekbar is shown
    LaunchedEffect(showSeekbar, isSeeking) {
        if (showSeekbar && !isSeeking) {
            autoHideJob?.cancel()
            autoHideJob = coroutineScope.launch {
                delay(4000)
                if (!isSeeking) {
                    showSeekbar = false
                }
            }
        }
    }
    
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
    
    // Handle speed reset when not holding
    LaunchedEffect(leftIsHolding, rightIsHolding) {
        if (!leftIsHolding && !rightIsHolding && isSpeedingUp) {
            delay(100)
            MPVLib.setPropertyDouble("speed", 1.0)
            isSpeedingUp = false
        }
    }
    
    // Optimized pause state refresh - only when triggered
    LaunchedEffect(refreshPauseState) {
        if (refreshPauseState > 0) {
            // First immediate refresh
            val paused = MPVLib.getPropertyBoolean("pause") ?: false
            isVideoPaused = paused
            
            // Second refresh after 250ms
            delay(250)
            val paused2 = MPVLib.getPropertyBoolean("pause") ?: false
            isVideoPaused = paused2
            
            // Third refresh after another 250ms
            delay(250)
            val paused3 = MPVLib.getPropertyBoolean("pause") ?: false
            isVideoPaused = paused3
        }
    }
    
    // Function to trigger pause state refresh
    fun refreshPauseState() {
        refreshPauseState++ // Increment to trigger LaunchedEffect
    }
    
    // Function to toggle video info (filename/title)
    fun toggleVideoInfo() {
        showVideoInfo = when (showVideoInfo) {
            0 -> 1 // Show filename
            1 -> 2 // Show title
            else -> 0 // Hide
        }
        
        if (showVideoInfo != 0) {
            // Auto-hide after 4 seconds
            videoInfoJob?.cancel()
            videoInfoJob = coroutineScope.launch {
                delay(4000)
                showVideoInfo = 0
            }
        }
    }
    
    // Get display text based on current state
    val displayText = when (showVideoInfo) {
        1 -> fileName
        2 -> videoTitle
        else -> ""
    }
    
    // Aggressive software decoding optimization with large cache
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        
        // Large cache settings for smooth seeking
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 100 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "60")
        MPVLib.setPropertyString("cache-secs", "60")
        MPVLib.setPropertyString("cache-pause", "no")
        
        // Frame-accurate seeking settings
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
    }
    
    // Update time and progress every 50ms
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            
            // Always update time display
            currentTime = formatTimeSimple(currentPos)
            totalTime = formatTimeSimple(duration)
            
            // Always update position
            currentPosition = currentPos
            videoDuration = duration
            
            // Update seekbar values
            seekbarPosition = currentPos.toFloat()
            seekbarDuration = duration.toFloat()
            
            delay(50)
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
    
    // Handle pause/resume with different delays
    LaunchedEffect(pendingPauseResume) {
        if (pendingPauseResume) {
            if (isPausing) {
                delay(50)
            } else {
                delay(150)
            }
            viewModel.pauseUnpause()
            pendingPauseResume = false
            // Refresh pause state after pause/resume action
            refreshPauseState()
        }
    }
    
    // Simple real-time seeking function
    fun performRealTimeSeek(targetPosition: Double) {
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
    }
    
    // Function to show seekbar and manage auto-hide
    fun showSeekbarWithAutoHide() {
        showSeekbar = true
        autoHideJob?.cancel()
        if (!isSeeking) {
            autoHideJob = coroutineScope.launch {
                delay(4000)
                if (!isSeeking) {
                    showSeekbar = false
                }
            }
        }
    }
    
    // Handle seekbar value change
    fun handleSeekbarValueChange(newPosition: Float) {
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            
            // Cancel auto-hide immediately when seeking starts
            autoHideJob?.cancel()
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        
        // Update user position
        userSeekPosition = newPosition
        
        val targetPosition = newPosition.toDouble()
        performRealTimeSeek(targetPosition)
        seekTargetTime = formatTimeSimple(targetPosition)
        
        // Update current time during seeking
        currentTime = formatTimeSimple(targetPosition)
    }
    
    // Handle seekbar value change finished
    fun handleSeekbarValueChangeFinished() {
        if (isSeeking) {
            // Clear user position
            userSeekPosition = null
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            wasPlayingBeforeSeek = false
            
            // Restart auto-hide after seeking ends
            autoHideJob?.cancel()
            if (showSeekbar) {
                autoHideJob = coroutineScope.launch {
                    delay(4000)
                    if (!isSeeking) {
                        showSeekbar = false
                    }
                }
            }
        }
    }
    
    // Continuous drag seeking gesture handler for bottom area
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                seekStartX = event.x
                seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                isSeeking = true
                showSeekTime = true
                
                // Cancel auto-hide immediately
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
                    
                    val pixelsPerSecond = 3f / 0.033f
                    val timeDeltaSeconds = deltaX / pixelsPerSecond
                    
                    val newPositionSeconds = seekStartPosition + timeDeltaSeconds
                    val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                    val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
                    
                    performRealTimeSeek(clampedPosition)
                    seekTargetTime = formatTimeSimple(clampedPosition)
                    // Update current time during horizontal seeking
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
                    
                    // Restart auto-hide
                    autoHideJob = coroutineScope.launch {
                        delay(4000)
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
                    // Short tap - toggle seekbar and refresh pause state
                    refreshPauseState()
                    if (showSeekbar) {
                        showSeekbar = false
                        autoHideJob?.cancel()
                    } else {
                        showSeekbarWithAutoHide()
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
                    // Short tap - toggle seekbar and refresh pause state
                    refreshPauseState()
                    if (showSeekbar) {
                        showSeekbar = false
                        autoHideJob?.cancel()
                    } else {
                        showSeekbarWithAutoHide()
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
                            refreshPauseState() // Refresh pause state on center tap
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
                            
                            // Pause indicator (only shown when video is paused)
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
