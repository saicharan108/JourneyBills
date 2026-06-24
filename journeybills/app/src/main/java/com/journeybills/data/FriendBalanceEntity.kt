package com.journeybills.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friend_balance")
data class FriendBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val balance: Double,
    val email: String,
    val isDeleted: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)
