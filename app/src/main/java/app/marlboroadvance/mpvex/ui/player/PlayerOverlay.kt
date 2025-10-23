package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    
    // Drag seeking variables
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // Video progress for seekbar
    var currentPosition by remember { mutableStateOf(0.0) }
    var videoDuration by remember { mutableStateOf(1.0) }
    
    // Slider state
    var userSliderPosition by remember { mutableStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = if (isSeeking) userSliderPosition else (currentPosition / videoDuration).coerceIn(0.0, 1.0).toFloat(),
        label = "seekbar_animation"
    )
    
    // Tap detection variables
    var leftTapStartTime by remember { mutableStateOf(0L) }
    var rightTapStartTime by remember { mutableStateOf(0L) }
    var leftIsHolding by remember { mutableStateOf(false) }
    var rightIsHolding by remember { mutableStateOf(false) }
    
    // Auto-hide control
    var autoHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Start auto-hide when seekbar is shown
    LaunchedEffect(showSeekbar) {
        if (showSeekbar && !isSeeking) {
            autoHideJob?.cancel()
            autoHideJob = coroutineScope.launch {
                delay(4000)
                showSeekbar = false
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
    
    // Update time and progress every 100ms
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            
            currentTime = formatTimeSimple(currentPos)
            totalTime = formatTimeSimple(duration)
            currentPosition = currentPos
            videoDuration = duration
            
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
                showSeekbar = false
            }
        }
    }
    
    // Calculate seek position from X coordinate - FIXED to use seekbar bounds
    fun calculateSeekPosition(x: Float): Double {
        val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
        val horizontalPadding = 64f // 64dp on each side
        
        // Calculate percentage within seekbar (with padding) - FIXED BOUNDS
        val availableWidth = screenWidth - (horizontalPadding * 2)
        val relativeX = (x - horizontalPadding).coerceIn(0f, availableWidth)
        val progressPercent = relativeX / availableWidth
        
        // Convert percentage to video time
        return progressPercent * videoDuration
    }
    
    // PRECISE SLIDER SEEKING - Touch position matches exactly
    fun handleSliderSeek(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel auto-hide during seeking
                autoHideJob?.cancel()
                showSeekbar = true
                
                wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                isSeeking = true
                showSeekTime = true
                
                // Calculate position based on EXACT touch point within seekbar bounds
                val targetPosition = calculateSeekPosition(event.x)
                userSliderPosition = (targetPosition / videoDuration).coerceIn(0.0, 1.0).toFloat()
                
                // Perform initial seek
                performRealTimeSeek(targetPosition)
                seekTargetTime = formatTimeSimple(targetPosition)
                
                // Pause video when seeking starts
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    // Calculate target position using EXACT touch point within seekbar bounds
                    val targetPosition = calculateSeekPosition(event.x)
                    userSliderPosition = (targetPosition / videoDuration).coerceIn(0.0, 1.0).toFloat()
                    
                    // Perform real-time seek
                    performRealTimeSeek(targetPosition)
                    seekTargetTime = formatTimeSimple(targetPosition)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSeeking) {
                    // Final position using EXACT touch point within seekbar bounds
                    val targetPosition = calculateSeekPosition(event.x)
                    userSliderPosition = (targetPosition / videoDuration).coerceIn(0.0, 1.0).toFloat()
                    performRealTimeSeek(targetPosition)
                    
                    // Resume video if it was playing before seek
                    if (wasPlayingBeforeSeek) {
                        coroutineScope.launch {
                            delay(100)
                            MPVLib.setPropertyBoolean("pause", false)
                        }
                    }
                    
                    // Reset seeking state and restart auto-hide
                    isSeeking = false
                    showSeekTime = false
                    wasPlayingBeforeSeek = false
                    
                    // Restart auto-hide after seeking ends
                    autoHideJob = coroutineScope.launch {
                        delay(4000)
                        showSeekbar = false
                    }
                }
                return true
            }
        }
        return false
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
                    .fillMaxHeight(0.15f)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp) // Outer padding
            ) {
                // Current time - left side
                Text(
                    text = currentTime,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .background(Color.DarkGray.copy(alpha = 0.8f)) // Dark grey background
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                
                // Total time - right side
                Text(
                    text = totalTime,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .background(Color.DarkGray.copy(alpha = 0.8f)) // Dark grey background
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                
                // SEEKBAR AREA - Between times
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .fillMaxHeight(0.4f)
                        .align(Alignment.Center)
                        .pointerInteropFilter { event ->
                            handleSliderSeek(event)
                        }
                ) {
                    // Use animated progress for smooth visual feedback
                    val progressPercent = animatedProgress.coerceIn(0f, 1f)
                    
                    // 20% Top transparent area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.2f)
                            .align(Alignment.TopStart)
                    )
                    
                    // 60% Middle - Seekbar track and progress
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.6f)
                            .align(Alignment.Center)
                    ) {
                        // Background track (grey)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .background(Color.Gray.copy(alpha = 0.6f))
                                .clip(RectangleShape)
                        )
                        
                        // Progress fill (white) - Clean progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressPercent)
                                .fillMaxHeight()
                                .background(Color.White)
                                .clip(RectangleShape)
                        )
                    }
                    
                    // 20% Bottom transparent area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.2f)
                            .align(Alignment.BottomStart)
                    )
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
                    .background(Color.DarkGray.copy(alpha = 0.8f)) // Dark grey background
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
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
