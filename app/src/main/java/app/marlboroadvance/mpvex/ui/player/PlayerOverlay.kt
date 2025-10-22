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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.Job
import `is`.xyz.mpv.MPVLib
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("00:00\n  00") }
    var totalTime by remember { mutableStateOf("00:00\n  00") }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pendingPauseResume by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    
    // Velocity-based frame stepping variables
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastMoveTime by remember { mutableLongStateOf(0L) }
    var accumulatedDelta by remember { mutableFloatStateOf(0f) }
    var wasPlayingBeforeFrameStep by remember { mutableStateOf(false) }
    var isFrameStepActive by remember { mutableStateOf(false) }
    var frameStepJob by remember { mutableStateOf<Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Update time every 50ms for smoother milliseconds
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            
            currentTime = formatTimeWithMilliseconds(currentPos)
            totalTime = formatTimeWithMilliseconds(duration)
            
            delay(50)
        }
    }
    
    // Handle speed transitions with 100ms delay
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            delay(100)
            MPVLib.setPropertyFloat("speed", 2.0f)
        } else {
            delay(100)
            MPVLib.setPropertyFloat("speed", 1.0f)
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
    
    // Velocity-based frame stepping gesture handler
    fun handleFrameStepGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastMoveTime = System.currentTimeMillis()
                accumulatedDelta = 0f
                
                // Remember playback state and pause immediately
                wasPlayingBeforeFrameStep = MPVLib.getPropertyBoolean("pause") == false
                if (wasPlayingBeforeFrameStep) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                isFrameStepActive = true
                
                // Cancel any existing frame step job
                frameStepJob?.cancel()
                
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (!isFrameStepActive) return true
                
                val currentX = event.x
                val currentTime = System.currentTimeMillis()
                
                val deltaX = currentX - lastX
                val deltaTime = (currentTime - lastMoveTime).coalesce(1L)
                
                // Calculate velocity (pixels per millisecond)
                val velocity = abs(deltaX) / deltaTime
                
                // Accumulate delta
                accumulatedDelta += deltaX
                
                // Threshold is 3 pixels
                val threshold = 3f
                
                if (abs(accumulatedDelta) >= threshold) {
                    val direction = sign(accumulatedDelta).toInt()
                    
                    // Calculate steps based on velocity (jog-shuttle behavior)
                    // Higher velocity = more frames per action
                    val steps = when {
                        velocity > 2.0f -> 5  // Very fast swipe
                        velocity > 1.0f -> 3  // Fast swipe
                        velocity > 0.5f -> 2  // Medium swipe
                        else -> 1             // Slow swipe
                    }
                    
                    // Execute frame steps directly without delays
                    val command = if (direction > 0) "frame-step" else "frame-back-step"
                    
                    // Step multiple frames based on velocity
                    repeat(steps) {
                        MPVLib.command("no-osd", command)
                    }
                    
                    // Reset accumulated delta
                    accumulatedDelta = 0f
                }
                
                // Update for next iteration
                lastX = currentX
                lastMoveTime = currentTime
                
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFrameStepActive = false
                accumulatedDelta = 0f
                
                // Resume playback if it was playing before
                if (wasPlayingBeforeFrameStep) {
                    frameStepJob = coroutineScope.launch {
                        delay(100)
                        MPVLib.setPropertyBoolean("pause", false)
                        wasPlayingBeforeFrameStep = false
                    }
                }
                
                return true
            }
        }
        return false
    }
    
    // Hold gesture handler for left/right areas (2x speed)
    fun handleHoldGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isSpeedingUp = true
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSpeedingUp = false
                return true
            }
        }
        return false
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
        
        // BOTTOM 30% - Frame stepping gesture (horizontal drag)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.30f)
                .align(Alignment.BottomStart)
                .pointerInteropFilter { event ->
                    handleFrameStepGesture(event)
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
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp
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
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 70.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// Extension function to prevent division by zero
private fun Long.coalesce(default: Long): Long = if (this == 0L) default else this

// Function to format time with milliseconds in the requested format
private fun formatTimeWithMilliseconds(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val milliseconds = ((seconds - totalSeconds) * 100).toInt()
    
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%3d\n%02d:%02d\n%3d", hours, minutes, secs, milliseconds)
    } else {
        String.format("%02d:%02d\n%3d", minutes, secs, milliseconds)
    }
}
