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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import `is`.xyz.mpv.MPVLib

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPauseText by remember { mutableStateOf(false) }
    var showResumeText by remember { mutableStateOf(false) }
    var debugText by remember { mutableStateOf("Debug: No state") }
    
    // Use ViewModel's paused state instead of direct MPVLib for better timing
    val isPaused = viewModel.paused ?: false
    
    // Handle pause state changes
    LaunchedEffect(isPaused) {
        debugText = "LaunchedEffect: isPaused = $isPaused"
        
        if (isPaused) {
            showPauseText = true
            showResumeText = false
            debugText = "SHOWING PAUSE TEXT"
        } else {
            if (showPauseText) { // Only show resume if we were previously showing pause
                showPauseText = false
                showResumeText = true
                debugText = "SHOWING RESUME TEXT"
                // Hide resume text after 1 second
                delay(1000)
                showResumeText = false
                debugText = "HIDING RESUME TEXT"
            }
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // GESTURE LAYER - Full screen clickable area (on bottom)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        // Use ViewModel for better state management
                        viewModel.pauseUnpause()
                        debugText = "Tapped! isPaused was: ${viewModel.paused}"
                    }
                )
        )
        
        // TEXT LAYER - On top, NOT clickable
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // DEBUG TEXT - Always visible to see what's happening
            Text(
                text = debugText,
                style = TextStyle(
                    color = Color.Red, // Bright red for visibility
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            )
            
            // Pause text with very visible background for debugging
            if (showPauseText) {
                Text(
                    text = "PAUSE",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 24.sp, // Even larger
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                        .background(Color.Red.copy(alpha = 0.8f)) // Bright red background for debugging
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            
            // Resume text with very visible background for debugging
            if (showResumeText) {
                Text(
                    text = "RESUME",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 24.sp, // Even larger
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp)
                        .background(Color.Green.copy(alpha = 0.8f)) // Bright green background for debugging
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            
            // Additional debug info
            Text(
                text = "Paused: $isPaused\nShowPause: $showPauseText\nShowResume: $showResumeText",
                style = TextStyle(
                    color = Color.Yellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            )
        }
    }
}
