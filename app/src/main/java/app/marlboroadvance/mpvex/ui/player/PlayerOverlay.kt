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
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Start auto-hide when seekbar is shown - FIXED: Don't auto-hide during seeking
    LaunchedEffect(showSeekbar, isSeeking) {
        if (showSeekbar && !isSeeking) {
            autoHideJob?.cancel()
            autoHideJob = coroutineScope.launch {
                delay(4000)
                if (!isSeeking) { // Double check we're not seeking
                    showSeekbar = false
                }
            }
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
    
    // Update time and progress every 100ms - OPTIMIZED: Only update when needed
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!isSeeking) { // Only update position if not seeking (to reduce lag)
                val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
                
                currentTime = formatTimeSimple(currentPos)
                totalTime = formatTimeSimple(duration)
                currentPosition = currentPos
                videoDuration = duration
                
                // Update seekbar values
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            } else {
                // When seeking, only update duration if needed
                val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
                if (duration != videoDuration) {
                    videoDuration = duration
                    seekbarDuration = duration.toFloat()
                    totalTime = formatTimeSimple(duration)
                }
            }
            
            delay(100)
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
    
    // Handle seekbar value change - FIXED: Cancel auto-hide immediately
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
        
        val targetPosition = newPosition.toDouble()
        performRealTimeSeek(targetPosition)
        seekTargetTime = formatTimeSimple(targetPosition)
    }
    
    // Handle seekbar value change finished - FIXED: Restart auto-hide properly
    fun handleSeekbarValueChangeFinished() {
        if (isSeeking) {
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            wasPlayingBeforeSeek = false
            
            // Restart auto-hide after seeking ends with proper check
            autoHideJob?.cancel()
            if (showSeekbar) {
                autoHideJob = coroutineScope.launch {
                    delay(4000)
                    if (!isSeeking) { // Double check we're not seeking again
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
                    // Short tap - toggle seekbar
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
                    // Short tap - toggle seekbar
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
        // CENTER AREA - Tap for pause/resume
        Box(
            modifier = Modifier
                .fillMaxWidth(0.73f)
                .fillMaxHeight(0.7f)
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
        
        // BOTTOM 25% - Continuous drag seeking (above seekbar)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                .align(Alignment.BottomStart)
                .offset(y = (-20).dp)
                .pointerInteropFilter { event ->
                    handleDragSeekGesture(event)
                }
        )
        
        // BOTTOM AREA - Times and Seekbar (all toggle together)
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp) // Slightly increased height for better spacing
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 24.dp) // Increased horizontal padding for edge spacing
                    .offset(y = (-16).dp) // Moved up a bit more
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp) // Increased gap between time and seekbar
                ) {
                    // Merged time display - current/total
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .padding(bottom = 4.dp) // Extra bottom padding for time
                    ) {
                        Text(
                            text = "$currentTime / $totalTime",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .background(Color.DarkGray.copy(alpha = 0.8f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    
                    // Seekbar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp) // Reduced seekbar height for lighter feel
                    ) {
                        CustomSeekbar(
                            position = seekbarPosition,
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
        
        // LEFT 27% - Tap to show/hide seekbar, hold for 2x speed
        Box(
            modifier = Modifier
                .fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterStart)
                .pointerInteropFilter { event ->
                    handleLeftAreaGesture(event)
                }
        )
        
        // RIGHT 27% - Tap to show/hide seekbar, hold for 2x speed
        Box(
            modifier = Modifier
                .fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterEnd)
                .pointerInteropFilter { event ->
                    handleRightAreaGesture(event)
                }
        )
        
        // TOP 5% - Ignore area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
        )
        
        // Center seek time - shows target position during seeking (DARK GREY BACKGROUND)
        if (showSeekTime) {
            Text(
                text = seekTargetTime,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-40).dp)
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
    modifier: Modifier = Modifier,
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
            progressColor = Color.White, // White progress
            thumbColor = Color.White, // White thumb
            trackColor = Color.Gray.copy(alpha = 0.6f), // Grey semi-transparent track
            readAheadColor = Color.Gray, // Grey read ahead
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
