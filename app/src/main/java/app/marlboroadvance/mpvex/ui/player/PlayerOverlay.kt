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
import kotlin.math.abs

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
    
    // ENHANCED DEBOUNCING FOR HORIZONTAL SCRUBBING
    var lastFrameSeekTime by remember { mutableStateOf(0L) }
    val debounceRejectWindow = 20L // 20ms rejection
    val debounceAcceptWindow = 5L  // 5ms acceptance
    
    // FRAME SCRUBBING VARIABLES
    var isFrameScrubbing by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableStateOf(0) }
    var totalFrames by remember { mutableStateOf(0) }
    var videoFPS by remember { mutableStateOf(30.0) }
    
    // Sensitivity for 15-second full swipe
    val screenWidthPixels = 1000f
    val fullSwipeSeconds = 15.0
    val framesInFullSwipe = (fullSwipeSeconds * videoFPS).toInt()
    val pixelsPerFrame = (screenWidthPixels / framesInFullSwipe).coerceAtLeast(4f)
    
    // DIRECTIONAL FRAME SCRUBBING
    var accumulatedPixels by remember { mutableStateOf(0f) }
    var lastDirection by remember { mutableStateOf(0) }
    var scrubStartX by remember { mutableStateOf(0f) }
    
    // ENHANCED PREDICTIVE PRE-DECODING SYSTEM
    var framePreDecodeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isFramePreDecodingActive by remember { mutableStateOf(false) }
    var frameCache by remember { mutableStateOf<MutableSet<Int>>(mutableSetOf()) }
    
    // PREDICTIVE CHUNK SYSTEM
    var consecutiveDirectionCommands by remember { mutableStateOf(0) }
    var lastChunkDirection by remember { mutableStateOf(0) }
    var nextChunkSize by remember { mutableStateOf(30) } // 1=30, 2=60, 1=30, 2=60 pattern
    
    // Chunk sizes
    val chunk1Size = 30
    val chunk2Size = 60
    
    // MEMORY OPTIMIZATION
    var lastCleanupTime by remember { mutableStateOf(0L) }
    val cleanupInterval = 10 * 60 * 1000L
    
    // GESTURE STATES
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // THRESHOLDS
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val maxVerticalMovement = 50f
    
    var showVideoInfo by remember { mutableStateOf(0) }
    var videoTitle by remember { mutableStateOf("Video") }
    var fileName by remember { mutableStateOf("Video") }
    var videoInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var userInteracting by remember { mutableStateOf(false) }
    var hideSeekbarJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showPlaybackFeedback by remember { mutableStateOf(false) }
    var playbackFeedbackText by remember { mutableStateOf("") }
    var playbackFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    var showVolumeFeedbackState by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(viewModel.currentVolume.value) }
    var volumeFeedbackJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val coroutineScope = remember { CoroutineScope(Dispatchers.Main) }
    
    // UTILITY FUNCTIONS
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
    
    // FRAME MANAGEMENT FUNCTIONS
    fun calculateFrameFromTime(time: Double): Int {
        return (time * videoFPS).toInt()
    }
    
    fun calculateTimeFromFrame(frame: Int): Double {
        return frame / videoFPS
    }
    
    fun seekToExactFrame(frame: Int) {
        val targetTime = calculateTimeFromFrame(frame)
        MPVLib.command("seek", targetTime.toString(), "absolute", "exact")
        currentFrame = frame.coerceIn(0, totalFrames)
    }
    
    // PREDICTIVE PRE-DECODING SYSTEM - MOVED UP TO FIX COMPILATION ERROR
    fun triggerPredictivePreDecoding(currentFrame: Int, direction: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            val chunkSize = nextChunkSize
            val chunkStart = if (direction == 1) {
                currentFrame + 1
            } else {
                currentFrame - chunkSize
            }.coerceAtLeast(0)
            
            val chunkEnd = if (direction == 1) {
                currentFrame + chunkSize
            } else {
                currentFrame - 1
            }.coerceAtMost(totalFrames)
            
            // Pre-decode the chunk
            val range = if (direction == 1) chunkStart..chunkEnd else chunkEnd downTo chunkStart
            for (frame in range) {
                if (!isFrameScrubbing) break
                if (frame in 0..totalFrames && frame !in frameCache) {
                    val frameTime = calculateTimeFromFrame(frame)
                    MPVLib.command("seek", frameTime.toString(), "absolute", "exact", "keyframes")
                    frameCache.add(frame)
                    delay(12) // Balanced decoding speed
                }
            }
            
            // Alternate chunk sizes for next trigger (1, 2, 1, 2 pattern)
            nextChunkSize = if (nextChunkSize == chunk1Size) chunk2Size else chunk1Size
        }
    }
    
    // ENHANCED DEBOUNCING FOR HORIZONTAL SCRUBBING
    fun canSeekDueToDebounce(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastSeek = currentTime - lastFrameSeekTime
        
        // 20ms rejection window, 5ms acceptance window
        return timeSinceLastSeek >= debounceRejectWindow && 
               timeSinceLastSeek < (debounceRejectWindow + debounceAcceptWindow)
    }
    
    fun seekToExactFrameDebounced(frame: Int) {
        if (canSeekDueToDebounce()) {
            seekToExactFrame(frame)
            lastFrameSeekTime = System.currentTimeMillis()
            
            // TRACK CONSECUTIVE COMMANDS FOR PREDICTION
            val currentDirection = if (frame > currentFrame) 1 else if (frame < currentFrame) -1 else 0
            if (currentDirection == lastChunkDirection) {
                consecutiveDirectionCommands++
            } else {
                consecutiveDirectionCommands = 1
                lastChunkDirection = currentDirection
                nextChunkSize = chunk1Size // Reset to chunk 1 on direction change
            }
            
            // TRIGGER PREDICTIVE PRE-DECODING
            if (consecutiveDirectionCommands >= 30 && lastChunkDirection != 0) {
                triggerPredictivePreDecoding(currentFrame, lastChunkDirection)
                consecutiveDirectionCommands = 0 // Reset counter after triggering
            }
        }
    }
    
    // NORMAL FRAME CAROUSEL
    fun startNormalFrameCarousel() {
        if (isFramePreDecodingActive) return
        
        framePreDecodeJob?.cancel()
        isFramePreDecodingActive = true
        
        framePreDecodeJob = coroutineScope.launch(Dispatchers.IO) {
            while (this.isActive && isFramePreDecodingActive) {
                val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
                val currentFrameNum = calculateFrameFromTime(currentPos)
                
                // Skip during user interaction
                if (isFrameScrubbing || isSeeking || isDragging || userInteracting) {
                    delay(100)
                    continue
                }
                
                // Maintain 30-frame buffer around current position
                val startFrame = (currentFrameNum - 15).coerceAtLeast(0)
                val endFrame = (currentFrameNum + 15).coerceAtMost(totalFrames)
                
                // Clear distant frames
                frameCache.removeAll { frame -> frame < currentFrameNum - 30 || frame > currentFrameNum + 30 }
                
                // Pre-decode buffer
                for (frame in startFrame..endFrame) {
                    if (!isFramePreDecodingActive) break
                    if (frame !in frameCache) {
                        val frameTime = calculateTimeFromFrame(frame)
                        MPVLib.command("seek", frameTime.toString(), "absolute", "exact", "keyframes")
                        frameCache.add(frame)
                        delay(16)
                    }
                }
                
                // Return to current position
                if (this.isActive && isFramePreDecodingActive) {
                    MPVLib.command("seek", currentPos.toString(), "absolute", "exact", "keyframes")
                }
                
                delay(200)
            }
        }
    }
    
    fun stopFramePreDecoding() {
        isFramePreDecodingActive = false
        framePreDecodeJob?.cancel()
        framePreDecodeJob = null
        frameCache.clear()
        // Reset predictive system
        consecutiveDirectionCommands = 0
        lastChunkDirection = 0
        nextChunkSize = chunk1Size
    }
    
    fun resetFramePreDecoding() {
        stopFramePreDecoding()
        coroutineScope.launch {
            delay(100)
            startNormalFrameCarousel()
        }
    }
    
    // FRAME SCRUBBING FUNCTIONS
    fun startFrameScrubbing(startX: Float) {
        isFrameScrubbing = true
        isHorizontalSwipe = true
        cancelAutoHide()
        scrubStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        currentFrame = calculateFrameFromTime(seekStartPosition)
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        showSeekTime = true
        
        // Reset directional variables
        accumulatedPixels = 0f
        lastDirection = 0
        
        // Stop normal carousel
        stopFramePreDecoding()
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun handleFrameScrubbing(currentX: Float) {
        if (!isFrameScrubbing) return
        
        val deltaX = currentX - scrubStartX
        val currentDirection = if (deltaX > 1) 1 else if (deltaX < -1) -1 else 0
        
        if (currentDirection == 0) return
        
        if (currentDirection != 0 && currentDirection != lastDirection) {
            accumulatedPixels = 0f
            scrubStartX = currentX
            lastDirection = currentDirection
        }
        
        val pixelDelta = abs(deltaX)
        accumulatedPixels = pixelDelta
        
        if (accumulatedPixels >= pixelsPerFrame) {
            val targetFrame = if (currentDirection == 1) {
                currentFrame + 1
            } else {
                currentFrame - 1
            }.coerceIn(0, totalFrames)
            
            seekTargetTime = formatTimeSimple(calculateTimeFromFrame(targetFrame))
            currentTime = formatTimeSimple(calculateTimeFromFrame(targetFrame))
            
            // USE ENHANCED DEBOUNCED SEEKING
            seekToExactFrameDebounced(targetFrame)
            
            // DISCRETE CYCLE RESET
            accumulatedPixels = 0f
            scrubStartX = currentX
        }
    }
    
    fun endFrameScrubbing() {
        if (isFrameScrubbing) {
            val finalFrame = currentFrame
            
            coroutineScope.launch {
                // Clear and reset
                frameCache.clear()
                accumulatedPixels = 0f
                lastDirection = 0
                consecutiveDirectionCommands = 0
                lastChunkDirection = 0
                nextChunkSize = chunk1Size
                
                // Seek to final position
                seekToExactFrame(finalFrame)
                
                // Resume playback if needed
                if (wasPlayingBeforeSeek) {
                    MPVLib.setPropertyBoolean("pause", false)
                }
                
                // Restart normal frame carousel
                if (videoDuration > 0) {
                    startNormalFrameCarousel()
                }
                
                // Reset states
                isFrameScrubbing = false
                isHorizontalSwipe = false
                showSeekTime = false
                scrubStartX = 0f
                seekStartPosition = 0.0
                wasPlayingBeforeSeek = false
                scheduleSeekbarHide()
            }
        }
    }
    
    // CACHE CLEARING FUNCTION
    fun clearVideoCache() {
        MPVLib.setPropertyString("cache", "no")
        MPVLib.setPropertyString("demuxer-readahead-secs", "0")
        
        coroutineScope.launch {
            delay(16)
            MPVLib.setPropertyString("cache", "yes")
            MPVLib.setPropertyString("demuxer-readahead-secs", "10")
        }
    }
    
    // MEMORY OPTIMIZATION FUNCTION
    fun gentleCleanup() {
        MPVLib.setPropertyString("demuxer-readahead-secs", "10")
        MPVLib.setPropertyString("cache-secs", "10")
        MPVLib.setPropertyInt("demuxer-max-bytes", 100 * 1024 * 1024)
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("hr-seek", "yes")
    }
    
    // Function to get fresh position from MPV
    fun getFreshPosition(): Float {
        return (MPVLib.getPropertyDouble("time-pos") ?: 0.0).toFloat()
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
            if (isTouching && !isHorizontalSwipe) {
                isLongTap = true
                isSpeedingUp = true
                MPVLib.setPropertyDouble("speed", 2.0)
                stopFramePreDecoding()
            }
        }
    }
    
    fun checkForHorizontalSwipe(currentX: Float, currentY: Float): Boolean {
        if (isHorizontalSwipe || isLongTap) return false
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            isHorizontalSwipe = true
            longTapJob?.cancel()
            return true
        }
        return false
    }
    
    fun endTouch() {
        val touchDuration = System.currentTimeMillis() - touchStartTime
        isTouching = false
        longTapJob?.cancel()
        
        if (isLongTap) {
            isLongTap = false
            isSpeedingUp = false
            MPVLib.setPropertyDouble("speed", 1.0)
            resetFramePreDecoding()
        } else if (isHorizontalSwipe) {
            endFrameScrubbing()
            isHorizontalSwipe = false
        } else if (touchDuration < 150) {
            handleTap()
        }
        isHorizontalSwipe = false
        isLongTap = false
    }
    
    // VIDEO DETECTION AND INITIALIZATION
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
    
    // DETECT VIDEO FPS AND SETUP FRAME SYSTEM
    LaunchedEffect(Unit) {
        delay(1000)
        
        val detectedFPS = MPVLib.getPropertyDouble("container-fps") ?: 
                         MPVLib.getPropertyDouble("video-params/fps") ?: 30.0
        videoFPS = detectedFPS.coerceIn(24.0, 60.0)
        
        val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
        totalFrames = calculateFrameFromTime(duration)
        
        if (duration > 5) {
            startNormalFrameCarousel()
        }
    }
    
    // Clean up when composable leaves composition
    LaunchedEffect(Unit) {
        try {
        } finally {
            stopFramePreDecoding()
        }
    }
    
    // Speed control with frame system reset
    LaunchedEffect(isSpeedingUp) {
        if (isSpeedingUp) {
            MPVLib.setPropertyDouble("speed", 2.0)
            stopFramePreDecoding()
        } else {
            MPVLib.setPropertyDouble("speed", 1.0)
            resetFramePreDecoding()
        }
    }
    
    // Seekbar seeking - clean frames
    LaunchedEffect(isDragging) {
        if (isDragging) {
            stopFramePreDecoding()
        }
    }
    
    LaunchedEffect(isSeeking) {
        if (isSeeking) {
            stopFramePreDecoding()
        }
    }
    
    // OPTIMIZED MPV CONFIGURATION
    LaunchedEffect(Unit) {
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "4")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 300 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "30")
        MPVLib.setPropertyString("cache-secs", "30")
        
        MPVLib.setPropertyString("cache-pause", "no")
        MPVLib.setPropertyString("cache-initial", "0.5")
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        MPVLib.setPropertyString("frame-drop", "no")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-skipidct", "all")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "150M")
        MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
        MPVLib.setPropertyString("gpu-dumb-mode", "yes")
        MPVLib.setPropertyString("opengl-pbo", "yes")
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1:reconnect_at_eof=1:reconnect_streamed=1")
        MPVLib.setPropertyString("network-timeout", "30")
        MPVLib.setPropertyString("audio-client-name", "MPVEx-Predictive")
        MPVLib.setPropertyString("audio-samplerate", "auto")
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("video-aspect-override", "no")
        
        MPVLib.setPropertyString("correct-pts", "yes")
        MPVLib.setPropertyString("audio-pitch-correction", "yes")
        MPVLib.setPropertyString("video-latency-hacks", "yes")
    }
    
    // PERIODIC MEMORY MAINTENANCE
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30 * 1000)
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCleanupTime > cleanupInterval) {
                if (!isSeeking && !isDragging && !userInteracting && !isFrameScrubbing) {
                    gentleCleanup()
                    lastCleanupTime = currentTime
                }
            }
        }
    }
    
    // VIDEO END DETECTION
    LaunchedEffect(currentPosition, videoDuration) {
        if (videoDuration > 0 && currentPosition > videoDuration - 5) {
            gentleCleanup()
            stopFramePreDecoding()
        }
    }
    
    // CONTINUOUS POSITION UPDATES
    LaunchedEffect(Unit) {
        var lastSeconds = -1
        
        while (isActive) {
            val currentPos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 1.0
            val currentSeconds = currentPos.toInt()
            
            if (isFrameScrubbing) {
                currentTime = seekTargetTime
                totalTime = formatTimeSimple(duration)
            } else {
                if (currentSeconds != lastSeconds) {
                    currentTime = formatTimeSimple(currentPos)
                    totalTime = formatTimeSimple(duration)
                    currentFrame = calculateFrameFromTime(currentPos)
                    lastSeconds = currentSeconds
                }
            }
            
            if (!isDragging && !isFrameScrubbing) {
                seekbarPosition = currentPos.toFloat()
                seekbarDuration = duration.toFloat()
            }
            currentPosition = currentPos
            videoDuration = duration
            
            if (!isFramePreDecodingActive && duration > 5 && !isFrameScrubbing && !isDragging && !isSeeking) {
                startNormalFrameCarousel()
            }
            
            delay(500)
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }
    
    // PROGRESS BAR DRAG HANDLING
    fun handleProgressBarDrag(newPosition: Float) {
        cancelAutoHide()
        if (!isSeeking) {
            isSeeking = true
            wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
            showSeekTime = true
            
            stopFramePreDecoding()
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", true)
            }
        }
        isDragging = true
        seekbarPosition = newPosition
        val targetPosition = newPosition.toDouble()
        
        seekTargetTime = formatTimeSimple(targetPosition)
        currentTime = formatTimeSimple(targetPosition)
        currentFrame = calculateFrameFromTime(targetPosition)
        
        // Regular seeking (no enhanced debouncing)
        seekToExactFrame(currentFrame)
    }
    
    fun handleDragFinished() {
        isDragging = false
        
        coroutineScope.launch {
            clearVideoCache()
            delay(16)
            
            if (wasPlayingBeforeSeek) {
                MPVLib.setPropertyBoolean("pause", false)
            }
            
            if (videoDuration > 0) {
                startNormalFrameCarousel()
            }
            
            isSeeking = false
            showSeekTime = false
            wasPlayingBeforeSeek = false
            scheduleSeekbarHide()
        }
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
                
                // CENTER 90% - Frame scrubbing gestures
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
                                    if (!isHorizontalSwipe && !isLongTap) {
                                        if (checkForHorizontalSwipe(event.x, event.y)) {
                                            startFrameScrubbing(event.x)
                                        }
                                    } else if (isHorizontalSwipe) {
                                        handleFrameScrubbing(event.x)
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
                    .offset(y = (-1).dp) 
            ) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(modifier = Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$currentTime / $totalTime",
                                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            if (isFrameScrubbing) {
                                Text(
                                    text = "Frame: $currentFrame/$totalFrames",
                                    style = TextStyle(color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                        SimpleDraggableProgressBar(
                            position = seekbarPosition,
                            duration = seekbarDuration,
                            onValueChange = { handleProgressBarDrag(it) },
                            onValueChangeFinished = { handleDragFinished() },
                            getFreshPosition = { getFreshPosition() },
                            clearVideoCache = { clearVideoCache() },
                            modifier = Modifier.fillMaxSize()
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
                showSeekTime -> Text(
                    text = if (isFrameScrubbing) "Frame: $currentFrame" else seekTargetTime,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Color.DarkGray.copy(alpha = 0.8f)).padding(horizontal = 12.dp, vertical = 4.dp)
                )
                isFrameScrubbing -> Text(
                    text = "Frame Scrubbing",
                    style = TextStyle(color = Color.Yellow, fontSize = 14.sp, fontWeight = FontWeight.Medium),
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
    clearVideoCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartPosition by remember { mutableStateOf(0f) }
    var hasPassedThreshold by remember { mutableStateOf(false) }
    var thresholdStartX by remember { mutableStateOf(0f) }
    
    val movementThresholdPx = with(LocalDensity.current) { 25.dp.toPx() }
    
    Box(modifier = modifier.height(24.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.CenterStart).background(Color.Gray.copy(alpha = 0.6f)))
        Box(modifier = Modifier.fillMaxWidth(fraction = if (duration > 0) (position / duration).coerceIn(0f, 1f) else 0f).height(4.dp).align(Alignment.CenterStart).background(Color.White))
        Box(modifier = Modifier.fillMaxWidth().height(24.dp).align(Alignment.CenterStart).pointerInput(Unit) {
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
                            clearVideoCache()
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
        })
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
