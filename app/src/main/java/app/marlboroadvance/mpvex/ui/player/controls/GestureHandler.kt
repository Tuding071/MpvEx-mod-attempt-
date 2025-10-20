package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.shadow.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import app.marlboroadvance.mpvex.presentation.components.LeftSideOvalShape
import app.marlboroadvance.mpvex.presentation.components.RightSideOvalShape
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlayerUpdates
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.controls.components.DoubleTapSeekTriangles
import app.marlboroadvance.mpvex.ui.theme.playerRippleConfiguration
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import `is`.xyz.mpv.MPVLib
import org.koin.compose.koinInject

@Suppress("CyclomaticComplexMethod", "MultipleEmitters")
@Composable
fun GestureHandler(
    viewModel: PlayerViewModel,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier
) {
    val playerPreferences = koinInject<PlayerPreferences>()
    val audioPreferences = koinInject<AudioPreferences>()
    val panelShown by viewModel.panelShown.collectAsState()
    val allowGesturesInPanels by playerPreferences.allowGesturesInPanels.collectAsState()
    val paused by MPVLib.propBoolean["pause"].collectAsState()
    val playbackSpeed by MPVLib.propFloat["speed"].collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val seekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val isSeekingForwards by viewModel.isSeekingForwards.collectAsState()

    var isDoubleTapSeeking by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val multipleSpeedGesture by playerPreferences.holdForMultipleSpeed.collectAsState()
    val haptics = LocalHapticFeedback.current

    // Reset double-tap seeking
    LaunchedEffect(seekAmount) {
        delay(800)
        isDoubleTapSeeking = false
        viewModel.updateSeekAmount(0)
        viewModel.updateSeekText(null)
        delay(100)
        viewModel.hideSeekBar()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeGestures)
            .pointerInput(Unit) {
                var originalSpeed = MPVLib.getPropertyFloat("speed") ?: 1f
                detectTapGestures(
                    onTap = {
                        // Pause/resume logic
                        val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
                        if (isPaused) {
                            viewModel.unpause()
                            gestureText = "Resumed"
                            scope.launch {
                                delay(1000)
                                gestureText = null
                            }
                        } else {
                            viewModel.pause()
                            gestureText = "Paused"
                        }
                    },
                    onDoubleTap = {
                        if (areControlsLocked || isDoubleTapSeeking) return@detectTapGestures
                        if (it.x > size.width * 3 / 5) {
                            if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                            viewModel.handleRightDoubleTap()
                            isDoubleTapSeeking = true
                        } else if (it.x < size.width * 2 / 5) {
                            if (isSeekingForwards) viewModel.updateSeekAmount(0)
                            viewModel.handleLeftDoubleTap()
                            isDoubleTapSeeking = true
                        } else {
                            viewModel.handleCenterDoubleTap()
                        }
                    },
                    onPress = {
                        if (panelShown != Panels.None && !allowGesturesInPanels) {
                            viewModel.panelShown.update { Panels.None }
                        }
                        val press = PressInteraction.Press(it)
                        interactionSource.emit(press)
                        tryAwaitRelease()
                        interactionSource.emit(PressInteraction.Release(press))
                    },
                    onLongPress = {
                        if (multipleSpeedGesture == 0f || areControlsLocked) return@detectTapGestures
                        if (paused == false) {
                            val originalSpeed = playbackSpeed ?: return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            MPVLib.setPropertyFloat("speed", multipleSpeedGesture)
                            viewModel.playerUpdate.update { PlayerUpdates.MultipleSpeed }
                            scope.launch {
                                delay(1000)
                                MPVLib.setPropertyFloat("speed", originalSpeed)
                                viewModel.playerUpdate.update { PlayerUpdates.None }
                            }
                        }
                    }
                )
            }
    ) {
        // Overlay gesture text
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            gestureText?.let { text ->
                Text(
                    text = text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(2f, 2f),
                            blurRadius = 2f
                        )
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubleTapToSeekOvals(
    amount: Int,
    text: String?,
    showOvals: Boolean,
    showSeekIcon: Boolean,
    showSeekTime: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (amount == 0) 0f else 0.2f, label = "double_tap_animation_alpha")
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (amount > 0) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        CompositionLocalProvider(LocalRippleConfiguration provides playerRippleConfiguration) {
            if (amount != 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.4f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showOvals) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(if (amount > 0) RightSideOvalShape else LeftSideOvalShape)
                                .background(Color.White.copy(alpha))
                                .indication(interactionSource, ripple()),
                        )
                    }
                    if (showSeekIcon || showSeekTime) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DoubleTapSeekTriangles(isForward = amount > 0)
                            Text(
                                text = text ?: "$amount sec",
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
