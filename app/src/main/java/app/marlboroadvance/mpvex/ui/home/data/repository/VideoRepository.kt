package app.marlboroadvance.mpvex.ui.home.data.repository

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import app.marlboroadvance.mpvex.ui.home.data.model.Video
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object VideoRepository {

  private const val tag = "VideoRepository"

  fun getVideosInFolder(context: Context, bucketId: String): List<Video> {
    Log.d(tag, "Starting to query videos for bucket: $bucketId")
    val videos = mutableListOf<Video>()

    val projection = getProjection()
    val (selection, selectionArgs) = getSelectionArgs(bucketId)
    val sortOrder = "${MediaStore.Video.Media.TITLE} COLLATE NOCASE ASC"

    try {
      val cursor: Cursor? = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder,
      )

      Log.d(tag, "Cursor result: ${cursor?.count ?: 0} videos found")

      cursor?.use { c ->
        processVideoCursor(c, videos)
      }
    } catch (e: Exception) {
      Log.e(tag, "Error querying videos for bucket $bucketId", e)
    }

    Log.d(tag, "Returning ${videos.size} videos")
    return videos
  }

  private fun getProjection(): Array<String> {
    return arrayOf(
      MediaStore.Video.Media._ID,
      MediaStore.Video.Media.TITLE,
      MediaStore.Video.Media.DISPLAY_NAME,
      MediaStore.Video.Media.DATA,
      MediaStore.Video.Media.DURATION,
      MediaStore.Video.Media.SIZE,
      MediaStore.Video.Media.DATE_MODIFIED,
      MediaStore.Video.Media.DATE_ADDED,
      MediaStore.Video.Media.MIME_TYPE,
      MediaStore.Video.Media.BUCKET_ID,
      MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
    )
  }

  private fun getSelectionArgs(bucketId: String): Pair<String, Array<String>?> {
    return if (bucketId == "root_storage") {
      Log.d(tag, "Querying for root storage videos")
      val selection = "${MediaStore.Video.Media.BUCKET_ID} IS NULL OR " +
        "${MediaStore.Video.Media.DATA} LIKE '/storage/emulated/0/%' AND " +
        "${MediaStore.Video.Media.DATA} NOT LIKE '/storage/emulated/0/%/%'"
      Pair(selection, null)
    } else {
      Log.d(tag, "Querying for bucket: $bucketId")
      Pair("${MediaStore.Video.Media.BUCKET_ID} = ?", arrayOf(bucketId))
    }
  }

  private fun processVideoCursor(cursor: Cursor, videos: MutableList<Video>) {
    val columnIndices = getCursorColumnIndices(cursor)

    while (cursor.moveToNext()) {
      extractVideoFromCursor(cursor, columnIndices)?.let { video ->
        videos.add(video)
        Log.d(tag, "Added video: ${video.displayName} at ${video.path}")
      }
    }
  }

  private fun getCursorColumnIndices(cursor: Cursor): VideoColumnIndices {
    return VideoColumnIndices(
      id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID),
      title = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE),
      displayName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME),
      data = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA),
      duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION),
      size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE),
      dateModified = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED),
      dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED),
      mimeType = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE),
      bucketId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID),
      bucketDisplayName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
    )
  }

  private fun extractVideoFromCursor(cursor: Cursor, indices: VideoColumnIndices): Video? {
    val id = cursor.getLong(indices.id)
    val title = cursor.getString(indices.title) ?: ""
    val displayName = cursor.getString(indices.displayName) ?: ""
    val path = cursor.getString(indices.data) ?: ""
    val duration = cursor.getLong(indices.duration)
    val size = cursor.getLong(indices.size)
    val dateModified = cursor.getLong(indices.dateModified)
    val dateAdded = cursor.getLong(indices.dateAdded)
    val mimeType = cursor.getString(indices.mimeType) ?: ""
    val videoBucketId = cursor.getString(indices.bucketId) ?: ""
    val bucketDisplayName = cursor.getString(indices.bucketDisplayName) ?: ""

    val (finalBucketId, finalBucketDisplayName) = getFinalBucketInfo(
      videoBucketId,
      bucketDisplayName,
      path,
    )

    if (path.isEmpty() || !File(path).exists()) {
      Log.w(tag, "Skipping non-existent file: $path")
      return null
    }

    val uri = Uri.withAppendedPath(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
      id.toString(),
    )

    return Video(
      id = id,
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = formatDuration(duration),
      size = size,
      sizeFormatted = formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateAdded,
      mimeType = mimeType,
      bucketId = finalBucketId,
      bucketDisplayName = finalBucketDisplayName,
    )
  }

  private fun getFinalBucketInfo(
    bucketId: String,
    bucketDisplayName: String,
    path: String,
  ): Pair<String, String> {
    val finalBucketId = bucketId.ifEmpty { "root_storage" }
    val finalBucketDisplayName = bucketDisplayName.ifEmpty {
      val isRootStorage = path.startsWith("/storage/emulated/0/") &&
        File(path).parent == "/storage/emulated/0"
      if (isRootStorage) {
        "Internal Storage"
      } else {
        File(path).parent?.let { File(it).name } ?: "Unknown Folder"
      }
    }
    return Pair(finalBucketId, finalBucketDisplayName)
  }

  private data class VideoColumnIndices(
    val id: Int,
    val title: Int,
    val displayName: Int,
    val data: Int,
    val duration: Int,
    val size: Int,
    val dateModified: Int,
    val dateAdded: Int,
    val mimeType: Int,
    val bucketId: Int,
    val bucketDisplayName: Int,
  )

  private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
      hours > 0 -> "${hours}h ${minutes}m ${secs}s"
      minutes > 0 -> "${minutes}m ${secs}s"
      else -> "${secs}s"
    }
  }

  private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()

    return String.format(
      Locale.getDefault(),
      "%.1f %s",
      bytes / 1024.0.pow(digitGroups.toDouble()),
      units[digitGroups],
    )
  }
}
