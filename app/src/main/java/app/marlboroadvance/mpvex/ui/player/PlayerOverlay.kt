package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    
    // Drag seeking variables
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var lastDragUpdate by remember { mutableStateOf(0L) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Aggressive software decoding optimization
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyInt("demuxer-max-bytes", 100 * 1024 * 1024) // 100MB cache
        MPVLib.setPropertyString("demuxer-readahead-secs", "30")
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
    }
    
    // Update time every 250ms
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            
            currentTime = formatTimeSimple(currentPos)
            totalTime = formatTimeSimple(duration)
            
            delay(250)
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
    
    // Smart seeking function with dual-mode (fast vs precise)
    fun performSmartSeek(targetPosition: Double, dragSpeed: Double) {
        val isFastSeek = Math.abs(dragSpeed) > 2.0
        
        if (isFastSeek) {
            // FAST SEEKING: Keyframe mode for instant response
            MPVLib.command("seek", targetPosition.toString(), "absolute", "keyframes")
        } else {
            // SLOW SEEKING: Precise mode for accuracy
            MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
        }
        
        // Update seek target time display
        seekTargetTime = formatTimeSimple(targetPosition)
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
                lastDragUpdate = System.currentTimeMillis()
                
                // Pause video when seeking starts for smoother frame updates
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                
                // Enable high-performance seeking mode
                MPVLib.setPropertyString("hr-seek", "absolute")
                MPVLib.setPropertyString("video-sync", "display-resample")
                
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    val currentTime = System.currentTimeMillis()
                    val currentX = event.x
                    val deltaX = currentX - seekStartX
                    
                    // Calculate drag speed for smart seeking
                    val timeDelta = (currentTime - lastDragUpdate) / 1000.0
                    val dragSpeed = if (timeDelta > 0) Math.abs(deltaX / timeDelta) else 0.0
                    
                    // Responsive sensitivity: 3 pixels = 33ms (0.033 seconds)
                    val pixelsPerSecond = 3f / 0.033f // ~90.9 pixels per second
                    val timeDeltaSeconds = deltaX / pixelsPerSecond
                    
                    // Calculate new position in seconds with double precision
                    val newPositionSeconds = seekStartPosition + timeDeltaSeconds
                    
                    // Ensure position is within bounds
                    val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                    val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
                    
                    // Perform smart seek based on drag speed
                    performSmartSeek(clampedPosition, dragSpeed)
                    
                    // Update last drag time
                    lastDragUpdate = currentTime
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSeeking) {
                    // Final position adjustment
                    val currentX = event.x
                    val deltaX = currentX - seekStartX
                    val pixelsPerSecond = 3f / 0.033f
                    val timeDeltaSeconds = deltaX / pixelsPerSecond
                    val newPositionSeconds = seekStartPosition + timeDeltaSeconds
                    val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
                    val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
                    
                    // Final precise seek to ensure accuracy
                    MPVLib.command("seek", clampedPosition.toString(), "absolute", "exact")
                    
                    // Resume video if it was playing before seek
                    if (wasPlayingBeforeSeek) {
                        coroutineScope.launch {
                            delay(100)
                            MPVLib.setPropertyBoolean("pause", false)
                        }
                    }
                    
                    // Reset seeking mode
                    MPVLib.setPropertyString("hr-seek", "no")
                    MPVLib.setPropertyString("video-sync", "audio")
                    
                    // Reset seeking state
                    isSeeking = false
                    showSeekTime = false
                    seekStartX = 0f
                    seekStartPosition = 0.0
                    wasPlayingBeforeSeek = false
                    lastDragUpdate = 0L
                }
                return true
            }
        }
        return false
    }
    
    // Hold gesture handler for left/right areas (2x speed)
    fun handleHoldGesture(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isSpeedingUp = true
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSpeedingUp = false
                true
            }
            else -> false
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // CENTER AREA - Tap for pause/resume with different delays
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
        
        // BOTTOM 30% - Continuous drag seeking
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.30f)
                .align(Alignment.BottomStart)
                .pointerInteropFilter { event ->
                    handleDragSeekGesture(event)
                }
        )
        
        // LEFT 27% - Hold for 2x speed
        Box(
            modifier = Modifier
                .fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterStart)
                .pointerInteropFilter { event ->
                    handleHoldGesture(event)
                }
        )
        
        // RIGHT 27% - Hold for 2x speed
        Box(
            modifier = Modifier
                .fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterEnd)
                .pointerInteropFilter { event ->
                    handleHoldGesture(event)
                }
        )
        
        // TOP 5% - Ignore area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
        )
        
        // Current time - bottom left
        Text(
            text = currentTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 70.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        
        // Total time - bottom right
        Text(
            text = totalTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 70.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        
        // Center seek time - shows target position during seeking (positioned higher up)
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
                    .offset(y = (-40).dp) // Positioned 40dp above center
                    .background(Color.Black.copy(alpha = 0.7f))
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
