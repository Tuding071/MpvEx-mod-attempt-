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
    var isFixingFile by remember { mutableStateOf(false) }
    var fixProgress by remember { mutableStateOf(0) }
    
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

    // NEW: Fix video file using FFmpeg
    suspend fun fixVideoFile(originalPath: String): String? {
        isFixingFile = true
        fixProgress = 0
        
        return try {
            val originalFile = File(originalPath)
            if (!originalFile.exists()) {
                return null
            }
            
            // Create temporary file for fixed version
            val tempDir = File(context.cacheDir, "fixed_videos")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            val fixedFilePath = File(tempDir, "fixed_${originalFile.name}").absolutePath
            
            preprocessingStage = "Creating continuous video stream..."
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
                fixedFilePath
            )
            
            preprocessingStage = "Remuxing video segments..."
            fixProgress = 40
            
            // Execute FFmpeg command
            val process = Runtime.getRuntime().exec(ffmpegCommand)
            
            // Monitor progress (simplified - in real implementation you'd parse FFmpeg output)
            var progressCounter = 40
            while (process.isAlive) {
                delay(500)
                progressCounter += 2
                if (progressCounter <= 90) {
                    fixProgress = progressCounter
                }
            }
            
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && File(fixedFilePath).exists()) {
                preprocessingStage = "Finalizing fixed video..."
                fixProgress = 95
                delay(500)
                
                fixedFilePath
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            isFixingFile = false
            fixProgress = 100
        }
    }

    // NEW: Load fixed video into MPV
    fun loadFixedVideo(fixedFilePath: String) {
        MPVLib.command("loadfile", fixedFilePath, "replace")
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
            
            val fixedPath = fixVideoFile(currentVideoPath)
            
            if (fixedPath != null) {
                preprocessingStage = "Loading fixed video..."
                preprocessingProgress = 100
                delay(500)
                
                // Load the fixed video
                loadFixedVideo(fixedPath)
                
                isPreprocessing = false
                isStreamPrepared = true
            } else {
                preprocessingStage = "Failed to fix video"
                delay(2000)
                isPreprocessing = false
                // Continue with original file despite issues
                MPVLib.setPropertyBoolean("pause", false)
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
    
    // ... (rest of your existing functions remain the same - handleTap, startLongTapDetection, etc.)
    // [Include all the existing gesture handling functions from your previous code]
    
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
                }
            }
        }
        
        // MAIN UI - Only show when not preprocessing or showing confirmation
        else if (!isPreprocessing && !showFixConfirmation) {
            // [Include your existing main UI code here]
            // This is where your normal video player UI goes
        }
    }
}

// [Include all your existing helper functions - SimpleDraggableProgressBar, formatTimeSimple, etc.]
