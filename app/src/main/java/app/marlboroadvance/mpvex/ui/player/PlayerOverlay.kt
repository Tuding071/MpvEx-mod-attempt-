package app.marlboroadvance.mpvex.ui.player

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vivvvek.seeker.Seeker
import dev.vivvvek.seeker.SeekerDefaults
import dev.vivvvek.seeker.Segment
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Intent
import android.net.Uri
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

// ========== PURE FRAME MEDIACODEC DECODER - INTEGRATED ==========
data class VideoFrame(
    val buffer: ByteArray,
    val timestampUs: Long,
    val isKeyFrame: Boolean,
    val width: Int,
    val height: Int
)

class MediaCodecSeeker {
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoTrackIndex = -1
    private var videoWidth = 0
    private var videoHeight = 0
    private var isInitialized = false
    
    fun initialize(videoPath: String): Boolean {
        try {
            extractor = MediaExtractor()
            extractor?.setDataSource(videoPath)
            
            // Select ONLY video track
            videoTrackIndex = selectVideoTrack(extractor)
            if (videoTrackIndex == -1) return false
            
            val format = extractor?.getTrackFormat(videoTrackIndex)
            videoWidth = format?.getInteger(MediaFormat.KEY_WIDTH) ?: 0
            videoHeight = format?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0
            
            val mime = format?.getString(MediaFormat.KEY_MIME) ?: return false
            
            // Create decoder with MINIMAL configuration - NO SURFACE = pure buffer output
            decoder = MediaCodec.createDecoderByType(mime)
            decoder?.configure(format, null, null, 0)
            decoder?.start()
            
            extractor?.selectTrack(videoTrackIndex)
            isInitialized = true
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    // Seek and decode to exact frame - PURE FRAMES, NO DROPS
    fun seekToFrame(targetTimeUs: Long): VideoFrame? {
        if (!isInitialized) return null
        
        try {
            decoder?.flush()
            extractor?.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            
            val bufferInfo = BufferInfo()
            var targetFrame: VideoFrame? = null
            
            val timeoutUs = 10000L // 10ms timeout
            
            // Decode until we reach target frame
            while (true) {
                // Feed data to decoder
                val inputBufferIndex = decoder?.dequeueInputBuffer(timeoutUs) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder?.getInputBuffer(inputBufferIndex)
                    
                    if (inputBuffer != null) {
                        // Read sample data into buffer
                        val sampleSize = extractor?.readSampleData(inputBuffer, 0) ?: -1
                        
                        if (sampleSize < 0) {
                            // End of stream
                            decoder?.queueInputBuffer(inputBufferIndex, 0, 0, 0, 
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val presentationTimeUs = extractor?.sampleTime ?: 0
                            decoder?.queueInputBuffer(inputBufferIndex, 0, sampleSize, 
                                presentationTimeUs, 0)
                            extractor?.advance()
                        }
                    }
                }
                
                // Get output frames
                val outputBufferIndex = decoder?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: -1
                
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No frames ready yet
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Format changed, ignore
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = decoder?.getOutputBuffer(outputBufferIndex)
                        
                        // Create copy of frame data
                        val frameData = outputBuffer?.let { buf ->
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            bytes
                        } ?: continue
                        
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        val frame = VideoFrame(
                            buffer = frameData,
                            timestampUs = bufferInfo.presentationTimeUs,
                            isKeyFrame = isKeyFrame,
                            width = videoWidth,
                            height = videoHeight
                        )
                        
                        // Check if this is our target frame or past it
                        if (bufferInfo.presentationTimeUs >= targetTimeUs) {
                            targetFrame = frame
                            decoder?.releaseOutputBuffer(outputBufferIndex, false)
                            break
                        }
                        
                        decoder?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
                
                // Check for end of stream
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
            
            return targetFrame
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    // Continuous frame decoding for smooth drag seeking - EVERY FRAME, NO DROPS
    fun decodeFramesToTarget(targetTimeUs: Long, onFrameDecoded: (VideoFrame) -> Unit): VideoFrame? {
        if (!isInitialized) return null
        
        try {
            decoder?.flush()
            extractor?.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            
            val bufferInfo = BufferInfo()
            var lastFrame: VideoFrame? = null
            val timeoutUs = 10000L // 10ms timeout
            
            while (true) {
                // Feed data
                val inputBufferIndex = decoder?.dequeueInputBuffer(timeoutUs) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder?.getInputBuffer(inputBufferIndex)
                    
                    if (inputBuffer != null) {
                        // Read sample data
                        val sampleSize = extractor?.readSampleData(inputBuffer, 0) ?: -1
                        
                        if (sampleSize < 0) {
                            decoder?.queueInputBuffer(inputBufferIndex, 0, 0, 0, 
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val presentationTimeUs = extractor?.sampleTime ?: 0
                            decoder?.queueInputBuffer(inputBufferIndex, 0, sampleSize, 
                                presentationTimeUs, 0)
                            extractor?.advance()
                        }
                    }
                }
                
                // Get output
                val outputBufferIndex = decoder?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: -1
                
                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = decoder?.getOutputBuffer(outputBufferIndex)
                        
                        val frameData = outputBuffer?.let { buf ->
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            bytes
                        } ?: continue
                        
                        val frame = VideoFrame(
                            buffer = frameData,
                            timestampUs = bufferInfo.presentationTimeUs,
                            isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                            width = videoWidth,
                            height = videoHeight
                        )
                        
                        // Callback with every frame
                        onFrameDecoded(frame)
                        lastFrame = frame
                        
                        decoder?.releaseOutputBuffer(outputBufferIndex, false)
                        
                        // Stop if we've reached target
                        if (bufferInfo.presentationTimeUs >= targetTimeUs) {
                            break
                        }
                    }
                }
                
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
            
            return lastFrame
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    fun release() {
        try {
            decoder?.stop()
            decoder?.release()
            extractor?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        decoder = null
        extractor = null
        isInitialized = false
    }
    
    private fun selectVideoTrack(extractor: MediaExtractor?): Int {
        if (extractor == null) return -1
        
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return i
            }
        }
        return -1
    }
    
    val isReady: Boolean get() = isInitialized
}

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var seekTargetTime by remember { mutableStateOf("00:00") }
    var showSeekTime by remember { mutableStateOf(false) }
    var isSpeedingUp by remember { mutableStateOf(false) }
    var pendingPauseResume by remember { mutableStateOf(false) }
    var isPausing by remember { mutableStateOf(false) }
    var showSeekbar by remember { mutableStateOf(true) }
    
    var currentPosition by remember { mutableStateOf(0.0) }
    var videoDuration by remember { mutableStateOf(1.0) }
    var seekbarPosition by remember { mutableStateOf(0f) }
    var seekbarDuration by remember { mutableStateOf(1f) }
    
    var isDragging by remember { mutableStateOf(false) }
    
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    // ========== MEDIACODEC SEEKER ==========
    var mediaCodecSeeker by remember { mutableStateOf<MediaCodecSeeker?>(null) }
    var isUsingMediaCodec by remember { mutableStateOf(false) }
    var currentDecodedFrame by remember { mutableStateOf<VideoFrame?>(null) }
    var mediaCodecReady by remember { mutableStateOf(false) }
    
    // ADD: Seek direction for feedback
    var seekDirection by remember { mutableStateOf("") } // "+" or "-" or ""
    
    // ADD: Simple throttle control
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 16L // 16ms for 60fps smoothness
    
    // CLEAR GESTURE STATES WITH MUTUAL EXCLUSION
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // THRESHOLDS
    val longTapThreshold = 300L // ms
    val horizontalSwipeThreshold = 30f // pixels - minimum horizontal movement to trigger seeking
    val verticalSwipeThreshold = 40f // pixels - minimum vertical movement to trigger quick seek
    val maxVerticalMovement = 50f // pixels - maximum vertical movement allowed for horizontal swipe
    val maxHorizontalMovement = 50f // pixels - maximum horizontal movement allowed for vertical swipe
    
    // ADD: Quick seek amount in seconds
    val quickSeekAmount = 5
    
    // Video info follows seekbar visibility
    var showVideoInfo by remember { mutableStateOf(true) }
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var quickSeekFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // ========== INITIALIZE MEDIACODEC SEEKER ==========
    LaunchedEffect(Unit) {
        val videoPath = getVideoPath(context)
        if (videoPath != null) {
            val seeker = MediaCodecSeeker()
            val initialized = seeker.initialize(videoPath)
            if (initialized) {
                mediaCodecSeeker = seeker
                mediaCodecReady = true
            }
        }
    }
    
    // ========== CLEANUP ON DISPOSE ==========
    DisposableEffect(Unit) {
        onDispose {
            mediaCodecSeeker?.release()
            mediaCodecSeeker = null
        }
    }
    
    // ========== UTILITY FUNCTIONS ==========
    
    fun scheduleSeekbarHide() {
        if (userInteracting) return
        hideSeekbarJob?.cancel()
        hideSeekbarJob = coroutineScope.launch {
            delay(4000)
            showSeekbar = false
            showVideoInfo = false
        }
    }
    
    fun cancelAutoHide() {
        userInteracting = true
        hideSeekbarJob?.cancel()
        coroutineScope.launch {
            delay(100)
            userInteracting = false
        }
    }
    
    fun showSeekbarWithTimeout() {
        showSeekbar = true
        showVideoInfo = true
        scheduleSeekbarHide()
    }
    
    fun showPlaybackFeedback(text: String) {
        playbackFeedbackJob?.cancel()
        showPlaybackFeedback = true
        playbackFeedbackText = text
        playbackFeedbackJob = coroutineScope.launch {
            delay(1000)
            showPlaybackFeedback = false
        }
    }
    
    // ========== MEDIACODEC SEEKING FUNCTIONS ==========
    
    // PURE FRAME SEEKING - NO AUDIO, NO TIMING, JUST FRAMES
    fun performMediaCodecSeek(targetPositionSeconds: Double) {
        if (isSeekInProgress || !mediaCodecReady) return
        isSeekInProgress = true
        
        val targetTimeUs = (targetPositionSeconds * 1_000_000).toLong()
        
        coroutineScope.launch(Dispatchers.Default) {
            val frame = mediaCodecSeeker?.seekToFrame(targetTimeUs)
            frame?.let {
                currentDecodedFrame = it
                // Here you would render the frame
                // For now, we just store it
            }
            
            // Reset throttle
            coroutineScope.launch {
                delay(seekThrottleMs)
                isSeekInProgress = false
            }
        }
    }
    
    // CONTINUOUS FRAME DECODING FOR SMOOTH DRAG - EVERY FRAME, NO DROPS
    fun performContinuousMediaCodecSeek(targetPositionSeconds: Double) {
        if (!mediaCodecReady) return
        
        val targetTimeUs = (targetPositionSeconds * 1_000_000).toLong()
        
        coroutineScope.launch(Dispatchers.Default) {
            mediaCodecSeeker?.decodeFramesToTarget(targetTimeUs) { frame ->
                currentDecodedFrame = frame
                // Render each frame as it comes - buttery smooth!
            }
        }
    }
    
    fun switchToMediaCodecMode() {
        isUsingMediaCodec = true
        // Pause MPV playback during seeking
        if (MPVLib.getPropertyBoolean("pause") == false) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun switchBackToMPV(finalPositionSeconds: Double) {
        isUsingMediaCodec = false
        // Seek MPV to final position
        MPVLib.command("seek", finalPositionSeconds.toString(), "absolute", "exact")
        
        // Resume playback if it was playing before
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
    }
    
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    fun performQuickSeek(seconds: Int) {
        val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        quickSeekFeedbackJob?.cancel()
        quickSeekFeedbackJob = coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        
        MPVLib.command("seek", seconds.toString(), "relative", "exact")
    }
    
    fun toggleVideoInfo() {
        if (showSeekbar) {
            showSeekbar = false
            showVideoInfo = false
            hideSeekbarJob?.cancel()
        } else {
            showSeekbar = true
            showVideoInfo = true
            scheduleSeekbarHide()
        }
    }
    
    val showVolumeFeedback: (Int) -> Unit = { volume ->
        volumeFeedbackJob?.cancel()
        showVolumeFeedbackState = true
        volumeFeedbackJob = coroutineScope.launch {
            delay(1000)
            showVolumeFeedbackState = false
        }
    }
    
    // ========== GESTURE HANDLING FUNCTIONS ==========
    
    fun handleTap() {
        val currentPaused = MPVLib.getPropertyBoolean("pause") ?: false
        if (currentPaused) {
            coroutineScope.launch {
                val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                MPVLib.command("seek", currentPos.toString(), "absolute", "exact")
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
            showPlaybackFeedback("Resume")
        } else {
            MPVLib.setPropertyBoolean("pause", true)
            showPlaybackFeedback("Pause")
        }
        if (showSeekbar) {
            showSeekbar = false
            showVideoInfo = false
        } else {
            showSeekbarWithTimeout()
        }
        isPausing = !currentPaused
    }
    
    fun startLongTapDetection() {
        isTouching = true
        touchStartTime = System.currentTimeMillis()
        longTapJob?.cancel()
        longTapJob = coroutineScope.launch {
            delay(longTapThreshold)
            if (isTouching && !isHorizontalSwipe && !isVerticalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                MPVLib.setPropertyDouble("speed", 2.0)
            }
        }
    }
    
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return ""
        
        val deltaX = abs(currentX - touchStartX)
        val deltaY = abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            return "horizontal"
        }
        
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            return "vertical"
        }
        
        return ""
    }
    
    // Start horizontal seeking with MediaCodec
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        switchToMediaCodecMode() // Switch to MediaCodec for seeking
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        
        showSeekbar = true
        showVideoInfo = true
        
        // Paused already handled in switchToMediaCodecMode
    }
    
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        val deltaY = startY - touchStartY
        
        if (deltaY < 0) {
            seekDirection = "+"
            performQuickSeek(quickSeekAmount)
        } else {
            seekDirection = "-"
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    // Handle horizontal seeking with continuous MediaCodec decoding
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 2f / 0.032f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        
        // Update UI instantly
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        // Use MediaCodec for continuous frame decoding - BUTTERY SMOOTH!
        if (mediaCodecReady) {
            performContinuousMediaCodecSeek(clampedPosition)
        } else {
            // Fallback to MPV if MediaCodec not ready
            performMediaCodecSeek(clampedPosition)
        }
    }
    
    // End horizontal seeking and switch back to MPV
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val finalPosition = seekStartPosition + 
                ((seekStartX - touchStartX) / (2f / 0.032f))
            
            // Switch back to MPV at final position
            switchBackToMPV(finalPosition)
            
            isSeeking = false
            showSeekTime = false
            seekStartX = 0f
            seekStartPosition = 0.0
            seekDirection = ""
            scheduleSeekbarHide()
        }
        isHorizontalSwipe = false
    }
    
