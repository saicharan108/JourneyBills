package com.journeybills.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val timestamp: Long = System.currentTimeMillis()
)
