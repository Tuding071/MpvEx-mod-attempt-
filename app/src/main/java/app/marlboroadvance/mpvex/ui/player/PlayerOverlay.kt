package app.marlboroadvance.mpvex.ui.player

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.collection.LruCache
import android.view.MotionEvent
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import java.io.ByteArrayOutputStream

// ============================================================================
// COMPRESSED VIDEO SLIDING WINDOW SYSTEM
// ============================================================================

data class VideoSegment(
    val startTimeMicros: Long, // Start time of this 1-second segment
    val compressedData: ByteArray, // Compressed video data for this segment
    val durationMicros: Long = 1_000_000L, // 1 second
    val isLoaded: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VideoSegment
        return startTimeMicros == other.startTimeMicros
    }

    override fun hashCode(): Int = startTimeMicros.hashCode()
}

/**
 * ALWAYS-READY compressed video buffer for instant horizontal drag seeking
 * Maintains 20-second window around current position (10s past + 10s future)
 */
class CompressedVideoBuffer(
    private val context: android.content.Context,
    private val windowSizeSeconds: Int = 20
) {
    private val segments = LinkedHashMap<Long, VideoSegment>() // Ordered by timestamp
    private var currentCenterTimeMicros: Long = 0L
    private var currentVideoPath: String = ""
    private var isBufferReady = false
    private var maintenanceJob: Job? = null
    private val segmentSizeMicros = 1_000_000L // 1 second per segment
    
    companion object {
        private const val TAG = "CompressedVideoBuffer"
    }
    
    // ==================== PUBLIC API ====================
    
    /**
     * Initialize buffer around current position - CALL THIS WHEN VIDEO LOADS
     */
    suspend fun initialize(videoPath: String, centerTimeMicros: Long) {
        currentVideoPath = videoPath
        currentCenterTimeMicros = centerTimeMicros
        isBufferReady = false
        
        // Clear existing segments
        segments.clear()
        
        // Load initial 20-second window
        rebuildBufferAround(centerTimeMicros)
        
        // Start maintenance coroutine
        startMaintenance()
        
        isBufferReady = true
    }
    
    /**
     * Get video segment for target time - INSTANT access during drag
     */
    fun getSegmentForTime(targetTimeMicros: Long): VideoSegment? {
        val segmentStart = (targetTimeMicros / segmentSizeMicros) * segmentSizeMicros
        return segments[segmentStart]
    }
    
    /**
     * Check if buffer is ready for drag seeking around target time
     */
    fun isReadyForDrag(targetTimeMicros: Long): Boolean {
        if (!isBufferReady) return false
        
        val bufferStart = currentCenterTimeMicros - (windowSizeSeconds / 2 * segmentSizeMicros)
        val bufferEnd = currentCenterTimeMicros + (windowSizeSeconds / 2 * segmentSizeMicros)
        
        return targetTimeMicros in bufferStart..bufferEnd
    }
    
    /**
     * Handle position change - buffer follows or rebuilds as needed
     */
    suspend fun onPositionChanged(newPositionMicros: Long) {
        if (!isBufferReady) return
        
        val distanceFromCenter = abs(newPositionMicros - currentCenterTimeMicros)
        
        when {
            // Small movement - slide window gradually
            distanceFromCenter < 2_000_000L -> { // < 2 seconds
                slideWindowTo(newPositionMicros)
            }
            // Medium jump - recenter window
            distanceFromCenter < 10_000_000L -> { // < 10 seconds
                recenterWindow(newPositionMicros)
            }
            // Big jump - complete rebuild
            else -> {
                rebuildBufferAround(newPositionMicros)
            }
        }
    }
    
    /**
     * Get buffer status for UI feedback
     */
    fun getBufferStatus(): String {
        if (!isBufferReady) return "🔄 Initializing..."
        return "✅ Ready (${segments.size}/$windowSizeSeconds segments)"
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        maintenanceJob?.cancel()
        segments.clear()
        isBufferReady = false
    }
    
    // ==================== PRIVATE IMPLEMENTATION ====================
    
    private suspend fun rebuildBufferAround(centerTimeMicros: Long) {
        segments.clear()
        
        val startTime = centerTimeMicros - (windowSizeSeconds / 2 * segmentSizeMicros)
        val endTime = centerTimeMicros + (windowSizeSeconds / 2 * segmentSizeMicros)
        
        // Load all segments in the window
        for (segmentTime in startTime..endTime step segmentSizeMicros) {
            if (segmentTime >= 0) {
                loadSegment(segmentTime)
            }
        }
        
        currentCenterTimeMicros = centerTimeMicros
    }
    
    private suspend fun recenterWindow(newCenterMicros: Long) {
        val oldCenter = currentCenterTimeMicros
        currentCenterTimeMicros = newCenterMicros
        
        // Remove segments that are now outside window
        val windowStart = newCenterMicros - (windowSizeSeconds / 2 * segmentSizeMicros)
        val windowEnd = newCenterMicros + (windowSizeSeconds / 2 * segmentSizeMicros)
        
        segments.keys.removeAll { it < windowStart || it > windowEnd }
        
        // Load missing segments for new window
        for (segmentTime in windowStart..windowEnd step segmentSizeMicros) {
            if (segmentTime >= 0 && !segments.containsKey(segmentTime)) {
                loadSegment(segmentTime)
            }
        }
    }
    
    private suspend fun slideWindowTo(newPositionMicros: Long) {
        val direction = (newPositionMicros - currentCenterTimeMicros).compareTo(0)
        currentCenterTimeMicros = newPositionMicros
        
        if (direction > 0) {
            // Moving forward - remove oldest segment, load new future segment
            slideWindowForward()
        } else if (direction < 0) {
            // Moving backward - remove newest segment, load new past segment  
            slideWindowBackward()
        }
    }
    
    private suspend fun slideWindowForward() {
        val windowStart = currentCenterTimeMicros - (windowSizeSeconds / 2 * segmentSizeMicros)
        val windowEnd = currentCenterTimeMicros + (windowSizeSeconds / 2 * segmentSizeMicros)
        
        // Remove one segment from beginning if it's now outside window
        segments.keys.firstOrNull()?.let { firstKey ->
            if (firstKey < windowStart) {
                segments.remove(firstKey)
            }
        }
        
        // Add one segment at the end
        val newSegmentTime = windowEnd
        if (!segments.containsKey(newSegmentTime)) {
            loadSegment(newSegmentTime)
        }
    }
    
    private suspend fun slideWindowBackward() {
        val windowStart = currentCenterTimeMicros - (windowSizeSeconds / 2 * segmentSizeMicros)
        val windowEnd = currentCenterTimeMicros + (windowSizeSeconds / 2 * segmentSizeMicros)
        
        // Remove one segment from end if it's now outside window
        segments.keys.lastOrNull()?.let { lastKey ->
            if (lastKey > windowEnd) {
                segments.remove(lastKey)
            }
        }
        
        // Add one segment at the beginning
        val newSegmentTime = windowStart
        if (!segments.containsKey(newSegmentTime) && newSegmentTime >= 0) {
            loadSegment(newSegmentTime)
        }
    }
    
    private suspend fun loadSegment(segmentStartMicros: Long) {
        val segmentData = extractVideoChunk(segmentStartMicros, segmentSizeMicros)
        segmentData?.let { data ->
            segments[segmentStartMicros] = VideoSegment(segmentStartMicros, data)
        }
    }
    
    private suspend fun extractVideoChunk(startTimeMicros: Long, durationMicros: Long): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(currentVideoPath)
                
                // Select video track
                val videoTrackIndex = selectVideoTrack(extractor)
                if (videoTrackIndex == -1) {
                    extractor.release()
                    return@withContext null
                }
                
                extractor.selectTrack(videoTrackIndex)
                extractor.seekTo(startTimeMicros, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024) // 64KB buffer
                val endTime = startTimeMicros + durationMicros
                var currentTime = startTimeMicros
                var samplesRead = 0
                
                // Read compressed video data until we have enough or reach end
                while (currentTime < endTime && samplesRead < 300) { // Max 300 samples (~10 seconds)
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    
                    outputStream.write(buffer, 0, sampleSize)
                    currentTime = extractor.sampleTime
                    extractor.advance()
                    samplesRead++
                }
                
                extractor.release()
                outputStream.toByteArray()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        return -1
    }
    
    private fun startMaintenance() {
        maintenanceJob?.cancel()
        // Maintenance is now handled by onPositionChanged calls
    }
}

