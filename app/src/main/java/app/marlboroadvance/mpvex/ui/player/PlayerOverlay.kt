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

// Frame data classes
data class KeyframeInfo(
    val timestamp: Long, // in microseconds
    val fileOffset: Long,
    val isKeyframe: Boolean = true
)

data class CachedFrame(
    val timestamp: Long,
    val bitmap: Bitmap?,
    val isKeyframe: Boolean
)

data class FrameIndex(
    val keyframes: List<KeyframeInfo>,
    val duration: Long,
    val frameRate: Float,
    val videoWidth: Int,
    val videoHeight: Int
)

// Frame Cache Manager
class FrameCacheManager(
    private val cacheSize: Int = 60, // 30 frames each direction for ±15s window
    private val frameSkip: Int = 2
) {
    private val cache: LruCache<Long, CachedFrame> = LruCache(cacheSize)
    private val cacheTimestamps = sortedSetOf<Long>()
    
    fun getFrame(timestamp: Long): CachedFrame? {
        return cache.get(timestamp)
    }
    
    fun cacheFrame(timestamp: Long, frame: CachedFrame) {
        synchronized(cacheTimestamps) {
            cache.put(timestamp, frame)
            cacheTimestamps.add(timestamp)
            
            // Maintain cache size
            if (cacheTimestamps.size > cacheSize) {
                val oldest = cacheTimestamps.first()
                cacheTimestamps.remove(oldest)
                cache.remove(oldest)
            }
        }
    }
    
    fun isInCache(timestamp: Long): Boolean {
        return cache.get(timestamp) != null
    }
    
    fun getNearestCachedFrame(timestamp: Long): CachedFrame? {
        synchronized(cacheTimestamps) {
            return cacheTimestamps.floor(timestamp)?.let { cache.get(it) }
        }
    }
    
    fun getCacheWindow(centerTime: Long): Pair<Long, Long> {
        val windowSizeMs = 15000L * 1000 // ±15 seconds in microseconds
        return Pair(
            (centerTime - windowSizeMs).coerceAtLeast(0),
            centerTime + windowSizeMs
        )
    }
    
    fun isInCacheWindow(timestamp: Long, centerTime: Long): Boolean {
        val (start, end) = getCacheWindow(centerTime)
        return timestamp in start..end
    }
    
    fun clear() {
        cache.evictAll()
        cacheTimestamps.clear()
    }
    
    fun getCacheStats(): String {
        return "Cache: ${cacheTimestamps.size}/$cacheSize frames"
    }
}

// Frame Index Builder
class FrameIndexBuilder(private val context: android.content.Context) {
    
