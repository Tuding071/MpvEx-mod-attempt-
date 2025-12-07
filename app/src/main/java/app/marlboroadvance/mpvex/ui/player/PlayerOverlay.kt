package app.marlboroadvance.mpvex.ui.player

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import `is`.xyz.mpv.MPVLib
import java.util.*
import kotlin.math.*

// Define Thumbnail and ThumbnailState classes at the top level
data class Thumbnail(
    val timestamp: Double, // in seconds
    val bitmap: Bitmap?,
    val state: ThumbnailState = ThumbnailState.LOADING
)

enum class ThumbnailState {
    LOADING, READY, ERROR, GENERATING
}

@Composable
fun PlayerOverlay(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Core playback states
    var currentTime by remember { mutableStateOf("00:00") }
    var totalTime by remember { mutableStateOf("00:00") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0.0) }
    var videoDuration by remember { mutableStateOf(1.0) }
    
    // Thumbnail system states
    val thumbnailCache = remember { ThumbnailCache() }
    var scrubPosition by remember { mutableStateOf(0.0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }
    var showThumbnailStrip by remember { mutableStateOf(false) }
    var thumbnailStripOffset by remember { mutableStateOf(0f) }
    var activeThumbnailIndex by remember { mutableStateOf(0) }
    
    // UI feedback states
    var showFeedback by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf("") }
    var showVideoInfo by remember { mutableStateOf(0) }
    var videoTitle by remember { mutableStateOf("Video") }
    
    // Thumbnail strip dimensions
    val thumbnailWidth = with(LocalDensity.current) { 100.dp.toPx() }
    val thumbnailHeight = with(LocalDensity.current) { 56.dp.toPx() }
    val thumbnailSpacing = with(LocalDensity.current) { 2.dp.toPx() }
    
    // Calculate pixels per second for scrubbing
    val screenWidth = LocalDensity.current.run { 1080.dp.toPx() } // Assume 1080p screen
    val pixelsPerSecond = screenWidth / 20.0f // 20-second window fills screen
    
    // Initialize thumbnail cache
    LaunchedEffect(videoDuration) {
        if (videoDuration > 0) {
            thumbnailCache.initialize(videoDuration)
            // Start continuous thumbnail generation
            thumbnailCache.startContinuousGeneration(coroutineScope, currentPosition)
        }
    }
    
    // Update current position and trigger thumbnail generation
    LaunchedEffect(Unit) {
        while (true) {
            val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val dur = MPVLib.getPropertyDouble("duration") ?: 1.0
            
            currentPosition = pos
            videoDuration = dur
            currentTime = formatTime(pos)
            totalTime = formatTime(dur)
            
            // Update playback state
            isPlaying = MPVLib.getPropertyBoolean("pause") != true
            
            // Update thumbnail cache center
            if (!isScrubbing) {
                thumbnailCache.updateCenter(pos)
                scrubPosition = pos
            }
            
            delay(100) // Update 10 times per second
        }
    }
    
    // Handle scrub position changes
    LaunchedEffect(scrubPosition) {
        if (isScrubbing) {
            // Update active thumbnail index
            val index = thumbnailCache.timeToIndex(scrubPosition)
            activeThumbnailIndex = index
            
            // Ensure thumbnails are available around scrub position
            coroutineScope.launch {
                thumbnailCache.ensureThumbnailsAround(scrubPosition)
            }
        }
    }
    
    // Function to show feedback
    fun showTimedFeedback(text: String) {
        feedbackText = text
        showFeedback = true
        coroutineScope.launch {
            delay(1500)
            showFeedback = false
        }
    }
    
    // Handle tap gesture
    fun handleTap() {
        if (isPlaying) {
            MPVLib.setPropertyBoolean("pause", true)
            showTimedFeedback("Paused")
        } else {
            MPVLib.setPropertyBoolean("pause", false)
            showTimedFeedback("Playing")
        }
    }
    
    // Start scrubbing
    fun startScrubbing(startX: Float) {
        wasPlayingBeforeScrub = isPlaying
        isScrubbing = true
        showThumbnailStrip = true
        
        // Pause video for accurate scrubbing
        MPVLib.setPropertyBoolean("pause", true)
        
        // Set initial scrub position
        scrubPosition = currentPosition
        thumbnailStripOffset = 0f
        
        // Ensure thumbnails are ready
        coroutineScope.launch {
            thumbnailCache.ensureThumbnailsAround(scrubPosition)
        }
        
        showTimedFeedback("Scrubbing")
    }
    
    // Update scrubbing
    fun updateScrubbing(deltaX: Float) {
        // Calculate time delta based on pixels per second
        val timeDelta = deltaX / pixelsPerSecond
        val newPosition = (scrubPosition + timeDelta).coerceIn(0.0, videoDuration)
        
        scrubPosition = newPosition
        
        // Update strip offset for visual feedback
        thumbnailStripOffset = deltaX
        
        // Get thumbnail for current position
        coroutineScope.launch {
            thumbnailCache.getThumbnailAtTime(newPosition)
        }
    }
    
    // End scrubbing
    fun endScrubbing() {
        isScrubbing = false
        showThumbnailStrip = false
        
        // Seek to final position
        MPVLib.command("seek", scrubPosition.toString(), "absolute", "exact")
        
        // Resume playback if it was playing before
        if (wasPlayingBeforeScrub) {
            coroutineScope.launch {
                delay(100) // Small delay for seek to complete
                MPVLib.setPropertyBoolean("pause", false)
            }
        }
        
        showTimedFeedback("Seeked to ${formatTime(scrubPosition)}")
    }
    
    // Vertical swipe for quick seek
    fun handleVerticalSwipe(deltaY: Float) {
        val quickSeekAmount = if (deltaY < 0) 5.0 else -5.0
        val newPosition = (currentPosition + quickSeekAmount).coerceIn(0.0, videoDuration)
        
        MPVLib.command("seek", newPosition.toString(), "absolute", "exact")
        showTimedFeedback("${if (quickSeekAmount > 0) "+" else ""}${quickSeekAmount.toInt()}s")
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // Main gesture area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { handleTap() },
                        onLongPress = { startScrubbing(it.x) }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            startScrubbing(offset.x)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                // Horizontal drag - scrubbing
                                updateScrubbing(dragAmount.x)
                            } else {
                                // Vertical drag - quick seek
                                handleVerticalSwipe(dragAmount.y)
                            }
                        },
                        onDragEnd = {
                            endScrubbing()
                        }
                    )
                }
        )
        
        // Thumbnail strip (shown during scrubbing)
        if (showThumbnailStrip) {
            ThumbnailStrip(
                thumbnailCache = thumbnailCache,
                currentTime = scrubPosition,
                duration = videoDuration,
                activeIndex = activeThumbnailIndex,
                offset = thumbnailStripOffset,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(vertical = 8.dp)
            )
        }
        
        // Top info bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = videoTitle,
                    style = TextStyle(color = Color.White, fontSize = 16.sp),
                    maxLines = 1
                )
                
                Text(
                    text = "$currentTime / $totalTime",
                    style = TextStyle(color = Color.White, fontSize = 14.sp)
                )
            }
            
            // Progress indicator
            val progress = if (videoDuration > 0) (currentPosition / videoDuration).toFloat() else 0f
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomStart)
            ) {
                drawRect(
                    color = Color.Gray.copy(alpha = 0.5f),
                    size = Size(size.width, size.height)
                )
                drawRect(
                    color = Color.White,
                    size = Size(size.width * progress, size.height)
                )
            }
        }
        
        // Center feedback
        if (showFeedback) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = feedbackText,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
        
        // Bottom controls
        if (!isScrubbing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { handleTap() }
                    ) {
                        Text(
                            text = if (isPlaying) "⏸" else "▶",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 24.sp
                            ),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    
                    // Time display
                    Text(
                        text = currentTime,
                        style = TextStyle(color = Color.White, fontSize = 16.sp)
                    )
                    
                    // Placeholder for other controls
                    Box(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
fun ThumbnailStrip(
    thumbnailCache: ThumbnailCache,
    currentTime: Double,
    duration: Double,
    activeIndex: Int,
    offset: Float,
    modifier: Modifier = Modifier
) {
    val thumbnailsPerSecond = 12
    val windowSeconds = 10.0 // Show ±10 seconds
    val totalThumbnails = (windowSeconds * 2 * thumbnailsPerSecond).toInt()
    
    // Get thumbnails around current time
    val thumbnails = remember { mutableStateListOf<Thumbnail?>() }
    val coroutineScope = rememberCoroutineScope()
    
    LaunchedEffect(currentTime, activeIndex) {
        val startTime = max(0.0, currentTime - windowSeconds)
        val endTime = min(duration, currentTime + windowSeconds)
        
        val newThumbnails = mutableListOf<Thumbnail?>()
        for (i in 0 until totalThumbnails) {
            val time = startTime + (i.toDouble() / thumbnailsPerSecond)
            if (time <= endTime) {
                // Get thumbnail asynchronously
                val thumbnail = thumbnailCache.getThumbnailAtTime(time)
                newThumbnails.add(thumbnail)
            } else {
                newThumbnails.add(null)
            }
        }
        thumbnails.clear()
        thumbnails.addAll(newThumbnails)
    }
    
    Box(modifier = modifier) {
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(thumbnails) { index, thumbnail ->
                ThumbnailItem(
                    thumbnail = thumbnail,
                    isActive = index == activeIndex,
                    modifier = Modifier
                        .width(100.dp)
                        .height(56.dp)
                )
            }
        }
        
        // Current time indicator
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .align(Alignment.Center)
                .offset(x = with(LocalDensity.current) { offset.toDp() })
                .background(Color.Red)
        )
    }
}

