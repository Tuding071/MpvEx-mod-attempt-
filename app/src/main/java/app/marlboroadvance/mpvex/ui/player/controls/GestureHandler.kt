package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import kotlinx.coroutines.delay
import `is`.xyz.mpv.MPVLib

@Composable
fun GestureHandler(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    var lastTapTime by remember { mutableStateOf(0L) }
    var showText by remember { mutableStateOf(false) }
    var textContent by remember { mutableStateOf("") }

    // observe paused state
    val paused by MPVLib.propBoolean["pause"].collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        val now = System.currentTimeMillis()
                        val delta = now - lastTapTime
                        lastTapTime = now
                        if (delta < 200) return@detectTapGestures // ignore double tap

                        if (paused == true) {
                            MPVLib.setPropertyBoolean("pause", false)
                            textContent = "Resume"
                            showText = true
                            // Hide text after 1s
                            viewModel.viewModelScope.launch {
                                delay(1000)
                                showText = false
                            }
                        } else {
                            MPVLib.setPropertyBoolean("pause", true)
                            textContent = "Pause"
                            showText = true
                        }
                    }
                )
            }
            // exclude 5% from each side — center active area
            .padding(horizontal = 0.05.dp, vertical = 0.05.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        if (showText) {
            Text(
                text = textContent,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = TextStyle.Default,
                modifier = Modifier
                    .padding(top = 30.dp)
                    .background(Color.Transparent)
            )
        }
    }
}
