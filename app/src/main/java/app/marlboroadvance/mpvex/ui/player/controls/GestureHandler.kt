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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import `is`.xyz.mpv.MPVLib
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel

@Composable
fun GestureHandler(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    var lastTapTime by remember { mutableStateOf(0L) }
    var showText by remember { mutableStateOf(false) }
    var textContent by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

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
                            scope.launch {
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
            // center 90% active area (5% padding)
            .padding(horizontal = 20.dp, vertical = 20.dp),
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
