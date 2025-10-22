package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.gestures.detectTapGestures
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
    var isSpeedBoost by remember { mutableStateOf(false) }
    
    // Update time every 100ms
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyInt("time-pos") ?: 0
            val duration = MPVLib.getPropertyInt("duration") ?: 0
            
            currentTime = Utils.prettyTime(currentPos)
            totalTime = Utils.prettyTime(duration)
            
            delay(100) // Update every 100ms
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { 
                        // Start tracking press duration
                        val startTime = System.currentTimeMillis()
                        val wasPressed = tryAwaitRelease()
                        
                        if (wasPressed) {
                            val pressDuration = System.currentTimeMillis() - startTime
                            
                            if (pressDuration > 300) {
                                // Long press (hold) - speed up
                                isSpeedBoost = true
                                MPVLib.setPropertyFloat("speed", 2.0f)
                            } else {
                                // Short tap - toggle pause
                                viewModel.pauseUnpause()
                            }
                        }
                    },
                    onTap = {
                        // This handles quick taps (fallback)
                        if (!isSpeedBoost) {
                            viewModel.pauseUnpause()
                        }
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // Fallback for simple clicks
                    if (!isSpeedBoost) {
                        viewModel.pauseUnpause()
                    }
                }
            )
    ) {
        // Speed boost indicator - top center (visible when speed is 2x)
        if (isSpeedBoost) {
            Text(
                text = "2x",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Red.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        
        // Current time - bottom left (moved up a bit)
        Text(
            text = currentTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 32.dp) // Moved up from 16dp to 32dp
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        
        // Total time - bottom right (moved up a bit)
        Text(
            text = totalTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 32.dp) // Moved up from 16dp to 32dp
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
    
    // Handle speed boost reset when finger is lifted
    LaunchedEffect(isSpeedBoost) {
        if (isSpeedBoost) {
            // Wait for speed boost to end
            while (isSpeedBoost) {
                delay(100)
            }
            // Reset speed to normal
            MPVLib.setPropertyFloat("speed", 1.0f)
        }
    }
}

// Reset speed boost when touch ends
private suspend fun resetSpeedBoost(isSpeedBoost: Boolean, onReset: () -> Unit) {
    if (isSpeedBoost) {
        delay(100)
        onReset()
    }
}
