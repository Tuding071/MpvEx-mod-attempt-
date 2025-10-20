package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import kotlinx.coroutines.delay
import `is`.xyz.mpv.MPVLib

@Composable
fun GestureHandler(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    // Current paused state
    val paused by MPVLib.propBoolean["pause"].collectAsState()

    // Text state
    var gestureText by remember { mutableStateOf<String?>(null) }

    // Control timing of tap detection
    var lastTapTime by remember { mutableStateOf(0L) }

    // Handle text display timing
    LaunchedEffect(gestureText) {
        if (gestureText == "Resumed") {
            delay(1000)
            gestureText = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // exclude 5% edges → effective gesture zone center
            .padding(horizontal = 0.05.dp * 100, vertical = 0.05.dp * 100)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        val now = System.currentTimeMillis()
                        val duration = now - lastTapTime
                        lastTapTime = now

                        if (duration < 200) return@detectTapGestures // ignore accidental double tap

                        val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
                        if (isPaused) {
                            MPVLib.setPropertyBoolean("pause", false)
                            gestureText = "Resumed"
                        } else {
                            MPVLib.setPropertyBoolean("pause", true)
                            gestureText = "Paused"
                        }
                    }
                )
            }
    ) {
        // Show pause/resume text at top center
        if (gestureText != null) {
            Text(
                text = gestureText!!,
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
            )
        }
    }
}