    suspend fun buildFrameIndex(videoPath: String): FrameIndex? {
        return withContext(Dispatchers.IO) {
            try {
                val extractor = MediaExtractor()
                
                // Set data source
                if (videoPath.startsWith("content://")) {
                    extractor.setDataSource(context, Uri.parse(videoPath), null)
                } else if (videoPath.startsWith("/")) {
                    extractor.setDataSource(videoPath)
                } else {
                    return@withContext null
                }
                
                // Find video track
                val videoTrackIndex = findVideoTrack(extractor)
                if (videoTrackIndex == -1) {
                    extractor.release()
                    return@withContext null
                }
                
                extractor.selectTrack(videoTrackIndex)
                val format = extractor.getTrackFormat(videoTrackIndex)
                
                // Extract keyframe information
                val keyframes = mutableListOf<KeyframeInfo>()
                var sampleTime: Long
                
                while (extractor.advance()) {
                    sampleTime = extractor.sampleTime
                    val flags = extractor.sampleFlags
                    
                    if (flags.toInt() and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        keyframes.add(KeyframeInfo(
                            timestamp = sampleTime,
                            fileOffset = extractor.sampleOffset
                        ))
                    }
                    
                    // Limit to prevent excessive scanning on long videos
                    if (keyframes.size > 1000) break
                }
                
                extractor.release()
                
                FrameIndex(
                    keyframes = keyframes,
                    duration = format.getLong(MediaFormat.KEY_DURATION),
                    frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE),
                    videoWidth = format.getInteger(MediaFormat.KEY_WIDTH),
                    videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        return -1
    }
}

// Scrubbing Controller
class ScrubbingController(
    private val context: android.content.Context,
    private val cacheManager: FrameCacheManager = FrameCacheManager(),
    private val frameIndexBuilder: FrameIndexBuilder = FrameIndexBuilder(context)
) {
    private var frameIndex: FrameIndex? = null
    private var currentDecodeJob: Job? = null
    private var currentVideoPath: String? = null
    private var cacheCenterTime: Long = 0L
    
    suspend fun initialize(videoPath: String) {
        currentVideoPath = videoPath
        frameIndex = frameIndexBuilder.buildFrameIndex(videoPath)
        cacheManager.clear()
    }
    
    fun onScrubStart(currentTime: Long) {
        currentDecodeJob?.cancel()
        cacheCenterTime = currentTime
        preloadCacheAround(currentTime)
    }
    
    fun onScrubProgress(targetTime: Long, coroutineScope: CoroutineScope): CachedFrame? {
        cacheCenterTime = targetTime
        
        // Try to get frame from cache first
        val cachedFrame = cacheManager.getNearestCachedFrame(targetTime)
        if (cachedFrame != null && cacheManager.isInCacheWindow(targetTime, cacheCenterTime)) {
            return cachedFrame
        }
        
        // Start decoding if frame not in cache
        if (currentDecodeJob?.isActive != true) {
            currentDecodeJob?.cancel()
            currentDecodeJob = coroutineScope.launch {
                decodeToTimestamp(targetTime)
            }
        }
        
        return cachedFrame // Return nearest frame even if not exact
    }
    
    fun onScrubEnd() {
        currentDecodeJob?.cancel()
        cacheManager.clear() // Clear cache to save memory when not scrubbing
    }
    
    private fun preloadCacheAround(centerTime: Long) {
        // Implementation would decode frames around the current time
        // This is called when scrubbing starts to pre-populate cache
    }
    
    private suspend fun decodeToTimestamp(targetTime: Long) {
        val index = frameIndex ?: return
        val videoPath = currentVideoPath ?: return
        
        // Find nearest keyframe before target time
        val startKeyframe = findNearestKeyframeBefore(targetTime, index.keyframes)
        if (startKeyframe == null) {
            // Fallback to traditional seeking
            performTraditionalSeek(targetTime)
            return
        }
        
        // Implement forward-only decoding from startKeyframe to targetTime
        // This would use MediaCodec to decode frames forward
        decodeForwardFromKeyframe(startKeyframe, targetTime, index)
    }
    
    private fun findNearestKeyframeBefore(targetTime: Long, keyframes: List<KeyframeInfo>): KeyframeInfo? {
        return keyframes.lastOrNull { it.timestamp <= targetTime }
    }
    
    private suspend fun decodeForwardFromKeyframe(
        startKeyframe: KeyframeInfo,
        targetTime: Long,
        index: FrameIndex
    ) {
        // This is a simplified implementation
        // In a full implementation, you would:
        // 1. Seek MediaExtractor to startKeyframe.fileOffset
        // 2. Use MediaCodec to decode frames forward
        // 3. Cache every frameSkip-th frame
        // 4. Stop when reaching targetTime + cache window
        
        withContext(Dispatchers.IO) {
            try {
                // Placeholder for actual MediaCodec decoding
                // For now, we'll simulate with traditional seeking
                performTraditionalSeek(targetTime)
                
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to traditional seeking
                performTraditionalSeek(targetTime)
            }
        }
    }
    
    private fun performTraditionalSeek(targetTime: Long) {
        // Fallback to MPV's built-in seeking
        val targetSeconds = targetTime / 1_000_000.0 // Convert microseconds to seconds
        MPVLib.command("seek", targetSeconds.toString(), "absolute", "exact")
    }
    
    fun getCacheStats(): String {
        return cacheManager.getCacheStats()
    }
    
    fun cleanup() {
        currentDecodeJob?.cancel()
        cacheManager.clear()
    }
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
    
    // Scrubbing system states
    var currentScrubbingFrame by remember { mutableStateOf<CachedFrame?>(null) }
    var showScrubbingPreview by remember { mutableStateOf(false) }
    var scrubbingCacheStats by remember { mutableStateOf("") }
    
    var seekDirection by remember { mutableStateOf("") }
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 0L
    
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
    val horizontalSwipeThreshold = 30f
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
    
    // Initialize scrubbing controller
    val scrubbingController = remember {
        ScrubbingController(context)
    }
    
    // Initialize scrubbing system when video loads
    LaunchedEffect(fileName) {
        val videoPath = getCurrentVideoPath(context)
        if (videoPath.isNotEmpty()) {
            scrubbingController.initialize(videoPath)
        }
    }
    
    // Cleanup scrubbing controller
    LaunchedEffect(Unit) {
        try {
            awaitDispose {
                scrubbingController.cleanup()
            }
        } catch (e: Exception) {
            // Handle cleanup error
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
            showScrubbingPreview = false
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
            showScrubbingPreview = false
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
        showScrubbingPreview = true
        
        // Initialize scrubbing
        val currentTimeMicros = (seekStartPosition * 1_000_000).toLong()
        scrubbingController.onScrubStart(currentTimeMicros)
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
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
        
        // Use scrubbing system
        val targetTimeMicros = (clampedPosition * 1_000_000).toLong()
        currentScrubbingFrame = scrubbingController.onScrubProgress(targetTimeMicros, coroutineScope)
        
        // Update cache stats
        scrubbingCacheStats = scrubbingController.getCacheStats()
        
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        // Only use traditional seeking as fallback
        if (currentScrubbingFrame == null) {
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
            
            // Cleanup scrubbing
            scrubbingController.onScrubEnd()
            
            isSeeking = false
            showSeekTime = false
            showScrubbingPreview = false
            seekStartX = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            seekDirection = ""
            currentScrubbingFrame = null
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
            showScrubbingPreview = true
            
            // Initialize scrubbing
            val currentTimeMicros = (newPosition.toDouble() * 1_000_000).toLong()
            scrubbingController.onScrubStart(currentTimeMicros)
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        
        // Use scrubbing system
        val targetTimeMicros = (targetPosition * 1_000_000).toLong()
        currentScrubbingFrame = scrubbingController.onScrubProgress(targetTimeMicros, coroutineScope)
        
        // Update cache stats
        scrubbingCacheStats = scrubbingController.getCacheStats()
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Fallback to traditional seeking
        if (currentScrubbingFrame == null) {
            performRealTimeSeek(targetPosition)
        }
    }
    
    fun handleDragFinished() {
        isDragging = false
        if (wasPlayingBeforeSeek) {
            coroutineScope.launch {
                delay(100)
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
        
        // Cleanup scrubbing
        scrubbingController.onScrubEnd()
        
        isSeeking = false
        showSeekTime = false
        showScrubbingPreview = false
        wasPlayingBeforeSeek = false
        seekDirection = ""
        currentScrubbingFrame = null
        scheduleSeekbarHide()
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // Scrubbing Preview Overlay
        if (showScrubbingPreview && currentScrubbingFrame?.bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            ) {
                Image(
                    bitmap = currentScrubbingFrame.bitmap.asImageBitmap(),
                    contentDescription = "Scrubbing preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center)
                )
                
                // Scrubbing info overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 120.dp)
                        .background(Color.DarkGray.copy(alpha = 0.8f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = "Scrubbing: $seekTargetTime",
                            style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = scrubbingCacheStats,
                            style = TextStyle(color = Color.White, fontSize = 12.sp)
                        )
                    }
                }
            }
        }
        
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
