// CustomLayer.kt
package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.*
import `is`.xyz.mpv.MPVLib

class CustomLayer(context: Context) : FrameLayout(context) {

    private lateinit var gestureLayer: View
    private lateinit var pauseText: TextView
    private val gestureDetector: GestureDetector
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isFrameStepping = false
    
    private val hideRunnable = Runnable { 
        pauseText.visibility = View.GONE 
        pauseText.text = ""
    }

    init {
        // Setup gesture detector for tap (less than 200ms)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // onSingleTapConfirmed is called for taps less than 200ms
                handleTapGesture()
                return true
            }
        })

        setupLayer()
    }

    private fun setupLayer() {
        // Create completely transparent gesture layer that covers entire screen
        gestureLayer = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                return gestureDetector.onTouchEvent(event)
            }
        }.apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        addView(gestureLayer)

        // Create pause text view - properly centered at top
        pauseText = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (100 * resources.displayMetrics.density).toInt()
            }
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            visibility = View.GONE
            
            // Add black outline for visibility without background
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
        }
        addView(pauseText)
    }

    private fun handleTapGesture() {
        if (isFrameStepping) return
        
        scope.launch {
            isFrameStepping = true
            try {
                // Direct MPVLib communication - get current pause state
                val isPaused = MPVLib.getPropertyBoolean("pause") ?: false
                
                if (isPaused) {
                    // Resume playback directly via MPVLib
                    MPVLib.setPropertyBoolean("pause", false)
                    showPauseText("Resume", showBriefly = true)
                } else {
                    // Pause playback directly via MPVLib
                    MPVLib.setPropertyBoolean("pause", true)
                    showPauseText("Paused", showBriefly = false)
                }
                
                // Small delay to prevent rapid toggling
                delay(100)
            } catch (e: Exception) {
                Log.e("CustomLayer", "Error handling tap gesture", e)
            } finally {
                isFrameStepping = false
            }
        }
    }

    private fun showPauseText(text: String, showBriefly: Boolean) {
        // Remove any pending hide operations
        pauseText.removeCallbacks(hideRunnable)
        
        pauseText.text = text
        pauseText.visibility = View.VISIBLE
        
        if (showBriefly) {
            // Hide after 1 second for "Resume" text
            pauseText.postDelayed(hideRunnable, 1000)
        }
        // "Paused" text stays visible until resume
    }

    fun cleanup() {
        scope.cancel()
        pauseText.removeCallbacks(hideRunnable)
    }
}

// Simple logging
private fun Log.e(tag: String, message: String, e: Exception? = null) {
    android.util.Log.e(tag, message, e)
}
