package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import `is`.xyz.mpv.MPVLib
import androidx.compose.foundation.gestures.detectTapGestures

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("00:00\n  00") }
    var totalTime by remember { mutableStateOf("00:00\n  00") }
    
    // Update time every 50ms for smoother milliseconds
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            
            currentTime = formatTimeWithMilliseconds(currentPos)
            totalTime = formatTimeWithMilliseconds(duration)
            
            delay(50) // Faster update for milliseconds
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // CENTER AREA - Tap for pause/resume (no gestures)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f) // 60% width (reduced from 80%)
                .fillMaxHeight(0.6f) // 60% height (reduced from 70%)  
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Check current state before toggling
                            val wasPaused = MPVLib.getPropertyBoolean("pause") ?: false
                            
                            if (wasPaused) {
                                // Currently paused - about to resume
                                // Add 200ms delay before resuming
                                LaunchedEffect(Unit) {
                                    delay(200)
                                    viewModel.pauseUnpause()
                                }
                            } else {
                                // Currently playing - about to pause
                                // No delay for pause - instant response
                                viewModel.pauseUnpause()
                            }
                        }
                    )
                }
        )
        
        // BOTTOM 35% - For future gestures (hold, swipe, etc.)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .align(Alignment.BottomStart)
        )
        
        // LEFT 35% - For future gestures
        Box(
            modifier = Modifier
                .fillMaxWidth(0.35f)
                .fillMaxHeight(0.6f)
                .align(Alignment.CenterStart)
        )
        
        // RIGHT 35% - For future gestures
        Box(
            modifier = Modifier
                .fillMaxWidth(0.35f)
                .fillMaxHeight(0.6f)
                .align(Alignment.CenterEnd)
        )
        
        // TOP 5% - Ignore area (no gestures)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.05f)
                .align(Alignment.TopStart)
        )
        
        // Current time - bottom left (moved up more)
        Text(
            text = currentTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp, // Smaller font for two lines
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 70.dp) // Moved up to 70dp
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        
        // Total time - bottom right (moved up more)
        Text(
            text = totalTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp, // Smaller font for two lines
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 70.dp) // Moved up to 70dp
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
        // With hours: 
        //   HHH
        // MM:SS
        //   MS
        String.format("%3d\n%02d:%02d\n%3d", hours, minutes, secs, milliseconds)
    } else {
        // Without hours:
        // MM:SS
        //   MS
        String.format("%02d:%02d\n%3d", minutes, secs, milliseconds)
    }
}
