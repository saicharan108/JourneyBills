package com.journeybills.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val description: String,
    val amount: Double,
    val tripId: Int?,
    val paidById: Int?, // null means 'Me'
    val splitsJson: String, // A JSON string mapping participantId to their share double. "me" means 'Me'.
    val tagId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
