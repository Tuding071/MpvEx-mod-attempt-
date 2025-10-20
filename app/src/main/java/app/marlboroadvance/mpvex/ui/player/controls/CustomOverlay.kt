package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import `is`.xyz.mpv.MPVLib

/**
 * Custom overlay for player activity.
 * Independent of the original PlayerControls.kt
 * Currently supports tap to pause/resume.
 */
@Composable
fun CustomOverlay(
    modifier: Modifier = Modifier,
) {
    var paused by remember { mutableStateOf(MPVLib.propBoolean["pause"].get() ?: false) }
    var showText by remember { mutableStateOf(false) }
    var displayText by remember { mutableStateOf("") }

    // Observe pause state from MPV
    LaunchedEffect(Unit) {
        snapshotFlow { MPVLib.propBoolean["pause"].get() }.collect {
            paused = it ?: false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Only consider taps in center 90% area
                        val minX = size.width * 0.05f
                        val maxX = size.width * 0.95f
                        val minY = size.height * 0.05f
                        val maxY = size.height * 0.95f

                        if (it.x in minX..maxX && it.y in minY..maxY) {
                            // Toggle pause
                            MPVLib.togglePause()
                            paused = MPVLib.propBoolean["pause"].get() ?: false
                            displayText = if (paused) "Paused" else "Playing"
                            showText = true

                            // Hide text after 1 second if playing
                            if (!paused) {
                                LaunchedEffect(Unit) {
                                    delay(1000)
                                    showText = false
                                }
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (showText) {
            Text(
                text = displayText,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(16.dp)
            )
        }
    }
}