@Composable
fun ThumbnailItem(
    thumbnail: Thumbnail?,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = when (thumbnail?.state) {
                    ThumbnailState.READY -> Color.Transparent
                    ThumbnailState.LOADING -> Color.DarkGray
                    ThumbnailState.GENERATING -> Color.Gray
                    ThumbnailState.ERROR -> Color.Red.copy(alpha = 0.3f)
                    null -> Color.Black
                }
            )
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) Color.Red else Color.Transparent
            )
    ) {
        thumbnail?.bitmap?.asImageBitmap()?.let { imageBitmap ->
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// Thumbnail Cache Implementation
class ThumbnailCache {
    private val thumbnailsPerSecond = 12
    private val windowSeconds = 10.0 // ±10 seconds = 20-second window
    private val thumbnailWidth = 160
    private val thumbnailHeight = 90
    private val totalThumbnails = (windowSeconds * 2 * thumbnailsPerSecond).toInt()
    
    private val cache = mutableMapOf<Int, Thumbnail>()
    private val mutex = Mutex()
    private var generationJob: Job? = null
    private var currentCenterIndex = 0
    private var videoDuration = 0.0
    
    fun initialize(duration: Double) {
        videoDuration = duration
        currentCenterIndex = 0
        cache.clear()
    }
    
    fun startContinuousGeneration(scope: CoroutineScope, initialPosition: Double) {
        generationJob?.cancel()
        generationJob = scope.launch(Dispatchers.IO) {
            updateCenter(initialPosition)
            
            while (isActive) {
                // Generate thumbnails around current center
                generateThumbnailsAroundCenter()
                delay(100) // Adjust generation rate as needed
            }
        }
    }
    
    suspend fun updateCenter(position: Double) {
        mutex.withLock {
            currentCenterIndex = timeToIndex(position)
        }
    }
    
    fun timeToIndex(time: Double): Int {
        return (time * thumbnailsPerSecond).toInt()
    }
    
    fun indexToTime(index: Int): Double {
        return index.toDouble() / thumbnailsPerSecond
    }
    
    suspend fun getThumbnailAtTime(time: Double): Thumbnail? {
        val index = timeToIndex(time)
        return mutex.withLock {
            cache[index] ?: createPlaceholderThumbnail(time, index)
        }
    }
    
    suspend fun ensureThumbnailsAround(time: Double) {
        val centerIndex = timeToIndex(time)
        
        // Generate thumbnails in priority order
        val priorities = listOf(
            centerIndex, // Current position (highest priority)
            centerIndex - 1, centerIndex + 1, // Adjacent frames
            centerIndex - thumbnailsPerSecond, centerIndex + thumbnailsPerSecond, // ±1 second
            centerIndex - thumbnailsPerSecond * 5, centerIndex + thumbnailsPerSecond * 5 // ±5 seconds
        )
        
        priorities.forEach { index ->
            if (index >= 0 && index <= totalThumbnails && !cache.containsKey(index)) {
                generateThumbnailAsync(index)
            }
        }
    }
    
    private suspend fun generateThumbnailsAroundCenter() {
        val center = currentCenterIndex
        
        // Generate thumbnails in expanding circles from center
        for (radius in 0..(thumbnailsPerSecond * 5)) { // Generate up to 5 seconds away
            val indices = listOf(
                center + radius,
                center - radius
            )
            
            indices.forEach { index ->
                if (index >= 0 && index <= totalThumbnails && !cache.containsKey(index)) {
                    generateThumbnailAsync(index)
                }
            }
            
            // Small delay to prevent overwhelming the system
            if (radius % 10 == 0) {
                delay(10)
            }
        }
    }
    
    private fun generateThumbnailAsync(index: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val time = indexToTime(index)
            
            // Skip if beyond video duration
            if (time > videoDuration) return@launch
            
            // Create placeholder first
            val placeholder = createPlaceholderThumbnail(time, index)
            mutex.withLock {
                cache[index] = placeholder
            }
            
            try {
                // Generate actual thumbnail using MPV
                val bitmap = generateThumbnailFromMPV(time)
                
                mutex.withLock {
                    cache[index] = Thumbnail(
                        timestamp = time,
                        bitmap = bitmap,
                        state = ThumbnailState.READY
                    )
                }
            } catch (e: Exception) {
                // Keep placeholder on error
                mutex.withLock {
                    cache[index] = placeholder.copy(state = ThumbnailState.ERROR)
                }
            }
        }
    }
    
    private fun generateThumbnailFromMPV(time: Double): Bitmap? {
        // Use MPV's screenshot capability
        // Note: This is a simplified version. Actual implementation may vary.
        
        // Seek to position
        MPVLib.command("seek", time.toString(), "absolute", "exact")
        
        // Wait a bit for frame to be ready
        Thread.sleep(16) // ~60fps
        
        // Take screenshot
        // Note: MPV Android doesn't have a direct screenshot API in MPVLib
        // This would need to be implemented differently
        
        // For now, return a placeholder bitmap
        return createColorBitmap(
            width = thumbnailWidth,
            height = thumbnailHeight,
            color = when ((time.toInt() / 5) % 4) {
                0 -> android.graphics.Color.RED
                1 -> android.graphics.Color.GREEN
                2 -> android.graphics.Color.BLUE
                else -> android.graphics.Color.YELLOW
            }
        )
    }
    
    private fun createPlaceholderThumbnail(time: Double, index: Int): Thumbnail {
        return Thumbnail(
            timestamp = time,
            bitmap = createColorBitmap(
                width = thumbnailWidth,
                height = thumbnailHeight,
                color = android.graphics.Color.DKGRAY
            ),
            state = ThumbnailState.GENERATING
        )
    }
    
    private fun createColorBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(color)
        
        // Add time text for debugging
        val paint = android.graphics.Paint().apply {
            this.color = android.graphics.Color.WHITE
            textSize = 12f
        }
        val timeText = formatTimeSimple(time)
        canvas.drawText(timeText, 10f, 20f, paint)
        
        return bitmap
    }
    
    fun clear() {
        generationJob?.cancel()
    }
}

// Helper functions
private fun formatTime(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}

private fun formatTimeSimple(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format("%d:%02d", minutes, secs)
}
