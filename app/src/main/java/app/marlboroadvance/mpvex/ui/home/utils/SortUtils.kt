package app.marlboroadvance.mpvex.ui.home.utils

import app.marlboroadvance.mpvex.ui.home.data.model.Video
import app.marlboroadvance.mpvex.ui.home.data.model.VideoFolder

object SortUtils {

  fun sortVideos(
    videos: List<Video>,
    sortType: String,
    sortOrderAsc: Boolean,
  ): List<Video> {
    val sorted = when (sortType) {
      "Title" -> videos.sortedBy { it.displayName.lowercase() }
      "Duration" -> videos.sortedBy { it.duration }
      "Date" -> videos.sortedBy { it.dateAdded }
      "Size" -> videos.sortedBy { it.size }
      else -> videos
    }
    return if (sortOrderAsc) sorted else sorted.reversed()
  }

  fun sortFolders(
    folders: List<VideoFolder>,
    sortType: String,
    sortOrderAsc: Boolean,
  ): List<VideoFolder> {
    val sorted = when (sortType) {
      "Title" -> folders.sortedBy { it.name.lowercase() }
      "Date" -> folders.sortedBy { it.lastModified }
      "Size" -> folders.sortedBy { it.totalSize }
      else -> folders
    }
    return if (sortOrderAsc) sorted else sorted.reversed()
  }
}
