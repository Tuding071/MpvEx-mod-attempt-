package app.marlboroadvance.mpvex.ui.player

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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // ENHANCED PREPROCESSING STATES
    var isPreprocessing by remember { mutableStateOf(false) }
    var preprocessingProgress by remember { mutableStateOf(0) }
    var preprocessingStage by remember { mutableStateOf("") }
    var isStreamPrepared by remember { mutableStateOf(false) }
    var detectedSegments by remember { mutableStateOf(0) }
    var requiresEnhancedProcessing by remember { mutableStateOf(false) }
    
    // NEW: Fix confirmation dialog and file processing
    var showFixConfirmation by remember { mutableStateOf(false) }
    var videoRequiresFix by remember { mutableStateOf(false) }
    var fixConfirmed by remember { mutableStateOf(false) }
    var confirmationSwipeProgress by remember { mutableStateOf(0f) }
    var currentVideoPath by remember { mutableStateOf("") }
    var currentVideoUri by remember { mutableStateOf<Uri?>(null) }
    var isFixingFile by remember { mutableStateOf(false) }
    var fixProgress by remember { mutableStateOf(0) }
    var fixError by remember { mutableStateOf<String?>(null) }
    
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
    
    // ADD: Seek direction for feedback
    var seekDirection by remember { mutableStateOf("") } // "+" or "-" or ""
    
    // REMOVED: lastSeekTime and seekDebounceMs
    // ADD: Simple throttle control
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 0L // Small delay between seek commands
    
    // CLEAR GESTURE STATES WITH MUTUAL EXCLUSION
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) } // ADD: Vertical swipe state
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // THRESHOLDS
    val longTapThreshold = 300L // ms
    val horizontalSwipeThreshold = 30f // pixels - minimum horizontal movement to trigger seeking
    val verticalSwipeThreshold = 40f // pixels - minimum vertical movement to trigger quick seek
    val maxVerticalMovement = 50f // pixels - maximum vertical movement allowed for horizontal swipe
    val maxHorizontalMovement = 50f // pixels - maximum horizontal movement allowed for vertical swipe
    
    // ADD: Quick seek amount in seconds
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
    
    // ADD: Quick seek feedback
    var showQuickSeekFeedback by remember { mutableStateOf(false) }
    var quickSeekFeedbackText by remember { mutableStateOf("") }
    var quickSeekFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }

    // NEW: Detect segmented files based on format and behavior
    fun detectSegmentedFile(format: String, duration: Double, filePath: String): Boolean {
        // Check file format indicators
        val segmentedFormats = listOf("mp4", "mov", "m4v", "ismv")
        val isProblematicFormat = format.lowercase() in segmentedFormats
        
        // Check for TS-like behavior (common in social media downloads)
        val hasInconsistentTimestamps = duration <= 0 || duration > 36000 // 10+ hours often indicates issues
        
        // Check for known problematic patterns
        val isLikelySocialMedia = filePath.contains("tiktok", ignoreCase = true) ||
                                 filePath.contains("instagram", ignoreCase = true) ||
                                 filePath.contains("whatsapp", ignoreCase = true) ||
                                 filePath.contains("facebook", ignoreCase = true) ||
                                 filePath.contains("snapchat", ignoreCase = true)
        
        // Check for TS-in-MP4 patterns
        val hasTSlikeName = filePath.contains("ts", ignoreCase = true) && 
                           (filePath.contains(".mp4") || filePath.contains(".mov"))
        
        return isProblematicFormat && (hasInconsistentTimestamps || isLikelySocialMedia || hasTSlikeName)
    }

    // NEW: Enhanced scanning for segmented files
    suspend fun performEnhancedSegmentScanning(duration: Double): Int {
        var segmentsFound = 0
        if (duration > 10) {
            // Comprehensive scan for segmented files
            val scanPoints = listOf(0.05, 0.10, 0.20, 0.35, 0.50, 0.65, 0.80, 0.95)
            val progressPerPoint = 50 / scanPoints.size
            
            for ((index, point) in scanPoints.withIndex()) {
                val targetTime = duration * point
                MPVLib.command("seek", targetTime.toString(), "absolute", "keyframes")
                delay(100) // Longer delay for problematic files
                
                // Check if seek was successful
                val actualPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                if (abs(actualPos - targetTime) > 2.0) {
                    segmentsFound++
                }
                
                preprocessingProgress = 30 + (index + 1) * progressPerPoint
            }
        } else {
            // Short video - quick scan
            MPVLib.command("seek", (duration * 0.3).toString(), "absolute", "keyframes")
            preprocessingProgress = 50
            delay(80)
            
            MPVLib.command("seek", (duration * 0.7).toString(), "absolute", "keyframes")
            preprocessingProgress = 70
            delay(80)
        }
        return segmentsFound
    }

    // NEW: Quick scan for normal files
    suspend fun performQuickScan(duration: Double) {
        if (duration > 30) {
            // Just scan key points quickly
            MPVLib.command("seek", (duration * 0.2).toString(), "absolute", "keyframes")
            preprocessingProgress = 60
            delay(50)
            
            MPVLib.command("seek", (duration * 0.8).toString(), "absolute", "keyframes")
            preprocessingProgress = 80
            delay(50)
        }
        // For very short videos, no scanning needed
    }

    // NEW: Check if FFmpeg is available
    fun isFFmpegAvailable(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("ffmpeg", "-version")).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    // NEW: Fix video file using FFmpeg
    suspend fun fixVideoFile(originalPath: String, originalUri: Uri?): String? {
        isFixingFile = true
        fixProgress = 0
        fixError = null
        
        return try {
            // Check if FFmpeg is available
            if (!isFFmpegAvailable()) {
                fixError = "FFmpeg not available"
                return null
            }
            
            preprocessingStage = "Preparing video for fixing..."
            fixProgress = 10
            
            // Create temporary file for fixed version
            val tempDir = File(context.cacheDir, "fixed_videos")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            val fixedFileName = "fixed_${System.currentTimeMillis()}_${File(originalPath).name}"
            val fixedFilePath = File(tempDir, fixedFileName).absolutePath
            
            preprocessingStage = "Remuxing video into continuous format..."
            fixProgress = 20
            
            // Use FFmpeg to remux the file into a continuous MP4
            // This command will:
            // 1. Copy all streams without re-encoding (-c copy)
            // 2. Force continuous timestamps
            // 3. Create a proper MP4 structure
            val ffmpegCommand = arrayOf(
                "ffmpeg",
                "-y", // Overwrite output file
                "-i", originalPath,
                "-c", "copy", // Copy all streams without re-encoding
                "-movflags", "+faststart", // Optimize for streaming
                "-fflags", "+genpts", // Generate missing PTS
                "-avoid_negative_ts", "make_zero", // Fix timestamp issues
                "-map", "0", // Include all streams
                "-f", "mp4", // Force MP4 format
                fixedFilePath
            )
            
            preprocessingStage = "Processing video segments..."
            fixProgress = 30
            
            // Execute FFmpeg command
            val process = Runtime.getRuntime().exec(ffmpegCommand)
            
            // Monitor process (simplified progress)
            var progressCounter = 30
            val progressJob = coroutineScope.launch {
                while (isFixingFile && progressCounter < 90) {
                    delay(1000)
                    progressCounter += 5
                    fixProgress = progressCounter
                }
            }
            
            val exitCode = process.waitFor()
            progressJob.cancel()
            
            if (exitCode == 0) {
                // Verify the fixed file exists and has content
                val fixedFile = File(fixedFilePath)
                if (fixedFile.exists() && fixedFile.length() > 1024) {
                    preprocessingStage = "Finalizing fixed video..."
                    fixProgress = 95
                    delay(500)
                    
                    // Store the fixed file path for future use
                    val fixedFilesDir = File(context.filesDir, "fixed_videos")
                    if (!fixedFilesDir.exists()) {
                        fixedFilesDir.mkdirs()
                    }
                    
                    val permanentFixedPath = File(fixedFilesDir, fixedFileName).absolutePath
                    fixedFile.copyTo(File(permanentFixedPath), overwrite = true)
                    
                    // Clean up temp file
                    fixedFile.delete()
                    
                    fixProgress = 100
                    delay(300)
                    
                    permanentFixedPath
                } else {
                    fixError = "Fixed file creation failed"
                    null
                }
            } else {
                fixError = "FFmpeg process failed with code $exitCode"
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            fixError = "Error: ${e.message}"
            null
        } finally {
            isFixingFile = false
        }
    }

    // NEW: Load fixed video into MPV
    fun loadFixedVideo(fixedFilePath: String) {
        MPVLib.command("loadfile", fixedFilePath, "replace")
        // Update current path to the fixed version
        currentVideoPath = fixedFilePath
    }

    // ENHANCED: Smart preprocessing with file fixing capability
    fun analyzeAndProcessVideo() {
        isPreprocessing = true
        preprocessingProgress = 0
        preprocessingStage = "Analyzing video structure..."
        
        coroutineScope.launch {
            // Get video information first
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            val videoFormat = MPVLib.getPropertyString("file-format") ?: "unknown"
            currentVideoPath = MPVLib.getPropertyString("path") ?: ""
            
            // Get the original URI if available
            val intent = (context as? android.app.Activity)?.intent
            currentVideoUri = when {
                intent?.action == Intent.ACTION_SEND -> {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                intent?.action == Intent.ACTION_VIEW -> {
                    intent.data
                }
                else -> null
            }
            
            preprocessingStage = "Detecting video issues..."
            preprocessingProgress = 30
            
            // Check if video needs fixing
            val needsFix = detectSegmentedFile(videoFormat, duration, currentVideoPath)
            videoRequiresFix = needsFix
            
            if (needsFix) {
                preprocessingProgress = 100
                isPreprocessing = false
                
                // Show confirmation dialog instead of auto-fixing
                showFixConfirmation = true
            } else {
                // Normal preprocessing for good files
                preprocessingStage = "Quick optimization..."
                preprocessingProgress = 50
                
                performQuickScan(duration)
                
                preprocessingProgress = 90
                delay(100)
                
                isPreprocessing = false
                isStreamPrepared = true
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
    }

    // NEW: Handle fix confirmation
    fun confirmFix() {
        showFixConfirmation = false
        fixConfirmed = true
        
        coroutineScope.launch {
            isPreprocessing = true
            preprocessingStage = "Preparing to fix video..."
            preprocessingProgress = 0
            
            val fixedPath = fixVideoFile(currentVideoPath, currentVideoUri)
            
            if (fixedPath != null) {
                preprocessingStage = "Loading fixed video..."
                preprocessingProgress = 100
                delay(500)
                
                // Load the fixed video
                loadFixedVideo(fixedPath)
                
                isPreprocessing = false
                isStreamPrepared = true
                
                // Show success message
                showPlaybackFeedback("Video fixed successfully!")
            } else {
                preprocessingStage = "Failed to fix video: ${fixError ?: "Unknown error"}"
                delay(3000)
                isPreprocessing = false
                // Continue with original file despite issues
                MPVLib.setPropertyBoolean("pause", false)
                
                // Show error message
                showPlaybackFeedback("Fix failed: ${fixError ?: "Unknown error"}")
            }
        }
    }

    // NEW: Handle fix cancellation
    fun cancelFix() {
        showFixConfirmation = false
        fixConfirmed = false
        
        coroutineScope.launch {
            // Continue with original file despite issues
            delay(500)
            MPVLib.setPropertyBoolean("pause", false)
            showPlaybackFeedback("Playing original file (may have seeking issues)")
        }
    }

    // UPDATED: performRealTimeSeek with throttle
    fun performRealTimeSeek(targetPosition: Double) {
        if (isSeekInProgress) return
        
        isSeekInProgress = true
        MPVLib.command("seek", targetPosition.toString(), "absolute", "exact")
        
        coroutineScope.launch {
            delay(seekThrottleMs)
            isSeekInProgress = false
        }
    }
    
    // NEW: Function to get fresh position from MPV
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
    }
    
    // ADD: Quick seek function
    fun performQuickSeek(seconds: Int) {
        val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val newPosition = (currentPos + seconds).coerceIn(0.0, duration)
        
        // Show feedback
        quickSeekFeedbackText = if (seconds > 0) "+$seconds" else "$seconds"
        showQuickSeekFeedback = true
        quickSeekFeedbackJob?.cancel()
        quickSeekFeedbackJob = coroutineScope.launch {
            delay(1000)
            showQuickSeekFeedback = false
        }
        
        // Perform seek
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
    
    // UPDATED: checkForHorizontalSwipe to also check for vertical swipes
    fun checkForSwipeDirection(currentX: Float, currentY: Float): String {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return "" // Already determined or long tap active
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        // Check for horizontal swipe
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            return "horizontal"
        }
        
        // Check for vertical swipe
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            return "vertical"
        }
        
        return ""
    }
    
    // UPDATED: startHorizontalSeeking - MOTION BLUR REMOVED
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    // ADD: Start vertical swipe detection
    fun startVerticalSwipe(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        // Determine direction based on initial movement
        val currentY = startY
        val deltaY = currentY - touchStartY
        
        if (deltaY < 0) {
            // Swipe up - seek forward
            seekDirection = "+"
            performQuickSeek(quickSeekAmount)
        } else {
            // Swipe down - seek backward
            seekDirection = "-"
            performQuickSeek(-quickSeekAmount)
        }
    }
    
    // REPLACED: handleHorizontalSeeking with fixed sensitivity approach
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 4f / 0.016f // 250 pixels per second
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        // UPDATE: Set seek direction based on movement
        seekDirection = if (deltaX > 0) "+" else "-"
        
        // ALWAYS update UI instantly
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        // Send seek command with throttle for real-time frame updates
        performRealTimeSeek(clampedPosition)
    }
    
    // UPDATED: endHorizontalSeeking - MOTION BLUR REMOVED
    fun endHorizontalSeeking() {
        if (isSeeking) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: seekStartPosition
            performRealTimeSeek(currentPos)
            
            if (wasPlayingBeforeSeek) {
                coroutineScope.launch {
                    delay(100)
                    MPVLib.setPropertyBoolean("pause", false)
                }
            }
            
            isSeeking = false
            showSeekTime = false
            seekStartX = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            seekDirection = "" // Reset direction
            scheduleSeekbarHide()
        }
    }
    
    // ADD: End vertical swipe
    fun endVerticalSwipe() {
        isVerticalSwipe = false
        scheduleSeekbarHide()
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            // Long tap ended - reset speed
            isLongTap = false
            isSpeedingUp = false
            MPVLib.setPropertyDouble("speed", 1.0)
        } else if (isHorizontalSwipe) {
            // Horizontal swipe ended
            endHorizontalSeeking()
            isHorizontalSwipe = false
        } else if (isVerticalSwipe) {
            // Vertical swipe ended
            endVerticalSwipe()
            isVerticalSwipe = false
        } else if (touchDuration < 150) {
            // Short tap (less than 150ms)
            handleTap()
        }
        // Reset all gesture states
        isHorizontalSwipe = false
        isVerticalSwipe = false
        isLongTap = false
    }
    
    // MODIFIED: Enhanced LaunchedEffect for video initialization
    LaunchedEffect(Unit) {
        // Load video file info first
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
        
        // START ENHANCED ANALYSIS (with potential file fixing)
        analyzeAndProcessVideo()
        
        // Show video info briefly
        if (!isPreprocessing && !showFixConfirmation) {
            showVideoInfo = 1
            videoInfoJob?.cancel()
            videoInfoJob = coroutineScope.launch {
                delay(4000)
                showVideoInfo = 0
            }
        }
        
        scheduleSeekbarHide()
    }
    
    // Backup speed control
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
        }
    }
    
    // MODIFIED: Enhanced MPV config for segmented files
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
        
        // ENHANCED CONFIG FOR SEGMENTED FILES
        MPVLib.setPropertyString("demuxer-lavf-o", "seekable=1:fflags=+fastseek+genpts+sortdts")
        MPVLib.setPropertyBoolean("correct-pts", true)
        MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
        MPVLib.setPropertyString("demuxer-thread", "yes")
        MPVLib.setPropertyString("demuxer-mkv-subtitle-preroll", "yes")
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
    
    // UPDATED: handleProgressBarDrag - MOTION BLUR REMOVED
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
        val oldPosition = seekbarPosition
        seekbarPosition = newPosition
        
        // UPDATE: Set seek direction based on movement
        seekDirection = if (newPosition > oldPosition) "+" else "-"
        
        val targetPosition = newPosition.toDouble()
        
        // ALWAYS update UI instantly
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        
        // Send seek command with throttle for real-time frame updates
        performRealTimeSeek(targetPosition)
    }
    
    // UPDATED: handleDragFinished - MOTION BLUR REMOVED
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
        seekDirection = "" // Reset direction
        scheduleSeekbarHide()
    }
    
    // MODIFIED: Main Box with Fix Confirmation Dialog
    Box(modifier = modifier.fillMaxSize()) {
        // FIX CONFIRMATION DIALOG
        if (showFixConfirmation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // WARNING ICON/TEXT
                    Text(
                        text = "⚠️",
                        style = TextStyle(fontSize = 48.sp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "Video Format Issue Detected",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center
                    )
                    
                    Text(
                        text = "This video appears to be segmented or corrupted. This can cause seeking issues and playback problems.",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    
                    Text(
                        text = "Fix this video for smooth playback?",
                        style = TextStyle(
                            color = Color.Yellow,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )
                    
                    Text(
                        text = "This will create a fixed copy (no quality loss)",
                        style = TextStyle(
                            color = Color.Green,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        textAlign = TextAlign.Center
                    )
                    
                    // SWIPE TO CONFIRM AREA
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(60.dp)
                            .background(Color.DarkGray.copy(alpha = 0.6f))
                            .padding(2.dp)
                    ) {
                        // Swipe track
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Gray.copy(alpha = 0.3f))
                        )
                        
                        // Swipe thumb
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(confirmationSwipeProgress)
                                .fillMaxHeight()
                                .background(
                                    if (confirmationSwipeProgress > 0.9f) Color.Green
                                    else Color.Blue
                                )
                        )
                        
                        // Swipe text
                        Text(
                            text = if (confirmationSwipeProgress > 0.9f) "✓ FIX VIDEO" else "SWIPE RIGHT TO FIX →",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.align(Alignment.Center)
                        )
                        
                        // Swipe gesture area
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val newProgress = (confirmationSwipeProgress + (dragAmount.x / size.width)).coerceIn(0f, 1f)
                                            confirmationSwipeProgress = newProgress
                                        },
                                        onDragEnd = {
                                            if (confirmationSwipeProgress > 0.9f) {
                                                confirmFix()
                                            } else {
                                                confirmationSwipeProgress = 0f
                                            }
                                        }
                                    )
                                }
                        )
                    }
                    
                    // CANCEL BUTTON
                    Text(
                        text = "Tap here to skip fixing",
                        style = TextStyle(
                            color = Color.Gray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        modifier = Modifier
                            .clickable { cancelFix() }
                            .padding(16.dp)
                    )
                }
            }
        }
        
        // PREPROCESSING OVERLAY
        else if (isPreprocessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (isFixingFile) "$fixProgress%" else "$preprocessingProgress%",
                        style = TextStyle(
                            color = Color.White, 
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    
                    Text(
                        text = if (isFixingFile) preprocessingStage else preprocessingStage,
                        style = TextStyle(
                            color = Color.White, 
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    
                    if (detectedSegments > 0 && !isFixingFile) {
                        Text(
                            text = "Detected $detectedSegments problematic segments",
                            style = TextStyle(
                                color = Color.Yellow,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    if (fixError != null) {
                        Text(
                            text = "Error: $fixError",
                            style = TextStyle(
                                color = Color.Red,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        
        // MAIN UI - Only show when not preprocessing or showing confirmation
        else if (!isPreprocessing && !showFixConfirmation) {
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
                            // USE SINGLE pointerInteropFilter FOR ALL GESTURES TO AVOID CONFLICTS
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
                                            // Check if this should become a horizontal or vertical swipe
                                            when (checkForSwipeDirection(event.x, event.y)) {
                                                "horizontal" -> {
                                                    startHorizontalSeeking(event.x)
                                                }
                                                "vertical" -> {
                                                    startVerticalSwipe(event.y)
                                                }
                                            }
                                        } else if (isHorizontalSwipe) {
                                            // Continue horizontal seeking
                                            handleHorizontalSeeking(event.x)
                                        }
                                        // If it's a long tap or vertical swipe, ignore movement (allow slight finger movement during hold)
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
}

// [Include all your existing helper functions - SimpleDraggableProgressBar, formatTimeSimple, etc.]