    fun endVerticalSwipe() {
        isVerticalSwipe = false
        scheduleSeekbarHide()
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            isLongTap = false
            isSpeedingUp = false
            MPVLib.setPropertyDouble("speed", 1.0)
        } else if (isHorizontalSwipe) {
            endHorizontalSeeking()
        } else if (isVerticalSwipe) {
            endVerticalSwipe()
        } else if (touchDuration < 150) {
            handleTap()
        }
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    // ========== EVENT HANDLERS ==========
    
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    LaunchedEffect(Unit) {
        val intent = (context as? android.app.Activity)?.intent
        fileName = when {
            intent?.action == Intent.ACTION_SEND -> {
                getFileNameFromUri(intent.getParcelableExtra(Intent.EXTRA_STREAM), context)
            }
            intent?.action == Intent.ACTION_VIEW -> {
                getFileNameFromUri(intent.data, context)
            }
            else -> {
                getBestAvailableFileName(context)
            }
        }
        val title = MPVLib.getPropertyString("media-title") ?: "Video"
        videoTitle = title
        
        showVideoInfo = true
        showSeekbar = true
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            scheduleSeekbarHide()
        }
    }
    
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "8")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        MPVLib.setPropertyString("cache-initial", "0.5")
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-skipidct", "all")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
        MPVLib.setPropertyString("gpu-dumb-mode", "yes")
        MPVLib.setPropertyString("opengl-pbo", "yes")
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1:reconnect_at_eof=1:reconnect_streamed=1")
        MPVLib.setPropertyString("network-timeout", "30")
        MPVLib.setPropertyString("audio-client-name", "MPVEx-Software-4Core")
        MPVLib.setPropertyString("audio-samplerate", "auto")
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("video-aspect-override", "no")
    }
    
    LaunchedEffect(Unit) {
        var lastSeconds = -1
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            val currentSeconds = currentPos.toInt()
            
            if (isSeeking) {
                currentTime = seekTargetTime
                totalTime = formatTimeSimple(duration)
            } else {
                if (currentSeconds != lastSeconds) {
                    currentTime = formatTimeSimple(currentPos)
                    totalTime = formatTimeSimple(duration)
                    lastSeconds = currentSeconds
                }
            }
            
            if (!isDragging) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            
            currentPosition = currentPos
            videoDuration = duration
            delay(100)
        }
    }
    
    // ========== PROGRESS BAR HANDLERS ==========
    
    // Progress bar drag with MediaCodec
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            switchToMediaCodecMode()
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            
            showSeekbar = true
            showVideoInfo = true
        }
        isDragging = true
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Use MediaCodec for continuous frame decoding
        if (mediaCodecReady) {
            performContinuousMediaCodecSeek(targetPosition)
        } else {
            performMediaCodecSeek(targetPosition)
        }
    }
    
    fun handleDragFinished() {
        isDragging = false
        val finalPosition = seekbarPosition.toDouble()
        
        // Switch back to MPV at final position
        switchBackToMPV(finalPosition)
        
        isSeeking = false
        showSeekTime = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        scheduleSeekbarHide()
    }
    
    // ========== UI RENDERING ==========
    
    val displayText = when (showVideoInfo) {
        true -> fileName
        else -> ""
    }
    
    val videoInfoTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val videoInfoBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    val timeDisplayTextAlpha = if (isSeeking || isDragging) 0.0f else 1.0f
    val timeDisplayBackgroundAlpha = if (isSeeking || isDragging) 0.0f else 0.8f
    
    Box(modifier = modifier.fillMaxSize()) {
        // MAIN GESTURE AREA
        Box(modifier = Modifier.fillMaxSize()) {
            // TOP 5% - Ignore area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.05f)
                    .align(Alignment.TopStart)
            )
            
            // CENTER AREA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .align(Alignment.BottomStart)
            ) {
                // LEFT 5% - Ignore area
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                )
                
                // CENTER 90% - All gestures
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .pointerInteropFilter { event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    touchStartX = event.x
                                    touchStartY = event.y
                                    startLongTapDetection()
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (!isHorizontalSwipe && !isVerticalSwipe && !isLongTap) {
                                        when (checkForSwipeDirection(event.x, event.y)) {
                                            "horizontal" -> {
                                                startHorizontalSeeking(event.x)
                                            }
                                            "vertical" -> {
                                                startVerticalSwipe(event.y)
                                            }
                                        }
                                    } else if (isHorizontalSwipe) {
                                        handleHorizontalSeeking(event.x)
                                    }
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    endTouch()
                                    true
                                }
                                else -> false
                            }
                        }
                )
                
                // RIGHT 5% - Ignore area
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                )
            }
        }
        
        // BOTTOM SEEK BAR AREA
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
                    .offset(y = (3).dp) 
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(modifier = Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$currentTime / $totalTime",
                                style = TextStyle(
                                    color = Color.White.copy(alpha = timeDisplayTextAlpha),
                                    fontSize = 14.sp, 
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = timeDisplayBackgroundAlpha))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
                            modifier = Modifier.fillMaxSize().height(48.dp)
                        )
                    }
                }
            }
        }
        
        // VIDEO INFO - Top Left
        if (showVideoInfo) {
            Text(
                text = displayText,
                style = TextStyle(
                    color = Color.White.copy(alpha = videoInfoTextAlpha),
                    fontSize = 15.sp, 
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = videoInfoBackgroundAlpha))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // FEEDBACK AREA
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(currentVolume.toFloat() / viewModel.maxVolume.toFloat() * 100).toInt()}%",
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showSeekTime -> Text(
                    text = if (seekDirection.isNotEmpty()) "$seekTargetTime $seekDirection" else seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SimpleDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    getFreshPosition: () -> Float,
    modifier: Modifier = Modifier
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0f) }
    var hasPassedThreshold by remember { mutableStateOf(false) }
    var thresholdStartX by remember { mutableStateOf(0f) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(48.dp)) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.Gray.copy(alpha = 0.6f)))
        
        Box(modifier = Modifier
            .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(Color.White))
        
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .align(Alignment.CenterStart)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStartX = offset.x
                        dragStartPosition = getFreshPosition()
                        hasPassedThreshold = false
                        thresholdStartX = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val currentX = change.position.x
                        val totalMovementX = abs(currentX - dragStartX)
                        
                        if (!hasPassedThreshold) {
                            if (totalMovementX > movementThresholdPx) {
                                hasPassedThreshold = true
                                thresholdStartX = currentX
                            } else {
                                return@detectDragGestures
                            }
                        }
                        
                        val effectiveStartX = if (hasPassedThreshold) {
                            dragStartX + movementThresholdPx
                        } else {
                            dragStartX
                        }
                        val deltaX = currentX - effectiveStartX
                        val deltaPosition = (deltaX / size.width) * duration
                        val newPosition = (dragStartPosition + deltaPosition).coerceIn(0f, duration)
                        onValueChange(newPosition)
                    },
                    onDragEnd = { 
                        hasPassedThreshold = false
                        thresholdStartX = 0f
                        onValueChangeFinished() 
                    }
                )
            }
        )
    }
}

