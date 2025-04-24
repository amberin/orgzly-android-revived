package com.orgzly.android.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
        tableName = "versioned_rooks",

        foreignKeys = [
            ForeignKey(
                    entity = Rook::class,
                    parentColumns = arrayOf("id"),
                    childColumns = arrayOf("rook_id"),
                    onDelete = ForeignKey.CASCADE),
        ],

        indices = [
            Index("rook_id")
        ]
)
data class VersionedRook(
        @PrimaryKey(autoGenerate = true)
        val id: Long,

        @ColumnInfo(name = "rook_id")
        val rookId: Long,

        @ColumnInfo(name = "rook_revision")
        val rookRevision: String,

        @ColumnInfo(name = "rook_mtime")
        val rookMtime: Long
)