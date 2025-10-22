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
    var currentTime by remember { mutableStateOf("00:00\n  00") }
    var totalTime by remember { mutableStateOf("00:00\n  00") }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pendingPauseResume by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    
    // Drag seeking variables
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    var lastSeekUpdateTime by remember { mutableStateOf(0L) }
    
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
    
    // Continuous drag seeking gesture handler for bottom area
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                seekStartX = event.x
                seekStartPosition = MPVLib.getPropertyInt("time-pos") ?: 0
                wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                isSeeking = true
                
                // Pause video when seeking starts
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    val currentX = event.x
                    val deltaX = currentX - seekStartX
                    
                    // Sensitivity: 10 pixels = 1 second (adjust this later)
                    val sensitivity = 10f
                    val timeDelta = (deltaX / sensitivity).toInt()
                    
                    // Calculate new position
                    val newPosition = (seekStartPosition + timeDelta).coerceIn(0, Int.MAX_VALUE)
                    
                    // Seek immediately for responsive feedback
                    viewModel.seekTo(newPosition, false)
                    
                    // Update frame every 100ms (throttle updates)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastSeekUpdateTime >= 100) {
                        // Force frame update
                        MPVLib.command("no-osd", "frame-step")
                        MPVLib.command("no-osd", "frame-back-step")
                        lastSeekUpdateTime = currentTime
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSeeking) {
                    // Resume video if it was playing before seek
                    if (wasPlayingBeforeSeek) {
                        coroutineScope.launch {
                            delay(100) // 100ms delay before resuming
                            MPVLib.setPropertyBoolean("pause", false)
                        }
                    }
                    
                    // Reset seeking state
                    isSeeking = false
                    seekStartX = 0f
                    seekStartPosition = 0
                    wasPlayingBeforeSeek = false
                    lastSeekUpdateTime = 0L
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
