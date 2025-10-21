package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.clickable
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

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPauseText by remember { mutableStateOf(false) }
    var showResumeText by remember { mutableStateOf(false) }
    
    // Track pause state
    val isPaused = viewModel.paused ?: false
    
    // Handle pause state changes
    LaunchedEffect(isPaused) {
        if (isPaused) {
            showPauseText = true
            showResumeText = false
        } else {
            if (showPauseText) { // Only show resume if we were previously showing pause
                showPauseText = false
                showResumeText = true
                // Hide resume text after 1 second
                delay(1000)
                showResumeText = false
            }
        }
    }
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Main clickable area with 5% margins (center 90%)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(30.dp) // This creates ~5% margin on all sides
                .clickable(
                    onClick = {
                        viewModel.pauseUnpause()
                    }
                )
        )
        
        // Text overlay - always on top
        // Pause text - stays visible while paused
        if (showPauseText) {
            Text(
                text = "Pause",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            )
        }
        
        // Resume text - shows briefly when resuming
        if (showResumeText) {
            Text(
                text = "Resume",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            )
        }
    }
}
