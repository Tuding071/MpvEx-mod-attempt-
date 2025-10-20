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

    private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
        PlayerViewModelProviderFactory(this)
    }
    private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }
    private val playerObserver by lazy { PlayerObserver(this) }

    private val playbackStateRepository: PlaybackStateRepository by inject()
    val player by lazy { binding.player }
    val windowInsetsController by lazy {
        WindowCompat.getInsetsController(window, window.decorView)
    }
    val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val playerPreferences: PlayerPreferences by inject()
    private val audioPreferences: AudioPreferences by inject()
    private val subtitlesPreferences: SubtitlesPreferences by inject()
    private val advancedPreferences: AdvancedPreferences by inject()
    private val fileManager: FileManager by inject()

    private var fileName = ""
    private lateinit var pipHelper: MPVPipHelper
    private var systemUIRestored = false
    private var noisyReceiverRegistered = false
    private var audioFocusRequested = false

    private lateinit var gestureDetector: GestureDetector

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
            -> pausePlayback()
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
        setupTapGesture()

        // Start playback
        getPlayableUri(intent)?.let(player::playFile)
        setOrientation()
    }

    private fun setupTapGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                viewModel.pauseUnpause()
                return true
            }
        })

        binding.player.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
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
                if (isInPipMode) hideAllUIElements()
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
        if (!audioFocusRequested) Log.w(TAG, "Failed to obtain audio focus")
    }

    private fun getPlayableUri(intent: Intent): String? {
        val uri = parsePathFromIntent(intent) ?: return null
        return if (uri.startsWith("content://")) uri.toUri().openContentFd(this) else uri
    }

    private fun pausePlayback() {
        viewModel.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // ========================= Remaining functions from original code =========================
    // All your original methods like:
    // copyMPVAssets(), copyMPVScripts(), copyMPVConfigFiles(), copyMPVFonts(),
    // setOrientation(), restoreSystemUI(), saveVideoPlaybackState(), setReturnIntent(),
    // parsePathFromIntent(intent), onConfigurationChanged, onStart, onStop, onPause, onDestroy, etc.
    // ... are assumed unchanged and included here for brevity.
    // They must be copied exactly from your previous working PlayerActivity.

    companion object {
        private const val TAG = "mpvex"
    }
}
