package com.journeybills.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val participantIds: String = "",
    val isDeleted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val currency: String = "USD",
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
