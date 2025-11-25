package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import androidx.compose.ui.graphics.Color as ComposeColor
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect

// ===== FFMPEG INTEGRATION =====

// FFmpeg Binary Manager
class FFmpegBinaryManager(private val context: Context) {
    companion object {
        private const val FFMPEG_ASSET_PATH = "ffmpeg"
        private const val FFMPEG_CACHE_NAME = "ffmpeg_executable"
    }
    
    suspend fun ensureFFmpegAvailable(): File {
        return withContext(Dispatchers.IO) {
            val ffmpegFile = File(context.cacheDir, FFMPEG_CACHE_NAME)
            
            if (!ffmpegFile.exists() || ffmpegFile.length() == 0L) {
                try {
                    context.assets.open(FFMPEG_ASSET_PATH).use { input ->
                        ffmpegFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    ffmpegFile.setExecutable(true)
                } catch (e: Exception) {
                    throw IOException("Failed to extract FFmpeg from assets", e)
                }
            }
            
            if (!ffmpegFile.canExecute()) {
                ffmpegFile.setExecutable(true)
            }
            
            ffmpegFile
        }
    }
    
    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            val ffmpegFile = File(context.cacheDir, FFMPEG_CACHE_NAME)
            if (ffmpegFile.exists()) {
                ffmpegFile.delete()
            }
        }
    }
}

// FFmpeg Command Builder
object FFmpegCommands {
    fun extractSingleFrame(
        inputPath: String,
        timestampMs: Long,
        width: Int,
        height: Int
    ): Array<String> {
        return arrayOf(
            "-i", inputPath,
            "-ss", "${timestampMs / 1000.0}",
            "-vf", "scale=$width:$height",
            "-vframes", "1",
            "-f", "image2pipe",
            "-c:v", "mjpeg",
            "-"
        )
    }
}

// FFmpeg Frame Decoder
class FFmpegFrameDecoder(private val context: Context) {
    private val binaryManager = FFmpegBinaryManager(context)
    private var ffmpegBinary: File? = null
    private var currentVideoPath: String = ""
    
    suspend fun initialize(videoPath: String) {
        ffmpegBinary = binaryManager.ensureFFmpegAvailable()
        currentVideoPath = videoPath
    }
    
    suspend fun decodeFrameAtTime(timestamp: Double, targetHeight: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            if (ffmpegBinary == null || currentVideoPath.isEmpty()) {
                return@withContext createPlaceholderBitmap(timestamp, targetHeight)
            }
            
            try {
                val command = FFmpegCommands.extractSingleFrame(
                    currentVideoPath,
                    (timestamp * 1000).toLong(),
                    calculateWidth(targetHeight),
                    targetHeight
                )
                
                val process = ProcessBuilder(listOf(ffmpegBinary!!.absolutePath) + command.toList()).start()
                val inputStream = process.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                process.waitFor()
                
                bitmap ?: createPlaceholderBitmap(timestamp, targetHeight)
            } catch (e: Exception) {
                createPlaceholderBitmap(timestamp, targetHeight)
            }
        }
    }
    
    private fun calculateWidth(targetHeight: Int): Int {
        // Standard 16:9 aspect ratio for 480p
        return when (targetHeight) {
            480 -> 854
            96 -> 171
            else -> (targetHeight * 16 / 9)
        }
    }
    
    private fun createPlaceholderBitmap(timestamp: Double, targetHeight: Int): Bitmap {
        val width = calculateWidth(targetHeight)
        return Bitmap.createBitmap(width, targetHeight, Bitmap.Config.ARGB_8888).apply {
            val colorValue = when ((timestamp.toInt() % 5)) {
                0 -> Color.RED
                1 -> Color.BLUE
                2 -> Color.GREEN
                3 -> Color.YELLOW
                else -> Color.MAGENTA
            }
            eraseColor(colorValue)
        }
    }
    
    suspend fun cleanup() {
        binaryManager.cleanup()
    }
}

// ===== PREVIEW MANAGER WITH FFMPEG =====

// Data classes for preview system
data class ScrubbingFrame(
    val timestamp: Double,
    val bitmap: Bitmap
)

data class TimelineThumbnail(
    val secondIndex: Int,
    val bitmap: Bitmap
)

class PreviewManager(private val context: Context) {
    // Scrubbing Window (480p @ 12fps)
    private val scrubbingWindow = mutableListOf<ScrubbingFrame>()
    private val scrubbingMutex = Mutex()
    private var scrubbingDecoderJob: kotlinx.coroutines.Job? = null
    private var currentWindowCenter = 0.0
    
