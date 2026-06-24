package com.journeybills.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JourneyDao {
    @Query("SELECT * FROM friend_balance WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllFriends(): Flow<List<FriendBalanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFriend(friend: FriendBalanceEntity): Long
    
    @Query("UPDATE friend_balance SET balance = balance + :amount, lastUpdatedTimestamp = :timestamp WHERE id = :friendId")
    suspend fun adjustFriendBalance(friendId: Int, amount: Double, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE friend_balance SET name = :name, email = :email, lastUpdatedTimestamp = :timestamp WHERE id = :friendId")
    suspend fun updateFriend(friendId: Int, name: String, email: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT * FROM recent_activity ORDER BY timestamp DESC LIMIT :limit")
    fun getActivities(limit: Int): Flow<List<RecentActivityEntity>>

    @Query("SELECT * FROM recent_activity ORDER BY timestamp DESC")
    fun getAllActivities(): Flow<List<RecentActivityEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(activity: RecentActivityEntity): Long
    
    @Query("DELETE FROM friend_balance")
    suspend fun clearFriends()
    
    @Query("DELETE FROM recent_activity")
    suspend fun clearActivities()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long
    
    @Query("SELECT * FROM expenses ORDER BY timestamp DESC")
    fun getAllExpenses(): Flow<List<ExpenseEntity>>
    
    @Query("SELECT * FROM trips WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long
    
    @Query("UPDATE trips SET participantIds = :participantIds, lastUpdatedTimestamp = :timestamp WHERE id = :tripId")
    suspend fun updateTripParticipants(tripId: Int, participantIds: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE friend_balance SET isDeleted = 1, lastUpdatedTimestamp = :timestamp WHERE id = :friendId")
    suspend fun deleteFriend(friendId: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE trips SET isDeleted = 1, lastUpdatedTimestamp = :timestamp WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM expenses WHERE id = :expenseId")
    suspend fun deleteExpense(expenseId: Int)

    @Query("DELETE FROM trips")
    suspend fun clearTrips()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity)

    @Query("SELECT * FROM tags")
    fun getAllTags(): Flow<List<TagEntity>>
}
