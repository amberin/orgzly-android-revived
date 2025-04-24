package com.orgzly.android.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.orgzly.android.db.entity.Rook

@Dao
abstract class RookDao : BaseDao<Rook> {
    @Query("SELECT * FROM rooks WHERE repo_id = :repoId AND rook_url_id = :rookUrlId")
    abstract fun get(repoId: Long, rookUrlId: Long): Rook?

    fun getOrInsert(repoId: Long, rookUrlId: Long, repoRelativePath: String?): Long =
            get(repoId, rookUrlId).let {
                if (it != null) {
                    if (it.repoRelativePath != null) return it.id
                    // Add repoRelativePath attribute to existing entry when absent
                    replace(Rook(it.id, repoId, rookUrlId, repoRelativePath))
                    return it.id
                }
                // New entry
                return insert(Rook(0, repoId, rookUrlId, repoRelativePath))
            }
}