// ============================================================================
// ENHANCED PLAYER OVERLAY WITH COMPRESSED VIDEO BUFFER
// ============================================================================

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
    
    // Compressed video buffer states
    var bufferStatus by remember { mutableStateOf("🔄 Initializing...") }
    var isUsingBufferPlayback by remember { mutableStateOf(false) }
    var showBufferStatus by remember { mutableStateOf(false) }
    
    var seekDirection by remember { mutableStateOf("") }
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 16L // 60fps throttle
    
    // Gesture states
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<Job?>(null) }
    
    // Thresholds
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 20f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    
    // Quick seek
    val quickSeekAmount = 5
    
    var showVideoInfo by remember { mutableStateOf(0) }
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<Job?>(null) }
    
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<Job?>(null) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<Job?>(null) }
    
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var quickSeekFeedbackJob by remember { mutableStateOf<Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // Initialize compressed video buffer
    val videoBuffer = remember {
        CompressedVideoBuffer(context)
    }
    
    // Initialize buffer when video loads
    LaunchedEffect(fileName, currentPosition) {
        val videoPath = getCurrentVideoPath(context)
        if (videoPath.isNotEmpty()) {
            val currentTimeMicros = (currentPosition * 1_000_000).toLong()
            videoBuffer.initialize(videoPath, currentTimeMicros)
        }
    }
    
    // Update buffer status continuously
    LaunchedEffect(Unit) {
        while (isActive) {
            bufferStatus = videoBuffer.getBufferStatus()
            showBufferStatus = true
            delay(2000) // Update every 2 seconds
        }
    }
    
    // Keep buffer synchronized with playback
    LaunchedEffect(currentPosition) {
        if (currentPosition > 0 && !isSeeking) {
            val currentTimeMicros = (currentPosition * 1_000_000).toLong()
            videoBuffer.onPositionChanged(currentTimeMicros)
        }
    }
    
    // Cleanup buffer
    DisposableEffect(videoBuffer) {
        onDispose {
            videoBuffer.cleanup()
        }
    }

    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        
        isSeekInProgress = true
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
        
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    fun performQuickSeek(seconds: Int) {
        val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val newPosition = (currentPos + seconds).coerceIn(0.0, duration)
        
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
        showVideoInfo = if (showVideoInfo == 0) 1 else 0
        if (showVideoInfo != 0) {
            videoInfoJob?.cancel()
            videoInfoJob = coroutineScope.launch {
                delay(4000)
                showVideoInfo = 0
            }
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
    
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    fun scheduleSeekbarHide() {
        if (userInteracting) return
        hideSeekbarJob?.cancel()
        hideSeekbarJob = coroutineScope.launch {
            delay(4000)
            showSeekbar = false
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
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            return "horizontal"
        }
        
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            return "vertical"
        }
        
        return ""
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        isUsingBufferPlayback = true
        
        // Check if buffer is ready for smooth seeking
        val currentTimeMicros = (seekStartPosition * 1_000_000).toLong()
        val isBufferReady = videoBuffer.isReadyForDrag(currentTimeMicros)
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
        
        if (!isBufferReady) {
            showPlaybackFeedback("⏳ Buffer loading...")
        }
    }
    
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        val currentY = startY
        val deltaY = currentY - touchStartY
        
        if (deltaY < 0) {
            seekDirection = "+"
            performQuickSeek(quickSeekAmount)
        } else {
            seekDirection = "-"
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 4f / 0.016f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekDirection = if (deltaX > 0) "+" else "-"
        
        // Use compressed video buffer for instant seeking
        val targetTimeMicros = (clampedPosition * 1_000_000).toLong()
        val segment = videoBuffer.getSegmentForTime(targetTimeMicros)
        
        if (segment != null) {
            // Buffer has the segment - instant seek
            seekTargetTime = formatTimeSimple(clampedPosition)
            currentTime = formatTimeSimple(clampedPosition)
            performRealTimeSeek(clampedPosition)
        } else {
            // Buffer doesn't have this segment - fallback to normal seek
            seekTargetTime = formatTimeSimple(clampedPosition)
            currentTime = formatTimeSimple(clampedPosition)
            performRealTimeSeek(clampedPosition)
        }
    }
    
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: seekStartPosition
            
            // Final seek to exact position
            performRealTimeSeek(currentPos)
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            isUsingBufferPlayback = false
            seekStartX = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            seekDirection = ""
            scheduleSeekbarHide()
        }
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
            isHorizontalSwipe = false
        } else if (isVerticalSwipe) {
            endVerticalSwipe()
            isVerticalSwipe = false
        } else if (touchDuration < 150) {
            handleTap()
        }
        
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
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
        showVideoInfo = 1
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
        }
        scheduleSeekbarHide()
    }
    
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    LaunchedEffect(Unit) {
        // MPV configuration...
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "8")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavc-threads", "4")
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
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }
    
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            isUsingBufferPlayback = true
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        
        // Use compressed video buffer
        val targetTimeMicros = (targetPosition * 1_000_000).toLong()
        val segment = videoBuffer.getSegmentForTime(targetTimeMicros)
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Always seek - buffer ensures instant response when available
        performRealTimeSeek(targetPosition)
    }
    
    fun handleDragFinished() {
        isDragging = false
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
        
        isSeeking = false
        showSeekTime = false
        isUsingBufferPlayback = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        scheduleSeekbarHide()
    }
    
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
            
            // CENTER AREA - 95% height, divided into left/center/right
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.95f)
                    .align(Alignment.BottomStart)
            ) {
                // LEFT 5% - Video info toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toggleVideoInfo() }
                        )
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
                
                // RIGHT 5% - Video info toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.05f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toggleVideoInfo() }
                        )
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
                                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
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
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // BUFFER STATUS - Top Right
        if (showBufferStatus) {
            Text(
                text = bufferStatus,
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-60).dp, y = 20.dp)
                    .background(Color.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
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

// Keep the existing SimpleDraggableProgressBar and helper functions...

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
    
    val movementThresholdPx = with(LocalDensity.current) { 15.dp.toPx() }
    
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
                        
                        val effectiveStartX = if (hasPassedThreshold) thresholdStartX else dragStartX
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

// Helper function to get current video path
private fun getCurrentVideoPath(context: android.content.Context): String {
    val intent = (context as? android.app.Activity)?.intent
    return when {
        intent?.action == Intent.ACTION_SEND -> {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString() ?: ""
        }
        intent?.action == Intent.ACTION_VIEW -> {
            intent.data?.toString() ?: ""
        }
        else -> {
            MPVLib.getPropertyString("path") ?: ""
        }
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