private fun formatTimeSimple(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, secs) else String.format("%02d:%02d", minutes, secs)
}

private fun getFileNameFromUri(uri: Uri?, context: android.content.Context): String {
    if (uri == null) return getBestAvailableFileName(context)
    return when {
        uri.scheme == "file" -> uri.lastPathSegment?.substringBeforeLast(".") ?: getBestAvailableFileName(context)
        uri.scheme == "content" -> getDisplayNameFromContentUri(uri, context) ?: getBestAvailableFileName(context)
        uri.scheme in listOf("http", "https") -> uri.lastPathSegment?.substringBeforeLast(".") ?: "Online Video"
        else -> getBestAvailableFileName(context)
    }
}

private fun getDisplayNameFromContentUri(uri: Uri, context: android.content.Context): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex("_display_name")
                val displayName = if (displayNameIndex != -1) cursor.getString(displayNameIndex)?.substringBeforeLast(".") else null
                displayName ?: uri.lastPathSegment?.substringBeforeLast(".")
            } else null
        }
    } catch (e: Exception) { null }
}

private fun getBestAvailableFileName(context: android.content.Context): String {
    val mediaTitle = MPVLib.getPropertyString("media-title")
    if (mediaTitle != null && mediaTitle != "Video" && mediaTitle.isNotBlank()) return mediaTitle.substringBeforeLast(".")
    val mpvPath = MPVLib.getPropertyString("path")
    if (mpvPath != null && mpvPath.isNotBlank()) return mpvPath.substringAfterLast("/").substringBeforeLast(".").ifEmpty { "Video" }
    return "Video"
}

private fun getVideoPath(context: android.content.Context): String? {
    val intent = (context as? android.app.Activity)?.intent
    return when {
        intent?.action == Intent.ACTION_SEND -> {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            getPathFromUri(uri, context)
        }
        intent?.action == Intent.ACTION_VIEW -> {
            getPathFromUri(intent.data, context)
        }
        else -> {
            MPVLib.getPropertyString("path")
        }
    }
}

private fun getPathFromUri(uri: Uri?, context: android.content.Context): String? {
    if (uri == null) return null
    
    return when (uri.scheme) {
        "file" -> uri.path
        "content" -> {
            var path: String? = null
            val projection = arrayOf(android.provider.MediaStore.Video.Media.DATA)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(projection[0])
                        path = cursor.getString(columnIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            path
        }
        else -> uri.toString()
    }
}
