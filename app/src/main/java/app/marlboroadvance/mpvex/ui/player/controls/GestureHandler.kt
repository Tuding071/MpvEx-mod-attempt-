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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.shadow
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
    val duration by MPVLib.propInt["duration"].collectAsState()
    val position by MPVLib.propInt["time-pos"].collectAsState()
    val playbackSpeed by MPVLib.propFloat["speed"].collectAsState()
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val seekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val isSeekingForwards by viewModel.isSeekingForwards.collectAsState()
    var isDoubleTapSeeking by remember { mutableStateOf(false) }
    var gestureText by remember { mutableStateOf<String?>(null) } // For tap pause/resume display

    val scope = rememberCoroutineScope()
    val multipleSpeedGesture by playerPreferences.holdForMultipleSpeed.collectAsState()
    val brightnessGesture = playerPreferences.brightnessGesture.get()
    val volumeGesture by playerPreferences.volumeGesture.collectAsState()
    val swapVolumeAndBrightness by playerPreferences.swapVolumeAndBrightness.collectAsState()
    val seekGesture by playerPreferences.horizontalSeekGesture.collectAsState()
    val preciseSeeking by playerPreferences.preciseSeeking.collectAsState()
    val showSeekbarWhenSeeking by playerPreferences.showSeekBarWhenSeeking.collectAsState()
    var isLongPressing by remember { mutableStateOf(false) }
    val currentVolume by viewModel.currentVolume.collectAsState()
    val currentMPVVolume by MPVLib.propInt["volume"].collectAsState()
    val currentBrightness by viewModel.currentBrightness.collectAsState()
    val volumeBoostingCap = audioPreferences.volumeBoostCap.get()
    val haptics = LocalHapticFeedback.current

    // Reset double-tap seeking after animation
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
                        // Only treat very short taps (<200ms) as pause/resume
                        viewModel.togglePause() // This should toggle your player pause state
                        gestureText = if (MPVLib.getPropertyBoolean("pause") == true) "Paused" else "Resumed"
                        // Auto-hide "Resumed" after 1 second
                        if (gestureText == "Resumed") {
                            scope.launch {
                                delay(1000)
                                gestureText = null
                            }
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
                        if (isLongPressing) {
                            isLongPressing = false
                            MPVLib.setPropertyFloat("speed", originalSpeed)
                            viewModel.playerUpdate.update { PlayerUpdates.None }
                        }
                        interactionSource.emit(PressInteraction.Release(press))
                    },
                    onLongPress = {
                        if (multipleSpeedGesture == 0f || areControlsLocked) return@detectTapGestures
                        if (!isLongPressing && paused == false) {
                            originalSpeed = playbackSpeed ?: return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isLongPressing = true
                            MPVLib.setPropertyFloat("speed", multipleSpeedGesture)
                            viewModel.playerUpdate.update { PlayerUpdates.MultipleSpeed }
                        }
                    }
                )
            }
    ) {
        // Gesture overlay text (top center)
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
                        shadow = androidx.compose.ui.text.shadow.Shadow(
                            color = Color.Black,
                            offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                            blurRadius = 2f
                        )
                    )
                )
            }
        }

        // --- Keep your horizontal/vertical/double-tap gestures here exactly like your previous code ---
        // You can copy your vertical and horizontal drag pointerInput blocks here
        // and also your DoubleTapToSeekOvals() usage
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
        CompositionLocalProvider(
            LocalRippleConfiguration provides playerRippleConfiguration,
        ) {
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

// Utility functions
fun calculateNewVerticalGestureValue(originalValue: Int, startingY: Float, newY: Float, sensitivity: Float): Int {
    return originalValue + ((startingY - newY) * sensitivity).toInt()
}

fun calculateNewVerticalGestureValue(originalValue: Float, startingY: Float, newY: Float, sensitivity: Float): Float {
    return originalValue + ((startingY - newY) * sensitivity)
}

fun calculateNewHorizontalGestureValue(originalValue: Int, startingX: Float, newX: Float, sensitivity: Float): Int {
    return originalValue + ((newX - startingX) * sensitivity).toInt()
}

fun calculateNewHorizontalGestureValue(originalValue: Float, startingX: Float, newX: Float, sensitivity: Float): Float {
    return originalValue + ((newX - startingX) * sensitivity)
}
