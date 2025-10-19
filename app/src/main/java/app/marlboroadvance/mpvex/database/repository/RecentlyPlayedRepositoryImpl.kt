package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.RecentlyPlayedDao
import app.marlboroadvance.mpvex.database.entities.RecentlyPlayedEntity
import app.marlboroadvance.mpvex.domain.recentlyplayed.repository.RecentlyPlayedRepository

class RecentlyPlayedRepositoryImpl(
  private val recentlyPlayedDao: RecentlyPlayedDao,
) : RecentlyPlayedRepository {

  override suspend fun addRecentlyPlayed(filePath: String, fileName: String) {
    val entity = RecentlyPlayedEntity(
      filePath = filePath,
      fileName = fileName,
      timestamp = System.currentTimeMillis(),
    )
    recentlyPlayedDao.insert(entity)
  }

  override suspend fun getLastPlayed(): RecentlyPlayedEntity? {
    return recentlyPlayedDao.getLastPlayed()
  }

  override suspend fun getRecentlyPlayed(limit: Int): List<RecentlyPlayedEntity> {
    return recentlyPlayedDao.getRecentlyPlayed(limit)
  }

  override suspend fun clearAll() {
    recentlyPlayedDao.clearAll()
  }
}
