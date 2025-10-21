package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * CustomGestureLayer - Invisible gesture overlay that uses existing MPVLib commands
 * 
 * Features:
 * - Single tap: Toggle play/pause
 * - Long press (tap & hold): 2x speed while holding
 * - Horizontal swipe: Frame-by-frame navigation
 * 
 * Usage: Add this on top of your player surface (above or below existing GestureHandler)
 */
@Composable
fun CustomGestureLayer(
    modifier: Modifier = Modifier,
    pixelThreshold: Float = 50f,      // Pixels to trigger one frame step
    longPressMillis: Long = 500L,     // How long to hold for 2x speed
    enabled: Boolean = true            // Easy on/off switch
) {
    if (!enabled) return
    
    val scope = rememberCoroutineScope()
    val gestureState = remember { CustomGestureState(pixelThreshold, longPressMillis) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        gestureState.handleTap()
                    },
                    onPress = {
                        gestureState.startLongPress(scope)
                        tryAwaitRelease()
                        gestureState.endLongPress()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        gestureState.startSwipe(offset.x)
                    },
                    onDrag = { change, _ ->
                        gestureState.updateSwipe(change.position.x)
                    },
                    onDragEnd = {
                        gestureState.endSwipe()
                    }
                )
            }
    )
}

/**
 * Internal state management - handles all gesture logic
 */
private class CustomGestureState(
    private val pixelThreshold: Float,
    private val longPressMillis: Long
) {
    // Long press state
    private var isLongPressing = false
    private var longPressJob: Job? = null
    private var originalSpeed = 1f
    
    // Swipe state
    private var startX = 0f
    private var currentX = 0f
    private var accumulatedDelta = 0f
    private var isSwiping = false
    
    /**
     * TAP GESTURE - Toggle Play/Pause
     * Uses existing MPVLib command from GestureHandler pattern
     */
    fun handleTap() {
        // Use the same method as GestureHandler's center double tap
        val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
        MPVLib.setPropertyBoolean("pause", !isPaused)
    }
    
    /**
     * LONG PRESS START - Activate 2x Speed
     */
    fun startLongPress(scope: kotlinx.coroutines.CoroutineScope) {
        longPressJob?.cancel()
        longPressJob = scope.launch {
            delay(longPressMillis)
            
            // Check if not paused before activating speed
            val isPaused = MPVLib.getPropertyBoolean("pause") ?: true
            if (!isPaused && !isSwiping) {
                originalSpeed = MPVLib.getPropertyFloat("speed") ?: 1f
                isLongPressing = true
                
                // Set 2x speed using the same method as GestureHandler
                MPVLib.setPropertyFloat("speed", 2.0f)
            }
        }
    }
    
    /**
     * LONG PRESS END - Restore Original Speed
     */
    fun endLongPress() {
        longPressJob?.cancel()
        
        if (isLongPressing) {
            // Restore original speed
            MPVLib.setPropertyFloat("speed", originalSpeed)
            isLongPressing = false
        }
    }
    
    /**
     * SWIPE START - Initialize swipe tracking
     */
    fun startSwipe(x: Float) {
        startX = x
        currentX = x
        accumulatedDelta = 0f
        isSwiping = true
        
        // Cancel long press if swiping
        if (isLongPressing) {
            MPVLib.setPropertyFloat("speed", originalSpeed)
            isLongPressing = false
        }
        longPressJob?.cancel()
    }
    
    /**
     * SWIPE UPDATE - Frame stepping based on pixel threshold
     * Uses existing MPVLib frame-step commands from GestureHandler pattern
     */
    fun updateSwipe(x: Float) {
        if (!isSwiping) return
        
        val delta = x - currentX
        currentX = x
        accumulatedDelta += delta
        
        // Trigger frame step when threshold is crossed
        when {
            accumulatedDelta >= pixelThreshold -> {
                // Step forward using the same command as GestureHandler uses
                MPVLib.command(arrayOf("frame-step"))
                accumulatedDelta = 0f
            }
            accumulatedDelta <= -pixelThreshold -> {
                // Step backward
                MPVLib.command(arrayOf("frame-back-step"))
                accumulatedDelta = 0f
            }
        }
    }
    
    /**
     * SWIPE END - Clean up swipe state
     */
    fun endSwipe() {
        isSwiping = false
        accumulatedDelta = 0f
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * INTEGRATION GUIDE
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * 1. ADD TO YOUR PLAYER SCREEN:
 * 
 * @Composable
 * fun PlayerScreen(viewModel: PlayerViewModel) {
 *     Box(modifier = Modifier.fillMaxSize()) {
 *         // Your video surface
 *         VideoSurface()
 *         
 *         // Your hidden player UI (the one you already hid)
 *         // ...
 *         
 *         // ADD THIS CUSTOM GESTURE LAYER
 *         CustomGestureLayer(
 *             modifier = Modifier.fillMaxSize(),
 *             pixelThreshold = 50f,      // Adjust for frame step sensitivity
 *             longPressMillis = 500L,    // Adjust for 2x speed trigger time
 *             enabled = true
 *         )
 *     }
 * }
 * 
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * 2. LAYER ORDER MATTERS:
 * 
 * Option A - Above original GestureHandler (intercepts all gestures):
 *     Box {
 *         VideoSurface()
 *         GestureHandler()           // Original (will be blocked)
 *         CustomGestureLayer()       // Yours (captures gestures first)
 *     }
 * 
 * Option B - Below original GestureHandler (gestures pass through):
 *     Box {
 *         VideoSurface()
 *         CustomGestureLayer()       // Yours (gets gestures)
 *         GestureHandler()           // Original (may interfere)
 *     }
 * 
 * Option C - Replace original GestureHandler entirely:
 *     Box {
 *         VideoSurface()
 *         // GestureHandler() <- COMMENTED OUT
 *         CustomGestureLayer()       // Only yours active
 *     }
 * 
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * 3. CONFIGURATION TUNING:
 * 
 * pixelThreshold:
 *   - Lower (20-40f)  = More sensitive, faster frame stepping
 *   - Higher (60-100f) = Less sensitive, need bigger swipe
 * 
 * longPressMillis:
 *   - Shorter (300L) = Quicker 2x speed activation
 *   - Longer (700L)  = Need to hold longer for 2x speed
 * 
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * 4. TESTING YOUR GESTURES:
 * 
 * • Single tap anywhere → Should pause/resume playback
 * • Tap and hold 500ms → Should speed up to 2x (release = normal speed)
 * • Swipe left/right → Should step through frames (50 pixels per frame)
 * 
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * 5. TROUBLESHOOTING:
 * 
 * Q: Gestures not working?
 * A: Make sure CustomGestureLayer is ABOVE other gesture handlers in the Box
 * 
 * Q: Double gestures happening?
 * A: You have both CustomGestureLayer AND original GestureHandler active.
 *    Either remove GestureHandler or adjust layer order.
 * 
 * Q: Frame stepping too fast/slow?
 * A: Adjust pixelThreshold value (lower = faster, higher = slower)
 * 
 * Q: 2x speed activating too easily?
 * A: Increase longPressMillis value (e.g., 700L or 1000L)
 * 
 * ═══════════════════════════════════════════════════════════════════════════
 */
