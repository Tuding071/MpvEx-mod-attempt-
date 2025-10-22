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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("00:00\n000") }
    var totalTime by remember { mutableStateOf("00:00\n000") }
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

    // Update time every 50ms for smooth ms display
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0

            currentTime = formatTimeWithMilliseconds(currentPos)
            totalTime = formatTimeWithMilliseconds(duration)

            delay(50)
        }
    }

    // Handle speed transitions
    LaunchedEffect(isSpeedingUp) {
        MPVLib.setPropertyFloat("speed", if (isSpeedingUp) 2.0f else 1.0f)
    }

    // Handle pause/resume
    LaunchedEffect(pendingPauseResume) {
        if (pendingPauseResume) {
            viewModel.pauseUnpause()
            pendingPauseResume = false
        }
    }

    // Continuous drag seeking gesture handler
    fun handleDragSeekGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                seekStartX = event.x
                seekStartPosition = MPVLib.getPropertyInt("time-pos") ?: 0
                wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
                isSeeking = true

                // Pause video during seek
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isSeeking) {
                    val currentX = event.x
                    val deltaX = currentX - seekStartX

                    // Sensitivity: 25 pixels = 1 second
                    val sensitivity = 25f
                    val timeDelta = (deltaX / sensitivity).toInt()

                    val newPosition = (seekStartPosition + timeDelta).coerceAtLeast(0)

                    // Seek continuously
                    viewModel.seekTo(newPosition, false)

                    // Update every 100ms to refresh the frame visually
                    val now = System.currentTimeMillis()
                    if (now - lastSeekUpdateTime >= 100) {
                        MPVLib.command("no-osd", "frame-step")
                        MPVLib.command("no-osd", "frame-back-step")
                        lastSeekUpdateTime = now
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSeeking) {
                    if (wasPlayingBeforeSeek) {
                        coroutineScope.launch {
                            delay(100)
                            MPVLib.setPropertyBoolean("pause", false)
                        }
                    }
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

    // Hold gesture for 2x speed
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
        // CENTER TAP → pause/resume
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

        // BOTTOM → continuous drag seek
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.30f)
                .align(Alignment.BottomStart)
                .pointerInteropFilter { event ->
                    handleDragSeekGesture(event)
                }
        )

        // LEFT → hold for 2x speed
        Box(
            modifier = Modifier
                .fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterStart)
                .pointerInteropFilter { event ->
                    handleHoldGesture(event)
                }
        )

        // RIGHT → hold for 2x speed
        Box(
            modifier = Modifier
                .fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterEnd)
                .pointerInteropFilter { event ->
                    handleHoldGesture(event)
                }
        )

        // TOP ignore zone
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
        )

        // Current time
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

        // Total time
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

// ⏱ Format time with 3-digit milliseconds
private fun formatTimeWithMilliseconds(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val milliseconds = ((seconds - totalSeconds) * 1000).toInt().coerceIn(0, 999)

    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d\n%03d", hours, minutes, secs, milliseconds)
    } else {
        String.format("%02d:%02d\n%03d", minutes, secs, milliseconds)
    }
}
