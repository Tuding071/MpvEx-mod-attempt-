package app.marlboroadvance.mpvex.ui.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File
import android.graphics.Color
import android.view.Gravity
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import kotlin.math.abs

/**
 * Main player activity that handles video playback using MPV library.
 * Manages the lifecycle of the player, audio focus, picture-in-picture mode,
 * and playback state persistence.
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity : AppCompatActivity() {

  // ViewModels and Bindings
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  // Repositories
  private val playbackStateRepository: PlaybackStateRepository by inject()

  // Views and Controllers
  val player by lazy { binding.player }
  val windowInsetsController by lazy {
    WindowCompat.getInsetsController(window, window.decorView)
  }
  val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

  // Preferences
  private val playerPreferences: PlayerPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val fileManager: FileManager by inject()

  // State variables
  private var fileName = ""
  private lateinit var pipHelper: MPVPipHelper
  private var systemUIRestored = false
  private var noisyReceiverRegistered = false
  private var audioFocusRequested = false

  // Receivers and Listeners
  private val noisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
        pausePlayback()
      }
    }
  }

  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_LOSS,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      -> {
        pausePlayback()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> viewModel.pause()
      AudioManager.AUDIOFOCUS_GAIN -> Unit
    }
  }

  private lateinit var pauseText: TextView
  private lateinit var gestureLayer: View
  private val hideRunnable = Runnable { pauseText.visibility = View.GONE }
  private val handler = Handler(Looper.getMainLooper())
  private var downX = 0f
  private var downY = 0f
  private var downTime = 0L
  private var isLongPress = false
  private var isDragging = false
  private var originalSpeed = 1.0
  private val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
  private val longPressRunnable = Runnable {
    isLongPress = true
    originalSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0
    MPVLib.setPropertyDouble("speed", 2.0)
    pauseText.text = "2x"
    pauseText.visibility = View.VISIBLE
  }

  private lateinit var gestureDetector: GestureDetector
  private var startingPosition = 0
  private var startingX = 0f
  private var startingY = 0f
  private var mpvVolumeStartingY = 0f
  private var originalVolume = 0
  private var originalMPVVolume = 100
  private var wasPlayerAlreadyPaused = false
  private val horizontalSensitivity = 0.15f
  private val volumeGestureSens = 0.03f
  private val mpvVolumeGestureSens = 0.02f

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    // Add gesture layer
    gestureLayer = View(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.TRANSPARENT)
      isClickable = true
    }
    binding.root.addView(gestureLayer)

    // Add pause text (for feedback, can remove if no UI wanted)
    pauseText = TextView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER
      )
      setTextColor(Color.WHITE)
      textSize = 24f
      visibility = View.GONE
    }
    binding.root.addView(pauseText)

    // Setup gesture detector for taps and double taps
    gestureDetector = GestureDetector(this, object : SimpleOnGestureListener() {
      override fun onDoubleTap(e: MotionEvent): Boolean {
        val width = gestureLayer.width.toFloat()
        val x = e.x
        val doubleTapToSeekDuration = viewModel.doubleTapToSeekDuration
        val preciseSeeking = playerPreferences.preciseSeeking.get()
        if (x < width * 2 / 5) {
          viewModel.seekBy(-doubleTapToSeekDuration, preciseSeeking)
        } else if (x > width * 3 / 5) {
          viewModel.seekBy(doubleTapToSeekDuration, preciseSeeking)
        } else {
          viewModel.pauseUnpause()
        }
        return true
      }

      override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        // Single tap can be no-op or pauseUnpause if desired
        // viewModel.pauseUnpause()
        return true
      }

      override fun onLongPress(e: MotionEvent) {
        val multipleSpeedGesture = playerPreferences.holdForMultipleSpeed.get()
        if (multipleSpeedGesture != 0f && !viewModel.paused) {
          originalSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0
          MPVLib.setPropertyDouble("speed", multipleSpeedGesture.toDouble())
        }
      }
    })

    // Setup touch listener
    gestureLayer.setOnTouchListener { v, event ->
      gestureDetector.onTouchEvent(event)
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.x
          downY = event.y
          isDragging = false
          startingPosition = viewModel.pos ?: 0
          startingX = event.x
          startingY = event.y
          mpvVolumeStartingY = 0f
          originalVolume = viewModel.currentVolume.value
          originalMPVVolume = viewModel.currentMPVVolume ?: 100
          wasPlayerAlreadyPaused = viewModel.paused ?: false
          viewModel.pause()
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.x - downX
          val dy = event.y - downY
          if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            isDragging = true
          }
          if (isDragging) {
            if (abs(dx) > abs(dy) * 1.5) {
              // Horizontal drag: seek
              val newPos = (startingPosition + (dx * horizontalSensitivity)).toInt()
              val dur = viewModel.duration ?: 0
              viewModel.seekTo(newPos.coerceIn(0, dur), playerPreferences.preciseSeeking.get())
            } else if (abs(dy) > abs(dx) * 1.5) {
              // Vertical drag: volume
              val amount = -dy
              val volumeBoostingCap = audioPreferences.volumeBoostCap.get()
              if (volumeBoostingCap > 0 && viewModel.currentVolume.value == viewModel.maxVolume &&
                  (viewModel.currentMPVVolume ?: 100) - 100 < volumeBoostingCap && amount < 0
              ) {
                if (mpvVolumeStartingY == 0f) mpvVolumeStartingY = event.y
                viewModel.changeMPVVolumeTo(
                  (originalMPVVolume + ((mpvVolumeStartingY - event.y) * mpvVolumeGestureSens).toInt()).coerceIn(100..volumeBoostingCap + 100)
                )
              } else if (volumeBoostingCap > 0 && viewModel.currentVolume.value == viewModel.maxVolume &&
                  (viewModel.currentMPVVolume ?: 100) - 100 in 1..volumeBoostingCap && amount > 0
              ) {
                if (mpvVolumeStartingY == 0f) mpvVolumeStartingY = event.y
                viewModel.changeMPVVolumeTo(
                  (originalMPVVolume + ((mpvVolumeStartingY - event.y) * mpvVolumeGestureSens).toInt()).coerceIn(100..volumeBoostingCap + 100)
                )
              } else {
                if (startingY == 0f) startingY = event.y
                viewModel.changeVolumeTo(
                  (originalVolume + ((startingY - event.y) * volumeGestureSens).toInt())
                )
              }
            }
          }
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          if (isDragging) {
            if (!wasPlayerAlreadyPaused) viewModel.unpause()
          } else if (isLongPress) {
            MPVLib.setPropertyDouble("speed", originalSpeed)
          } else {
            // Reset speed if long press released
            val multipleSpeedGesture = playerPreferences.holdForMultipleSpeed.get()
            if (multipleSpeedGesture != 0f) {
              MPVLib.setPropertyDouble("speed", 1.0)
            }
          }
          isDragging = false
          isLongPress = false
          true
        }
        else -> false
      }
    }

    setupMPV()
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    setupPipHelper()
    setupAudioFocus()

    // Start playback
    getPlayableUri(intent)?.let(player::playFile)
    setOrientation()
  }

  // Rest of the class remains the same as provided, with UI methods no-op if already edited
  // For example, setupPlayerControls() { /* empty */ }
  // hideAllUIElements() { /* no-op */ }
  // etc.

  companion object {
    private const val RESULT_INTENT = "app.marlboroadvance.mpvex.ui.player.PlayerActivity.result"

    // Timing constants
    private const val PAUSE_DELAY_MS = 100L
    private const val QUIT_DELAY_MS = 150L
    private const val OBSERVER_REMOVAL_DELAY_MS = 50L

    // Value constants
    private const val BRIGHTNESS_NOT_SET = -1f
    private const val POSITION_NOT_SET = 0
    private const val MAX_MPV_VOLUME = 100
    private const val MILLISECONDS_TO_SECONDS = 1000
    private const val DELAY_DIVISOR = 1000.0
    private const val DEFAULT_PLAYBACK_SPEED = 1.0
    private const val DEFAULT_SUB_SPEED = 1.0
  }
}
