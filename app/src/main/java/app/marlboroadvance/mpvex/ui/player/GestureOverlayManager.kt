package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

/**
 * Manages the invisible gesture overlay and feedback text for PlayerActivity
 */
class GestureOverlayManager(
    private val context: Context,
    private val rootView: View,
    private val viewModel: PlayerViewModel
) : LifecycleObserver {

    private lateinit var gestureOverlay: View
    private lateinit var feedbackTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    
    private var tapStartTime: Long = 0
    private val tapThresholdMs = 200L
    private var lastPauseState: Boolean? = null
    
    private val hideFeedbackRunnable = Runnable { hideFeedbackText() }

    fun setup() {
        setupGestureOverlay()
        setupFeedbackText()
    }

    private fun setupGestureOverlay() {
        gestureOverlay = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        rootView.addView(gestureOverlay)
        
        gestureOverlay.setOnTouchListener { _, event ->
            handleGesture(event)
            true
        }
    }

    private fun setupFeedbackText() {
        feedbackTextView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setPadding(32, 16, 32, 16)
        }
        
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 150
        }
        
        rootView.addView(feedbackTextView, layoutParams)
    }

    private fun handleGesture(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                tapStartTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val tapDuration = System.currentTimeMillis() - tapStartTime
                if (tapDuration <= tapThresholdMs) {
                    handleScreenTap()
                }
            }
        }
    }

    private fun handleScreenTap() {
        val wasPaused = viewModel.paused == true
        viewModel.pauseUnpause()
        
        if (wasPaused) {
            showFeedbackText("Resume")
        } else {
            showFeedbackText("Pause")
            lastPauseState = false
        }
        
        Log.d(TAG, "Screen tapped - Toggled play/pause using pauseUnpause")
    }

    private fun showFeedbackText(message: String) {
        handler.removeCallbacks(hideFeedbackRunnable)
        feedbackTextView.text = message
        feedbackTextView.visibility = View.VISIBLE
        handler.postDelayed(hideFeedbackRunnable, 1000L)
    }

    private fun hideFeedbackText() {
        feedbackTextView.visibility = View.GONE
    }

    fun onPauseStateChanged(isPaused: Boolean) {
        // Show resume text when transitioning from pause to play
        if (lastPauseState == false && !isPaused) {
            showFeedbackText("Resume")
            lastPauseState = true
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun cleanup() {
        handler.removeCallbacks(hideFeedbackRunnable)
        try {
            rootView.removeView(gestureOverlay)
            rootView.removeView(feedbackTextView)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up gesture overlay", e)
        }
    }
}
