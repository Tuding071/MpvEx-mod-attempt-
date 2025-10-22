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
        modifier = modifier.fillMaxSize()
    ) {
        // CENTER AREA - Tap for pause/resume (no gestures)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f) // 80% width
                .fillMaxHeight(0.7f) // 70% height  
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Simple tap - pause/resume using ViewModel (no glitching)
                            viewModel.pauseUnpause()
                        }
                    )
                }
        )
        
        // BOTTOM 25% + LEFT/RIGHT AREAS - For future gestures (hold, swipe, etc.)
        // These areas are reserved but currently inactive
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                .align(Alignment.BottomStart)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth(0.25f)
                .fillMaxHeight(0.7f)
                .align(Alignment.CenterStart)
        )
        
        Box(
            modifier = Modifier
                .fillMaxWidth(0.25f)
                .fillMaxHeight(0.7f)
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 60.dp) // Moved up to 60dp
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        
        // Total time - bottom right (moved up more)
        Text(
            text = totalTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 60.dp) // Moved up to 60dp
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
