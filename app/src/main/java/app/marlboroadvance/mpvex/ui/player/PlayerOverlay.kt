package app.marlboroadvance.mpvex.ui.player

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("00:00\n  00") }
    var totalTime by remember { mutableStateOf("00:00\n  00") }

    // State vars
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pendingPauseResume by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }

    // Frame stepping vars
    var isFrameStepping by remember { mutableStateOf(false) }
    var frameStepStartX by remember { mutableStateOf(0f) }
    var lastFrameX by remember { mutableStateOf(0f) }
    var wasPlayingBefore by remember { mutableStateOf(false) }

    val coroutineScope = remember { CoroutineScope(Dispatchers.Default) }

    // Smooth timer update
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            currentTime = formatTimeWithMilliseconds(currentPos)
            totalTime = formatTimeWithMilliseconds(duration)
            delay(60)
        }
    }

    // Simple pause/resume tap
    LaunchedEffect(pendingPauseResume) {
        if (pendingPauseResume) {
            if (isPausing) delay(40) else delay(120)
            viewModel.pauseUnpause()
            pendingPauseResume = false
        }
    }

    // --- Frame Step Gesture ---
    fun handleFrameStepGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                frameStepStartX = event.x
                lastFrameX = event.x
                wasPlayingBefore = MPVLib.getPropertyBoolean("pause") == false

                // Pause once at start
                if (wasPlayingBefore) MPVLib.setPropertyBoolean("pause", true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - lastFrameX
                val absDeltaX = kotlin.math.abs(deltaX)

                if (absDeltaX >= 6 && !isFrameStepping) {
                    isFrameStepping = true

                    coroutineScope.launch {
                        val command = if (deltaX > 0) "frame-step" else "frame-back-step"

                        val now = System.nanoTime()
                        val speedNs = 100_000_000L // 100ms reference window
                        var repeatCount = 1

                        // Throttle skip: if swiping fast, skip 1–2 extra frames
                        val elapsed = now - (lastTriggerTime ?: now)
                        repeatCount = when {
                            elapsed < speedNs / 2 -> 3
                            elapsed < speedNs -> 2
                            else -> 1
                        }
                        lastTriggerTime = now

                        repeat(repeatCount) {
                            MPVLib.command(arrayOf("no-osd", command))
                        }

                        lastFrameX = event.x
                        delay(20) // tiny buffer for MPV render
                        isFrameStepping = false
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Resume only if it was playing before
                if (wasPlayingBefore) {
                    coroutineScope.launch {
                        delay(80)
                        MPVLib.setPropertyBoolean("pause", false)
                    }
                }
                isFrameStepping = false
                frameStepStartX = 0f
                lastFrameX = 0f
                wasPlayingBefore = false
                return true
            }
        }
        return false
    }

    // Track last trigger for speed detection
    var lastTriggerTime by remember { mutableStateOf<Long?>(null) }

    // Hold gesture for 2x speed
    fun handleHoldGesture(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { isSpeedingUp = true; return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isSpeedingUp = false; return true }
        }
        return false
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Tap pause/resume center
        Box(
            Modifier
                .fillMaxWidth(0.73f)
                .fillMaxHeight(0.7f)
                .align(Alignment.Center)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        val paused = MPVLib.getPropertyBoolean("pause") ?: false
                        isPausing = !paused
                        pendingPauseResume = true
                    }
                )
        )

        // Bottom 30% area for frame stepping
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.30f)
                .align(Alignment.BottomStart)
                .pointerInteropFilter { handleFrameStepGesture(it) }
        )

        // Left/right 2x zones
        Box(
            Modifier.fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterStart)
                .pointerInteropFilter { handleHoldGesture(it) }
        )
        Box(
            Modifier.fillMaxWidth(0.27f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterEnd)
                .pointerInteropFilter { handleHoldGesture(it) }
        )

        // Top ignore zone
        Box(
            Modifier.fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
        )

        // Time displays
        Text(
            text = currentTime,
            style = TextStyle(
                color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, lineHeight = 14.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 70.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            text = totalTime,
            style = TextStyle(
                color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, lineHeight = 14.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 70.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// --- Helper ---
private fun formatTimeWithMilliseconds(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val milliseconds = ((seconds - totalSeconds) * 100).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0)
        String.format("%3d\n%02d:%02d\n%3d", hours, minutes, secs, milliseconds)
    else
        String.format("%02d:%02d\n%3d", minutes, secs, milliseconds)
}
