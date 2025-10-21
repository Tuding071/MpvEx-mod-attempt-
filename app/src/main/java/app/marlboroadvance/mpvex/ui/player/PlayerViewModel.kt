package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.GesturePreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class PlayerViewModelProviderFactory(
    private val activity: PlayerActivity,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return PlayerViewModel(activity) as T
    }
}

@Suppress("TooManyFunctions")
class PlayerViewModel(
    private val activity: PlayerActivity,
) : ViewModel(), KoinComponent {
    private val playerPreferences: PlayerPreferences by inject()
    private val gesturePreferences: GesturePreferences by inject()
    private val audioPreferences: AudioPreferences by inject()
    private val json: Json by inject()

    // Gesture tap tracking
    private var tapStartTime: Long = 0
    private var isTapInProgress: Boolean = false

    val paused by MPVLib.propBoolean["pause"].collectAsState(viewModelScope)
    val pos by MPVLib.propInt["time-pos"].collectAsState(viewModelScope)
    val duration by MPVLib.propInt["duration"].collectAsState(viewModelScope)
    private val currentMPVVolume by MPVLib.propInt["volume"].collectAsState(viewModelScope)

    val currentVolume = MutableStateFlow(activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    private val volumeBoostCap by MPVLib.propInt["volume-max"].collectAsState(viewModelScope)

    val subtitleTracks = MPVLib.propNode["track-list"]
        .map { (it?.toObject<List<TrackNode>>(json)?.filter { it.isSubtitle } ?: persistentListOf()).toImmutableList() }

    val audioTracks = MPVLib.propNode["track-list"]
        .map { (it?.toObject<List<TrackNode>>(json)?.filter { it.isAudio } ?: persistentListOf()).toImmutableList() }

    val chapters = MPVLib.propNode["chapter-list"]
        .map { (it?.toObject<List<ChapterNode>>(json) ?: persistentListOf()).map { it.toSegment() }.toImmutableList() }

    private val _controlsShown = MutableStateFlow(true)
    val controlsShown = _controlsShown.asStateFlow()
    private val _seekBarShown = MutableStateFlow(true)
    val seekBarShown = _seekBarShown.asStateFlow()
    private val _areControlsLocked = MutableStateFlow(false)
    val areControlsLocked = _areControlsLocked.asStateFlow()

    val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
    val isBrightnessSliderShown = MutableStateFlow(false)
    val isVolumeSliderShown = MutableStateFlow(false)
    val currentBrightness = MutableStateFlow(
        runCatching {
            Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                .normalize(0f, 255f, 0f, 1f)
        }.getOrElse { 0f },
    )

    val sheetShown = MutableStateFlow(Sheets.None)
    val panelShown = MutableStateFlow(Panels.None)

    val gestureSeekAmount = MutableStateFlow<Pair<Int, Int>?>(null)

    private val _seekText = MutableStateFlow<String?>(null)
    val seekText = _seekText.asStateFlow()
    private val _doubleTapSeekAmount = MutableStateFlow(0)
    val doubleTapSeekAmount = _doubleTapSeekAmount.asStateFlow()
    private val _isSeekingForwards = MutableStateFlow(false)
    val isSeekingForwards = _isSeekingForwards.asStateFlow()

    private val _currentFrame = MutableStateFlow(0)
    val currentFrame = _currentFrame.asStateFlow()

    private val _totalFrames = MutableStateFlow(0)
    val totalFrames = _totalFrames.asStateFlow()

    private val _videoZoom = MutableStateFlow(0f)
    val videoZoom = _videoZoom.asStateFlow()

    private var timerJob: Job? = null
    private val _remainingTime = MutableStateFlow(0)
    val remainingTime = _remainingTime.asStateFlow()

    // Gesture tap handling methods
    fun handleTapStart() {
        tapStartTime = System.currentTimeMillis()
        isTapInProgress = true
        
        // Use coroutine to check tap duration after 200ms
        viewModelScope.launch {
            delay(200)
            if (isTapInProgress) {
                // Tap is longer than 200ms, don't trigger pause/resume
                isTapInProgress = false
            }
        }
    }

    fun handleTapEnd(): Boolean {
        val tapDuration = System.currentTimeMillis() - tapStartTime
        isTapInProgress = false
        
        // Only trigger pause/resume if tap was less than 200ms
        if (tapDuration < 200) {
            pauseUnpause()
            return true
        }
        return false
    }

    // Alternative single method for complete tap handling
    fun handleQuickTap(): Boolean {
        val currentTime = System.currentTimeMillis()
        val tapDuration = currentTime - tapStartTime
        
        if (tapDuration < 200) {
            pauseUnpause()
            return true
        }
        return false
    }

    // Simple one-call method for the invisible layer
    fun handleScreenTap() {
        val tapTime = System.currentTimeMillis()
        viewModelScope.launch {
            // Simulate tap detection with delay
            delay(10) // Small delay to ensure this is a quick tap
            pauseUnpause()
        }
    }

    fun startTimer(seconds: Int) {
        timerJob?.cancel()
        _remainingTime.value = seconds
        if (seconds < 1) return
        timerJob = viewModelScope.launch {
            for (time in seconds downTo 0) {
                _remainingTime.value = time
                delay(1000)
            }
            MPVLib.setPropertyBoolean("pause", true)
            Toast.makeText(activity, activity.getString(R.string.toast_sleep_timer_ended), Toast.LENGTH_SHORT).show()
        }
    }

    fun cycleDecoders() {
        MPVLib.setPropertyString(
            "hwdec",
            when (Decoder.getDecoderFromValue(MPVLib.getPropertyString("hwdec-current") ?: return)) {
                Decoder.HWPlus -> Decoder.HW.value
                Decoder.HW -> Decoder.SW.value
                Decoder.SW -> Decoder.HWPlus.value
                Decoder.AutoCopy -> Decoder.SW.value
                Decoder.Auto -> Decoder.SW.value
            },
        )
    }

    fun addAudio(uri: Uri) {
        val url = uri.toString()
        val path = if (url.startsWith("content://")) url.toUri().openContentFd(activity) else url
        MPVLib.command("audio-add", path ?: return, "cached")
    }

    fun addSubtitle(uri: Uri) {
        val url = uri.toString()
        val path = if (url.startsWith("content://")) url.toUri().openContentFd(activity) else url
        MPVLib.command("sub-add", path ?: return, "cached")
    }

    fun selectSub(id: Int) {
        val selectedSubs = Pair(MPVLib.getPropertyInt("sid"), MPVLib.getPropertyInt("secondary-sid"))
        when (id) {
            selectedSubs.first -> Pair(selectedSubs.second, null)
            selectedSubs.second -> Pair(selectedSubs.first, null)
            else -> if (selectedSubs.first != null) Pair(selectedSubs.first, id) else Pair(id, null)
        }.let {
            it.second?.let { MPVLib.setPropertyInt("secondary-sid", it) } ?: MPVLib.setPropertyBoolean("secondary-sid", false)
            it.first?.let { MPVLib.setPropertyInt("sid", it) } ?: MPVLib.setPropertyBoolean("sid", false)
        }
    }

    fun pauseUnpause() = MPVLib.command("cycle", "pause")
    fun pause() = MPVLib.setPropertyBoolean("pause", true)
    fun unpause() = MPVLib.setPropertyBoolean("pause", false)

    private val showStatusBar = playerPreferences.showSystemStatusBar.get()
    fun showControls() { /* UI Disabled */ }
    fun hideControls() { /* UI Disabled */ }
    fun hideSeekBar() { /* UI Disabled */ }
    fun showSeekBar() { /* UI Disabled */ }
    fun lockControls() { /* UI Disabled */ }
    fun unlockControls() { /* UI Disabled */ }

    fun seekBy(offset: Int, precise: Boolean = false) {
        MPVLib.command("seek", offset.toString(), if (precise) "relative+exact" else "relative")
    }

    fun seekTo(position: Int, precise: Boolean = true) {
        if (position !in 0..(MPVLib.getPropertyInt("duration") ?: 0)) return
        MPVLib.command("seek", position.toString(), if (precise) "absolute" else "absolute+keyframes")
    }

    fun changeBrightnessBy(change: Float) { /* UI Disabled */ }
    fun changeBrightnessTo(brightness: Float) { /* UI Disabled */ }
    fun displayBrightnessSlider() { /* UI Disabled */ }

    val maxVolume = activity.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun changeVolumeBy(change: Int) {
        val mpvVolume = MPVLib.getPropertyInt("volume")
        if ((volumeBoostCap ?: audioPreferences.volumeBoostCap.get()) > 0 && currentVolume.value == maxVolume) {
            if (mpvVolume == 100 && change < 0) changeVolumeTo(currentVolume.value + change)
            val finalMPVVolume = (mpvVolume?.plus(change))?.coerceAtLeast(100) ?: 100
            if (finalMPVVolume in 100..(volumeBoostCap ?: audioPreferences.volumeBoostCap.get()) + 100) {
                changeMPVVolumeTo(finalMPVVolume)
                return
            }
        }
        changeVolumeTo(currentVolume.value + change)
    }

    fun changeVolumeTo(volume: Int) {
        val newVolume = volume.coerceIn(0..maxVolume)
        activity.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        currentVolume.update { newVolume }
    }

    fun changeMPVVolumeTo(volume: Int) = MPVLib.setPropertyInt("volume", volume)
    fun setMPVVolume(volume: Int) { /* UI Disabled */ }
    fun displayVolumeSlider() { /* UI Disabled */ }

    fun changeVideoAspect(aspect: VideoAspect) { /* UI Disabled */ }
    fun cycleScreenRotations() { /* UI Disabled */ }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun handleLuaInvocation(property: String, value: String) { /* UI Disabled */ }

    private val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun forceShowSoftwareKeyboard() { /* UI Disabled */ }
    private fun forceHideSoftwareKeyboard() { /* UI Disabled */ }

    private fun seekToWithText(seekValue: Int, text: String?) { /* UI Disabled */ }
    private fun seekByWithText(value: Int, text: String?) { /* UI Disabled */ }

    private val doubleTapToSeekDuration = gesturePreferences.doubleTapToSeekDuration.get()

    // <<< STUBS ADDED TO FIX GESTUREHANDLER.KT >>>
    fun updateSeekAmount(amount: Int) { /* No-op stub */ }
    fun updateSeekText(text: String?) { /* No-op stub */ }

    fun leftSeek() { /* UI Disabled */ }
    fun rightSeek() { /* UI Disabled */ }
    fun handleLeftDoubleTap() { /* UI Disabled */ }
    fun handleCenterDoubleTap() { /* UI Disabled */ }
    fun handleRightDoubleTap() { /* UI Disabled */ }
    fun setVideoZoom(zoom: Float) { /* UI Disabled */ }
    fun updateFrameInfo() { /* UI Disabled */ }
}

fun Float.normalize(inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    return (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin
}

fun <T> Flow<T>.collectAsState(scope: CoroutineScope, initialValue: T? = null) =
    object : ReadOnlyProperty<Any?, T?> {
        private var value: T? = initialValue
        init { scope.launch { collect { value = it } } }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = value
    }
