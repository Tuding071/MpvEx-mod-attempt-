package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GestureHandler overlay:
 *  - Single tap (≤200 ms) toggles pause/resume.
 *  - Displays overlay text (“Paused” / “Playing”) at top center.
 */
@Composable
fun GestureHandler(
    modifier: Modifier = Modifier,
    onTogglePlayPause: () -> Boolean // returns true if now playing
) {
    var overlayText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val pressTime = System.currentTimeMillis()
                        val released = tryAwaitRelease()
                        val duration = System.currentTimeMillis() - pressTime
                        if (released && duration < 200) {
                            val isPlaying = onTogglePlayPause()
                            overlayText = if (isPlaying) "Playing" else "Paused"
                            coroutineScope.launch {
                                delay(800)
                                overlayText = ""
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        if (overlayText.isNotEmpty()) {
            Text(
                text = overlayText,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                ),
                modifier = Modifier
                    .padding(top = 40.dp)
                    .align(Alignment.TopCenter)
            )
        }
    }
}