    // Timeline Thumbnails (96p @ 1fps)  
    private val timelineThumbnails = ConcurrentHashMap<Int, TimelineThumbnail>()
    private val thumbnailMutex = Mutex()
    private var thumbnailDecoderJob: kotlinx.coroutines.Job? = null
    
    // FFmpeg Decoder
    private val frameDecoder = FFmpegFrameDecoder(context)
    private var currentVideoPath: String = ""
    
    // Configuration
    private val scrubbingWindowSize = 20.0 // seconds total
    private val scrubbingFPS = 12
    private val thumbnailFPS = 1
    
    companion object {
        @Volatile
        private var INSTANCE: PreviewManager? = null
        
        fun getInstance(context: Context): PreviewManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreviewManager(context).also { INSTANCE = it }
            }
        }
    }
    
    // Initialize with video path
    suspend fun initialize(videoPath: String) {
        currentVideoPath = videoPath
        frameDecoder.initialize(videoPath)
    }
    
    // Scrubbing Window Methods
    suspend fun getScrubbingFrame(timestamp: Double): Bitmap? {
        return scrubbingMutex.withLock {
            scrubbingWindow.minByOrNull { abs(it.timestamp - timestamp) }?.bitmap
        }
    }
    
    fun startScrubbingWindowGeneration(centerTime: Double, duration: Double) {
        scrubbingDecoderJob?.cancel()
        scrubbingDecoderJob = CoroutineScope(Dispatchers.IO).launch {
            generateScrubbingWindow(centerTime, duration)
        }
    }
    
    private suspend fun generateScrubbingWindow(centerTime: Double, duration: Double) {
        scrubbingMutex.withLock {
            scrubbingWindow.clear()
            currentWindowCenter = centerTime
        }
        
        val windowStart = (centerTime - scrubbingWindowSize / 2).coerceAtLeast(0.0)
        val windowEnd = (centerTime + scrubbingWindowSize / 2).coerceAtMost(duration)
        
        var currentTime = windowStart
        val timeStep = 1.0 / scrubbingFPS
        
        while (currentTime <= windowEnd && scrubbingDecoderJob?.isActive == true) {
            val frame = frameDecoder.decodeFrameAtTime(currentTime, 480)
            frame?.let {
                scrubbingMutex.withLock {
                    scrubbingWindow.add(ScrubbingFrame(currentTime, it))
                    // Keep only recent frames if window moves
                    if (scrubbingWindow.size > (scrubbingWindowSize * scrubbingFPS).toInt()) {
                        scrubbingWindow.removeAll { frame ->
                            abs(frame.timestamp - currentWindowCenter) > scrubbingWindowSize / 2
                        }
                    }
                }
            }
            currentTime += timeStep
            delay(16) // ~60fps decoding rate
        }
    }
    
    // Timeline Thumbnail Methods
    suspend fun getTimelineThumbnail(second: Int): Bitmap? {
        return timelineThumbnails[second]?.bitmap
    }
    
    fun startTimelineThumbnailGeneration(duration: Double) {
        thumbnailDecoderJob?.cancel()
        thumbnailDecoderJob = CoroutineScope(Dispatchers.IO).launch {
            generateTimelineThumbnails(duration.toInt())
        }
    }
    
    private suspend fun generateTimelineThumbnails(durationSeconds: Int) {
        for (second in 0..durationSeconds) {
            if (thumbnailDecoderJob?.isActive != true) break
            
            // Skip if already generated
            if (timelineThumbnails.containsKey(second)) continue
            
            val thumbnail = frameDecoder.decodeFrameAtTime(second.toDouble(), 96)
            thumbnail?.let {
                timelineThumbnails[second] = TimelineThumbnail(second, it)
            }
            
            // Low priority - delay between generations
            delay(100)
        }
    }
    
    fun clearScrubbingWindow() {
        scrubbingDecoderJob?.cancel()
        scrubbingWindow.clear()
    }
    
    fun cleanup() {
        scrubbingDecoderJob?.cancel()
        thumbnailDecoderJob?.cancel()
        scrubbingWindow.clear()
        timelineThumbnails.clear()
        CoroutineScope(Dispatchers.IO).launch {
            frameDecoder.cleanup()
        }
    }
}

