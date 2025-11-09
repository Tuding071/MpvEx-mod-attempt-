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
    var seekStartY by remember { mutableStateOf(0f) }
    var seekStartPosition by remember { mutableStateOf(0.0) }
    var wasPlayingBeforeSeek by remember { mutableStateOf(false) }
    
    var isSeekInProgress by remember { mutableStateOf(false) }
    val seekThrottleMs = 30L
    
    var lastCleanupTime by remember { mutableStateOf(0L) }
    val cleanupInterval = 10 * 60 * 1000L
    
    var touchStartTime by remember { mutableStateOf(0L) }
    var touchStartX by remember { mutableStateOf(0f) }
    var touchStartY by remember { mutableStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var isLongTap by remember { mutableStateOf(false) }
    var isHorizontalSwipe by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var longTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    val longTapThreshold = 300L
    val horizontalSwipeThreshold = 30f
    val verticalSwipeThreshold = 30f
    val maxHorizontalMovement = 50f
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
    
    fun gentleCleanup() {
        MPVLib.setPropertyString("demuxer-readahead-secs", "10")
        MPVLib.setPropertyString("cache-secs", "10")
        MPVLib.setPropertyInt("demuxer-max-bytes", 100 * 1024 * 1024)
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("hr-seek", "yes")
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
    
    fun toggleSeekbarAndInfo() {
        showSeekbar = !showSeekbar
        if (showSeekbar) {
            showSeekbarWithTimeout()
        } else {
            hideSeekbarJob?.cancel()
        }
        
        // Always show video info when toggling seekbar
        showVideoInfo = 1
        videoInfoJob?.cancel()
        videoInfoJob = coroutineScope.launch {
            delay(4000)
            showVideoInfo = 0
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
            showVideoInfo = 0
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
        toggleSeekbarAndInfo()
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
    
    fun checkForHorizontalSwipe(currentX: Float, currentY: Float): Boolean {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return false
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        if (deltaX > horizontalSwipeThreshold && deltaX > deltaY && deltaY < maxVerticalMovement) {
            isHorizontalSwipe = true
            longTapJob?.cancel()
            return true
        }
        return false
    }
    
    fun checkForVerticalSwipe(currentX: Float, currentY: Float): Boolean {
        if (isHorizontalSwipe || isVerticalSwipe || isLongTap) return false
        
        val deltaX = kotlin.math.abs(currentX - touchStartX)
        val deltaY = kotlin.math.abs(currentY - touchStartY)
        
        if (deltaY > verticalSwipeThreshold && deltaY > deltaX && deltaX < maxHorizontalMovement) {
            isVerticalSwipe = true
            longTapJob?.cancel()
            return true
        }
        return false
    }
    
    fun startHorizontalSeeking(startX: Float) {
        isHorizontalSwipe = true
        cancelAutoHide()
        seekStartX = startX
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        showSeekbar = true
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun startVerticalSeeking(startY: Float) {
        isVerticalSwipe = true
        cancelAutoHide()
        seekStartY = startY
        seekStartPosition = MPVLib.getPropertyDouble("time-pos") ?: 0.0
        wasPlayingBeforeSeek = MPVLib.getPropertyBoolean("pause") == false
        isSeeking = true
        showSeekTime = true
        showSeekbar = true
        
        if (wasPlayingBeforeSeek) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }
    
    fun handleHorizontalSeeking(currentX: Float) {
        if (!isSeeking) return
        
        val deltaX = currentX - seekStartX
        val pixelsPerSecond = 6f / 0.033f
        val timeDeltaSeconds = deltaX / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        performRealTimeSeek(clampedPosition)
    }
    
    fun handleVerticalSeeking(currentY: Float) {
        if (!isSeeking) return
        
        val deltaY = seekStartY - currentY // Inverted for natural feel (up = forward)
        val pixelsPerSecond = 6f / 0.033f
        val timeDeltaSeconds = deltaY / pixelsPerSecond
        val newPositionSeconds = seekStartPosition + timeDeltaSeconds
        val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
        val clampedPosition = newPositionSeconds.coerceIn(0.0, duration)
        
        seekTargetTime = formatTimeSimple(clampedPosition)
        currentTime = formatTimeSimple(clampedPosition)
        
        performRealTimeSeek(clampedPosition)
    }
    
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
            seekStartY = 0f
            seekStartPosition = 0.0
            wasPlayingBeforeSeek = false
            scheduleSeekbarHide()
        }
    }
    
    fun endVerticalSeeking() {
        endHorizontalSeeking() // Same cleanup logic
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
            endVerticalSeeking()
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
        MPVLib.setPropertyString("hwdec", "no")
        MPVLib.setPropertyString("vo", "gpu")
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("vd-lavc-threads", "4")
        MPVLib.setPropertyString("audio-channels", "auto")
        MPVLib.setPropertyString("demuxer-lavf-threads", "4")
        
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyInt("demuxer-max-bytes", 100 * 1024 * 1024)
        MPVLib.setPropertyString("demuxer-readahead-secs", "10")
        MPVLib.setPropertyString("cache-secs", "10")
        
        MPVLib.setPropertyString("cache-pause", "no")
        MPVLib.setPropertyString("cache-initial", "0.5")
        MPVLib.setPropertyString("video-sync", "display-resample")
        MPVLib.setPropertyString("untimed", "yes")
        MPVLib.setPropertyString("hr-seek", "yes")
        MPVLib.setPropertyString("hr-seek-framedrop", "no")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-skipidct", "all")
        MPVLib.setPropertyString("vd-lavc-assemble", "yes")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "50M")
        MPVLib.setPropertyString("demuxer-seekable-cache", "yes")
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
        while (isActive) {
            delay(30 * 1000)
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCleanupTime > cleanupInterval) {
                if (!isSeeking && !isDragging && !userInteracting) {
                    gentleCleanup()
                    lastCleanupTime = currentTime
                }
            }
        }
    }
    
    LaunchedEffect(currentPosition, videoDuration) {
        if (videoDuration > 0 && currentPosition > videoDuration - 5) {
            gentleCleanup()
        }
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
            delay(500)
        }
    }
    
    val displayText = when (showVideoInfo) {
        1 -> fileName
        else -> ""
    }
    
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
                // LEFT 3% - Video info and seekbar toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.03f)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toggleSeekbarAndInfo() }
                        )
                )
                
                // CENTER 94% - All gestures (tap, long tap, horizontal swipe, vertical swipe)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
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
                                        // Check if this should become a horizontal swipe
                                        if (checkForHorizontalSwipe(event.x, event.y)) {
                                            startHorizontalSeeking(event.x)
                                        }
                                        // Check if this should become a vertical swipe
                                        else if (checkForVerticalSwipe(event.x, event.y)) {
                                            startVerticalSeeking(event.y)
                                        }
                                    } else if (isHorizontalSwipe) {
                                        // Continue horizontal seeking
                                        handleHorizontalSeeking(event.x)
                                    } else if (isVerticalSwipe) {
                                        // Continue vertical seeking
                                        handleVerticalSeeking(event.y)
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
                
                // RIGHT 3% - Video info and seekbar toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.03f)
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toggleSeekbarAndInfo() }
                        )
                )
            }
        }
        
        // BOTTOM PROGRESS BAR AREA (Visual only - no dragging)
        if (showSeekbar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 60.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(), 
                    verticalArrangement = Arrangement.spacedBy(1.dp) // Reduced space
                ) {
                    // Time display
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart), 
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "$currentTime / $totalTime",
                                style = TextStyle(
                                    color = Color.White, 
                                    fontSize = 14.sp, 
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .background(Color.DarkGray.copy(alpha = 0.8f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    // Progress bar (visual only)
                    Box(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.CenterStart).background(Color.Gray.copy(alpha = 0.6f)))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = if (seekbarDuration > 0) (seekbarPosition / seekbarDuration).coerceIn(0f, 1f) else 0f)
                                .height(4.dp)
                                .align(Alignment.CenterStart)
                                .background(Color.White)
                        )
                    }
                }
            }
        }
        
        // VIDEO INFO - Top Left (no background, moved up, with shadow)
        if (showVideoInfo != 0) {
            Text(
                text = displayText,
                style = TextStyle(
                    color = Color.White, 
                    fontSize = 15.sp, 
                    fontWeight = FontWeight.Medium,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.7f),
                        blurRadius = 4f,
                        offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                    )
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 60.dp, y = 10.dp) // Moved up from 20dp to 10dp
            )
        }
        
        // FEEDBACK AREA (no background, moved up, with shadow)
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 60.dp)) { // Moved up from 80dp to 60dp
            when {
                showVolumeFeedbackState -> Text(
                    text = "Volume: ${(currentVolume.toFloat() / viewModel.maxVolume.toFloat() * 100).toInt()}%",
                    style = TextStyle(
                        color = Color.White, 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Medium,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            blurRadius = 4f,
                            offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                        )
                    )
                )
                isSpeedingUp -> Text(
                    text = "2X",
                    style = TextStyle(
                        color = Color.White, 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Medium,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            blurRadius = 4f,
                            offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                        )
                    )
                )
                showSeekTime -> Text(
                    text = seekTargetTime,
                    style = TextStyle(
                        color = Color.White, 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Medium,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            blurRadius = 4f,
                            offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                        )
                    )
                )
                showPlaybackFeedback -> Text(
                    text = playbackFeedbackText,
                    style = TextStyle(
                        color = Color.White, 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Medium,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.7f),
                            blurRadius = 4f,
                            offset = androidx.compose.ui.geometry.Offset(1f, 1f)
                        )
                    )
                )
            }
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
