package com.orgzly.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
        tableName = "rooks",

        foreignKeys = [
            ForeignKey(
                    entity = Repo::class,
                    parentColumns = arrayOf("id"),
                    childColumns = arrayOf("repo_id"),
                    onDelete = ForeignKey.CASCADE),
            ForeignKey(
                    entity = RookUrl::class,
                    parentColumns = arrayOf("id"),
                    childColumns = arrayOf("rook_url_id"),
                    onDelete = ForeignKey.CASCADE)
        ],

        indices = [
            Index("repo_id", "rook_url_id", "repo_relative_path", unique = true),
            Index("rook_url_id"),
        ]
)
data class Rook(
        @PrimaryKey(autoGenerate = true)
        val id: Long,

        @ColumnInfo(name = "repo_id")
        val repoId: Long,

        @ColumnInfo(name = "rook_url_id")
        val rookUrlId: Long,

        @ColumnInfo(name = "repo_relative_path")
        val repoRelativePath: String?
)