// ===== MAIN PLAYER OVERLAY =====

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewManager = remember { PreviewManager.getInstance(context) }
    
    // Existing states
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
    
    // NEW: Scrubbing states
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubbingPreviewTime by remember { mutableStateOf(0.0) }
    var currentPreviewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showScrubbingPreview by remember { mutableStateOf(false) }
    
    // NEW: Thumbnail states
    var showThumbnailPreview by remember { mutableStateOf(false) }
    var thumbnailPreviewTime by remember { mutableStateOf(0) }
    var currentThumbnailBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    var isSeeking by remember { mutableStateOf(false) }
    var seekStartX by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
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
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Thresholds
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 40f
    val maxVerticalMovement = 50f
    val maxHorizontalMovement = 50f
    val quickSeekAmount = 5
    
    var showVideoInfo by remember { mutableStateOf(0) }
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
    
    var currentVideoPath by remember { mutableStateOf("") }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }

    // ===== INITIALIZE FFMPEG WITH VIDEO PATH =====
    LaunchedEffect(currentVideoPath) {
        if (currentVideoPath.isNotEmpty()) {
            previewManager.initialize(currentVideoPath)
        }
    }

    // NEW: Initialize preview system when we have video duration and path
    LaunchedEffect(videoDuration, currentVideoPath) {
        if (videoDuration > 1.0 && currentVideoPath.isNotEmpty()) {
            previewManager.startTimelineThumbnailGeneration(videoDuration)
            previewManager.startScrubbingWindowGeneration(currentPosition, videoDuration)
        }
    }

    // ===== HELPER FUNCTIONS =====
    
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

    // ===== PREVIEW AND SCRUBBING FUNCTIONS =====

    fun startScrubbing(startX: Float) {
        isScrubbing = true
        showScrubbingPreview = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = currentPosition
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun handleScrubbing(currentX: Float) {
        if (!isScrubbing) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 4f / 0.016f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val clampedPosition = newPositionSeconds.coerceIn(0.0, videoDuration)
        
        scrubbingPreviewTime = clampedPosition
        seekTargetTime = formatTimeSimple(clampedPosition)
        seekDirection = if (deltaX > 0) "+" else "-"
        
        // Get preview frame from scrubbing window
        coroutineScope.launch {
            val frame = previewManager.getScrubbingFrame(clampedPosition)
            frame?.let {
                currentPreviewBitmap = it.asImageBitmap()
            }
        }
    }
    
    fun endScrubbing() {
        if (isScrubbing) {
            // Perform actual seek to final position
            MPVLib.command("seek", scrubbingPreviewTime.toString(), "absolute", "exact")
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            // Reset scrubbing states
            isScrubbing = false
            showScrubbingPreview = false
            currentPreviewBitmap = null
            
            // Regenerate scrubbing window around new position
            previewManager.clearScrubbingWindow()
            previewManager.startScrubbingWindowGeneration(scrubbingPreviewTime, videoDuration)
            
            scheduleSeekbarHide()
        }
    }
    
    // Thumbnail preview for seekbar
    fun showThumbnailPreview(timeSeconds: Int) {
        thumbnailPreviewTime = timeSeconds
        showThumbnailPreview = true
        
        coroutineScope.launch {
            val thumbnail = previewManager.getTimelineThumbnail(timeSeconds)
            thumbnail?.let {
                currentThumbnailBitmap = it.asImageBitmap()
            }
        }
    }
    
    fun hideThumbnailPreview() {
        showThumbnailPreview = false
        currentThumbnailBitmap = null
    }
    
    // Progress bar drag with thumbnail preview
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        seekbarPosition = newPosition
        
        val targetPosition = newPosition.toDouble()
        seekTargetTime = formatTimeSimple(targetPosition)
        seekDirection = if (newPosition > currentPosition.toFloat()) "+" else "-"
        
        // Show thumbnail preview
        val timeSeconds = (targetPosition).toInt()
        showThumbnailPreview(timeSeconds)
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
        wasPlayingBeforeSeek = false
        seekDirection = ""
        hideThumbnailPreview()
        scheduleSeekbarHide()
        
        // Perform final seek
        MPVLib.command("seek", seekbarPosition.toString(), "absolute", "exact")
    }
    
    // ===== GESTURE HANDLING FUNCTIONS =====

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
        startScrubbing(startX)
        isHorizontalSwipe = true
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        handleScrubbing(currentX)
    }
    
    fun endHorizontalSeeking() {
        endScrubbing()
        isHorizontalSwipe = false
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

    // ===== EFFECTS AND LIFECYCLE =====

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
        
        // Get current video path for FFmpeg
        currentVideoPath = MPVLib.getPropertyString("path") ?: ""
        scheduleSeekbarHide()
    }
    
    LaunchedEffect(viewModel.currentVolume) {
        viewModel.currentVolume.collect { volume ->
            currentVolume = volume
            showVolumeFeedback(volume)
        }
    }
    
    LaunchedEffect(Unit) {
        var lastSeconds = -1
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            val currentSeconds = currentPos.toInt()
            if (isSeeking || isScrubbing) {
                currentTime = seekTargetTime
                totalTime = formatTimeSimple(duration)
            } else {
                if (currentSeconds != lastSeconds) {
                    currentTime = formatTimeSimple(currentPos)
                    totalTime = formatTimeSimple(duration)
                    lastSeconds = currentSeconds
                }
            }
            if (!isDragging && !isScrubbing) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            currentPosition = currentPos
            videoDuration = duration
            delay(100)
        }
    }
    
    // Cleanup FFmpeg when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            previewManager.cleanup()
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }

    // ===== UI LAYOUT =====
    
    Box(modifier = modifier.fillMaxSize()) {
        // MAIN GESTURE AREA - Full screen divided into areas
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
                
                // CENTER 90% - All gestures (tap, long tap, horizontal swipe, vertical swipe)
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
                                style = TextStyle(color = ComposeColor.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.background(ComposeColor.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        EnhancedDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat() },
                            modifier = Modifier.fillMaxSize().height(48.dp),
                            showThumbnailPreview = { time, _ -> showThumbnailPreview(time) },
                            hideThumbnailPreview = { hideThumbnailPreview() }
                        )
                    }
                }
            }
        }
        
        // SCRUBBING PREVIEW OVERLAY
        if (showScrubbingPreview && currentPreviewBitmap != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(320.dp, 180.dp) // 480p aspect ratio
                    .background(ComposeColor.Black.copy(alpha = 0.9f))
            ) {
                Image(
                    bitmap = currentPreviewBitmap!!,
                    contentDescription = "Scrubbing preview",
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = seekTargetTime,
                    style = TextStyle(color = ComposeColor.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(ComposeColor.Black.copy(alpha = 0.7f))
                        .padding(8.dp)
                )
            }
        }
        
        // THUMBNAIL PREVIEW (Above time on bottom left)
        if (showThumbnailPreview && currentThumbnailBitmap != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 60.dp, y = (-100).dp)
                    .size(120.dp, 68.dp)
                    .background(ComposeColor.Black.copy(alpha = 0.9f))
            ) {
                Image(
                    bitmap = currentThumbnailBitmap!!,
                    contentDescription = "Thumbnail preview",
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    text = formatTimeSimple(thumbnailPreviewTime.toDouble()),
                    style = TextStyle(color = ComposeColor.White, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(ComposeColor.Black.copy(alpha = 0.7f))
                        .padding(4.dp)
                )
            }
        }
        
        // VIDEO INFO - Top Left
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                style = TextStyle(color = ComposeColor.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 20.dp)
                    .background(ComposeColor.DarkGray.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        
        // FEEDBACK AREA
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 80.dp)) {
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(currentVolume.toFloat() / viewModel.maxVolume.toFloat() * 100).toInt()}%",
                    style = TextStyle(color = ComposeColor.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(ComposeColor.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(color = ComposeColor.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(ComposeColor.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showQuickSeekFeedback -> Text(
                    text = quickSeekFeedbackText,
                    style = TextStyle(color = ComposeColor.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(ComposeColor.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showSeekTime -> Text(
                    text = if (seekDirection.isNotEmpty()) "$seekTargetTime $seekDirection" else seekTargetTime,
                    style = TextStyle(color = ComposeColor.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(ComposeColor.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = ComposeColor.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(ComposeColor.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ===== ENHANCED PROGRESS BAR COMPONENT =====

@Composable
fun EnhancedDraggableProgressBar(
    position: Float,
    duration: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    getFreshPosition: () -> Float,
    modifier: Modifier = Modifier,
    showThumbnailPreview: (Int, Float) -> Unit,
    hideThumbnailPreview: () -> Unit
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0f) }
    var hasPassedThreshold by remember { mutableStateOf(false) }
    var thresholdStartX by remember { mutableStateOf(0f) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(48.dp)) {
        // Progress bar background
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(ComposeColor.Gray.copy(alpha = 0.6f)))
        
        // Progress bar fill
        Box(modifier = Modifier
            .fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f)
            .height(4.dp)
            .align(Alignment.CenterStart)
            .background(ComposeColor.White))
        
        // Enhanced drag area with thumbnail preview
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
                        
                        // Show thumbnail preview immediately
                        val timeSeconds = (dragStartPosition).toInt()
                        showThumbnailPreview(timeSeconds, offset.x)
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
                        
                        // Update thumbnail preview during drag
                        val timeSeconds = (newPosition).toInt()
                        showThumbnailPreview(timeSeconds, currentX)
                    },
                    onDragEnd = { 
                        hasPassedThreshold = false
                        thresholdStartX = 0f
                        hideThumbnailPreview()
                        onValueChangeFinished() 
                    }
                )
            }
        )
    }
}

// ===== UTILITY FUNCTIONS =====

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
