package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    
    // Update time every 100ms
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyInt("time-pos") ?: 0
            val duration = MPVLib.getPropertyInt("duration") ?: 0
            
            currentTime = Utils.prettyTime(currentPos)
            totalTime = Utils.prettyTime(duration)
            
            delay(100)
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.any { it.pressed }
                        
                        if (pressed) {
                            val startTime = System.currentTimeMillis()
                            var isLongPress = false
                            
                            // Wait for press to be released or 300ms for long press
                            while (event.changes.any { it.pressed }) {
                                val currentDuration = System.currentTimeMillis() - startTime
                                
                                if (currentDuration >= 300 && !isLongPress) {
                                    // Long press detected - speed up to 2x
                                    isLongPress = true
                                    MPVLib.setPropertyFloat("speed", 2.0f)
                                }
                                
                                // Small delay to prevent busy waiting
                                awaitPointerEvent()
                            }
                            
                            // Press released
                            if (isLongPress) {
                                // Was long press - reset speed
                                MPVLib.setPropertyFloat("speed", 1.0f)
                            } else {
                                // Check if it was a quick tap (<200ms)
                                val pressDuration = System.currentTimeMillis() - startTime
                                if (pressDuration < 200) {
                                    // Quick tap - toggle pause/resume
                                    viewModel.pauseUnpause()
                                }
                                // Between 200-300ms does nothing
                            }
                        }
                    }
                }
            }
    ) {
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
                .padding(start = 16.dp, bottom = 48.dp)
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
                .padding(end = 16.dp, bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
