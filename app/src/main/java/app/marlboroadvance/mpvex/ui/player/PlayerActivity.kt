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
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.Modifier
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
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
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

@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity : AppCompatActivity() {

  // ViewModels and Bindings
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }
  private val playerObserver by lazy { PlayerObserver(this) }

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

  // Gesture detector for tap-to-pause
  private lateinit var gestureDetector: GestureDetector

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

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    setupMPV()
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    setupPipHelper()
    setupAudioFocus()

    // Gesture detector for tap-to-pause
    gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
        viewModel.pauseUnpause() // Toggle pause/play
        return true
      }
    })

    // Attach gesture detector to player view
    binding.player.setOnTouchListener { _, event ->
      gestureDetector.onTouchEvent(event)
      true
    }

    // Start playback
    getPlayableUri(intent)?.let(player::playFile)
    setOrientation()
  }

  private fun setupBackPressHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  private fun handleBackPress() {
    val shouldEnterPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      pipHelper.isPipSupported &&
      viewModel.paused != true &&
      playerPreferences.automaticallyEnterPip.get()

    if (shouldEnterPip &&
      viewModel.sheetShown.value == Sheets.None &&
      viewModel.panelShown.value == Panels.None
    ) {
      pipHelper.enterPipMode()
    } else {
      finish()
    }
  }

  private fun setupPlayerControls() {
    binding.controls.setContent { /* no UI */ }
  }

  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(
      activity = this,
      mpvView = player,
      autoPipEnabled = playerPreferences.automaticallyEnterPip.get(),
      onPipModeChanged = { isInPipMode ->
        if (isInPipMode) {
          hideAllUIElements()
        }
      },
    )
  }

  private fun hideAllUIElements() {
    viewModel.hideControls()
    viewModel.hideSeekBar()
    viewModel.isBrightnessSliderShown.update { false }
    viewModel.isVolumeSliderShown.update { false }
    viewModel.sheetShown.update { Sheets.None }
    viewModel.panelShown.update { Panels.None }
  }

  private fun setupAudioFocus() {
    val result = audioManager.requestAudioFocus(
      audioFocusChangeListener,
      AudioManager.STREAM_MUSIC,
      AudioManager.AUDIOFOCUS_GAIN,
    )
    audioFocusRequested = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    if (!audioFocusRequested) {
      Log.w(TAG, "Failed to obtain audio focus")
    }
  }

  private fun getPlayableUri(intent: Intent): String? {
    val uri = parsePathFromIntent(intent) ?: return null
    return if (uri.startsWith("content://")) {
      uri.toUri().openContentFd(this)
    } else {
      uri
    }
  }

  override fun onDestroy() {
    Log.d(TAG, "Exiting PlayerActivity")

    try {
      if (isFinishing && !systemUIRestored) {
        restoreSystemUI()
      }

      cleanupMPV()
      cleanupAudio()
      cleanupReceivers()
    } catch (e: Exception) {
      Log.e(TAG, "Error during onDestroy", e)
    } finally {
      super.onDestroy()
    }
  }

  private fun cleanupMPV() {
    player.isExiting = true

    if (!isFinishing) return

    try {
      MPVLib.setPropertyString("pause", "yes")
      Thread.sleep(PAUSE_DELAY_MS)

      MPVLib.command("quit")
      Thread.sleep(QUIT_DELAY_MS)
    } catch (e: Exception) {
      Log.e(TAG, "Error quitting MPV", e)
    }

    try {
      MPVLib.removeObserver(playerObserver)
      Thread.sleep(OBSERVER_REMOVAL_DELAY_MS)
    } catch (e: Exception) {
      Log.e(TAG, "Error removing MPV observer", e)
    }

    try {
      MPVLib.destroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying MPV (may be expected)", e)
    }
  }

  private fun cleanupAudio() {
    if (audioFocusRequested) {
      try {
        audioManager.abandonAudioFocus(audioFocusChangeListener)
        audioFocusRequested = false
      } catch (e: Exception) {
        Log.e(TAG, "Error abandoning audio focus", e)
      }
    }
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      try {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      } catch (e: Exception) {
        Log.e(TAG, "Error unregistering noisy receiver", e)
      }
    }
  }

  override fun onPause() {
    try {
      val isInPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        isInPictureInPictureMode

      if (!isInPip) viewModel.pause()

      saveVideoPlaybackState(fileName)

      if (isFinishing && !isInPip && !systemUIRestored) restoreSystemUI()
    } catch (e: Exception) {
      Log.e(TAG, "Error during onPause", e)
    } finally {
      super.onPause()
    }
  }

  override fun finish() {
    try {
      if (!systemUIRestored) restoreSystemUI()
      setReturnIntent()
    } catch (e: Exception) {
      Log.e(TAG, "Error during finish", e)
    } finally {
      super.finish()
    }
  }

  override fun onStop() {
    try {
      pipHelper.onStop()
      saveVideoPlaybackState(fileName)
      viewModel.pause()
      unregisterNoisyReceiver()
    } catch (e: Exception) {
      Log.e(TAG, "Error during onStop", e)
    } finally {
      super.onStop()
    }
  }

  private fun unregisterNoisyReceiver() {
    if (noisyReceiverRegistered) {
      try {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      } catch (e: Exception) {
        Log.e(TAG, "Error unregistering noisy receiver in onStop", e)
      }
    }
  }

  @SuppressLint("NewApi")
  override fun onUserLeaveHint() {
    pipHelper.onUserLeaveHint()
    super.onUserLeaveHint()
  }

  override fun onStart() {
    super.onStart()
    try {
      setupWindowFlags()
      setupSystemUI()
      registerNoisyReceiver()
      restoreBrightness()
    } catch (e: Exception) {
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipHelper.isPipSupported) {
      pipHelper.updatePictureInPictureParams()
    }

    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  @Suppress("detekt.Indentation")
  private fun setupSystemUI() {
    @Suppress("DEPRECATION")
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
      View.SYSTEM_UI_FLAG_LOW_PROFILE

    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
    windowInsetsController.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    setupDisplayCutout()
  }

  private fun setupDisplayCutout() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode =
        if (playerPreferences.drawOverDisplayCutout.get()) {
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
    }
  }

  private fun restoreBrightness() {
    if (playerPreferences.rememberBrightness.get()) {
      val brightness = playerPreferences.defaultBrightness.get()
      if (brightness != BRIGHTNESS_NOT_SET) {
        viewModel.changeBrightnessTo(brightness)
      }
    }
  }

  private fun registerNoisyReceiver() {
    if (!noisyReceiverRegistered) {
      val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
      registerReceiver(noisyReceiver, filter)
      noisyReceiverRegistered = true
    }
  }

  private fun pausePlayback() {
    viewModel.pause()
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun copyMPVAssets() {
    Utils.copyAssets(this@PlayerActivity)
    copyMPVScripts()
    copyMPVConfigFiles()
    lifecycleScope.launch(Dispatchers.IO) {
      copyMPVFonts()
    }
  }

  private fun setupMPV() {
    copyMPVAssets()
    player.initialize(filesDir.path, cacheDir.path)
    MPVLib.addObserver(playerObserver)
  }

  private fun setupAudio() {
    audioPreferences.audioChannels.get().let {
      MPVLib.setPropertyString(it.property, it.value)
    }
  }

  // --- rest of the PlayerActivity methods unchanged ---

  companion object {
    private const val RESULT_INTENT = "app.marlboroadvance.mpvex.ui.player.PlayerActivity.result"

    private const val PAUSE_DELAY_MS = 100L
    private const val QUIT_DELAY_MS = 150L
    private const val OBSERVER_REMOVAL_DELAY_MS = 50L

    private const val BRIGHTNESS_NOT_SET = -1f
    private const val POSITION_NOT_SET = 0
    private const val MAX_MPV_VOLUME = 100
    private const val MILLISECONDS_TO_SECONDS = 1000
    private const val DELAY_DIVISOR = 1000.0
    private const val DEFAULT_PLAYBACK_SPEED = 1.0
    private const val DEFAULT_SUB_SPEED = 1.0
  }
}

const val TAG = "mpvex"
