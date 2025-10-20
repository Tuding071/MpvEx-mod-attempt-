package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.player.*
import app.marlboroadvance.mpvex.ui.theme.spacing
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControls(
    viewModel: PlayerViewModel,
    onBackPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val playerPreferences = koinInject<PlayerPreferences>()
    val audioPreferences = koinInject<AudioPreferences>()
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val pausedForCache by MPVLib.propBoolean["paused-for-cache"].collectAsState()
    val paused by MPVLib.propBoolean["pause"].collectAsState()
    val duration by MPVLib.propInt["duration"].collectAsState()
    val position by MPVLib.propInt["time-pos"].collectAsState()
    val playbackSpeed by MPVLib.propFloat["speed"].collectAsState()
    var isSeeking by remember { mutableStateOf(false) }
    val playerTimeToDisappear by playerPreferences.playerTimeToDisappear.collectAsState()

    LaunchedEffect(controlsShown, paused, isSeeking) {
        if (controlsShown && paused == false && !isSeeking) {
            delay(playerTimeToDisappear.toLong())
            viewModel.hideControls()
        }
    }

    val transparentOverlay by animateFloatAsState(
        if (controlsShown && !areControlsLocked) .8f else 0f,
        animationSpec = playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )

    // ---------------------------
    // NEW GESTURE HANDLER INTEGRATION
    // ---------------------------
    GestureHandler(
        modifier = Modifier.fillMaxSize(),
        onTogglePlayPause = {
            viewModel.pauseUnpause()
            !MPVLib.getPropertyBoolean("pause")
        }
    )

    // ---------------------------
    // Rest of PlayerControls UI
    // ---------------------------
    ConstraintLayout(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    Pair(0f, Color.Black),
                    Pair(.2f, Color.Transparent),
                    Pair(.7f, Color.Transparent),
                    Pair(1f, Color.Black),
                ),
                alpha = transparentOverlay,
            )
            .padding(horizontal = MaterialTheme.spacing.medium),
    ) {
        val (unlockControlsButton, playerPauseButton, seekbar) = createRefs()

        // Unlock button
        AnimatedVisibility(
            controlsShown && areControlsLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.constrainAs(unlockControlsButton) {
                top.linkTo(parent.top, spacing.medium)
                start.linkTo(parent.start, spacing.medium)
            },
        ) {
            ControlsButton(
                Icons.Filled.Lock,
                onClick = { viewModel.unlockControls() },
            )
        }

        // Pause/Play button center
        AnimatedVisibility(
            visible = controlsShown && !areControlsLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.constrainAs(playerPauseButton) {
                end.linkTo(parent.absoluteRight)
                start.linkTo(parent.absoluteLeft)
                top.linkTo(parent.top)
                bottom.linkTo(parent.bottom)
            },
        ) {
            val icon = androidx.compose.animation.graphics.vector.AnimatedImageVector.animatedVectorResource(
                app.marlboroadvance.mpvex.R.drawable.anim_play_to_pause
            )
            androidx.compose.foundation.Image(
                painter = androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter(icon, paused == false),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape),
            )
        }

        // TODO: Keep rest of bottom/top controls like seekbar, sliders, chapters etc.
    }
}

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 300,
    easing = FastOutSlowInEasing,
)

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 100,
    easing = LinearOutSlowInEasing,
)
