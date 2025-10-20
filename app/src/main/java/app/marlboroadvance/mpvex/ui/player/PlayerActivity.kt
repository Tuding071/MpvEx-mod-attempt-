package app.marlboroadvance.mpvex.ui.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.marlboroadvance.mpvex.ui.player.controls.GestureHandler
import app.marlboroadvance.mpvex.ui.theme.MpvExTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MpvExTheme {
                val interactionSource = remember { MutableInteractionSource() }

                // Root container for video + gesture overlay
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // ✅ Native mpv player surface
                    AndroidView(
                        factory = { context ->
                            viewModel.initializePlayerView(context).apply {
                                // The player surface is managed by mpvKt / mpv-android
                                // So this just attaches it to Compose
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // ✅ Gesture overlay (tap to pause/resume, swipe, etc.)
                    GestureHandler(
                        viewModel = viewModel,
                        interactionSource = interactionSource,
                        modifier = Modifier.fillMaxSize(),
                        onTogglePlayPause = {
                            viewModel.togglePause()
                            !viewModel.isPaused()
                        }
                    )
                }
            }
        }
    }
}
