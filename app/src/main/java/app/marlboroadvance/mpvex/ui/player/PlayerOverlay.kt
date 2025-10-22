package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    
    // Track pause state directly from MPVLib
    val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
    
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
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // Direct MPVLib call to toggle pause
                    val currentPaused = MPVLib.getPropertyBoolean("pause") ?: false
                    MPVLib.setPropertyBoolean("pause", !currentPaused)
                }
            )
    ) {
        // Pause text with transparent padding
        if (showPauseText) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
            ) {
                Text(
                    text = "PAUSE",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.3f)) // Very subtle dark background
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        
        // Resume text with transparent padding
        if (showResumeText) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
            ) {
                Text(
                    text = "RESUME",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.3f)) // Very subtle dark background
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
