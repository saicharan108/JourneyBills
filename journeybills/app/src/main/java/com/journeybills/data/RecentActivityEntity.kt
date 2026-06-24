package com.journeybills.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_activity")
data class RecentActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val time: String,
    val iconName: String,
    val tripId: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
