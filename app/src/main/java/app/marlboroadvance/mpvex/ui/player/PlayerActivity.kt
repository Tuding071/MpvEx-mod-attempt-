package app.marlboroadvance.mpvex.ui.player

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.databinding.ActivityPlayerBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        observeViewModel()
        setupVideoSurface()

        // Start playback from intent
        intent?.data?.let { uri ->
            viewModel.playUri(uri)
        }
    }

    private fun setupVideoSurface() {
        // Attach MPV view to surface (assumes MPVView in layout)
        binding.mpvView.attachToPlayer(viewModel.mpv)
    }

    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                viewModel.pauseUnpause() // <-- toggle pause/resume
                return true
            }
        })

        binding.mpvView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.paused.collectLatest { paused ->
                // Optionally show UI feedback, e.g., toast or overlay
                // Example: binding.pauseOverlay.visibility = if (paused) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
        restoreSystemUI()
    }

    override fun onPause() {
        super.onPause()
        saveVideoPlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.releasePlayer()
    }

    private fun restoreSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        supportActionBar?.hide()
    }

    private fun saveVideoPlaybackState() {
        // Save current playback position, speed, etc.
        viewModel.savePlaybackState()
    }

    // Optional: handle orientation changes if needed
    private fun setOrientation(orientation: Int) {
        requestedOrientation = orientation
    }
}
