package app.marlboroadvance.mpvex.domain.recentlyplayed.repository

import app.marlboroadvance.mpvex.database.entities.RecentlyPlayedEntity

interface RecentlyPlayedRepository {

  suspend fun addRecentlyPlayed(filePath: String, fileName: String)

  suspend fun getLastPlayed(): RecentlyPlayedEntity?

  suspend fun getRecentlyPlayed(limit: Int = 10): List<RecentlyPlayedEntity>

  suspend fun clearAll()
}
