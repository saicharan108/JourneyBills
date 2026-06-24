package com.journeybills.data

import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.first

class JourneyRepository(val dao: JourneyDao) {

    val allFriends: Flow<List<FriendBalanceEntity>> = dao.getAllFriends()
    fun getActivities(limit: Int): Flow<List<RecentActivityEntity>> = dao.getActivities(limit)
    val allTrips: Flow<List<TripEntity>> = dao.getAllTrips()

    val expenses: Flow<List<ExpenseEntity>> = dao.getAllExpenses()

    suspend fun addFriend(name: String, email: String, balance: Double) {
        dao.insertFriend(FriendBalanceEntity(name = name, email = email, balance = balance))
    }
    
    suspend fun updateFriendBalance(friendId: Int, amountDiff: Double) {
        dao.adjustFriendBalance(friendId, amountDiff)
    }

    suspend fun updateFriend(friendId: Int, name: String, email: String) {
        dao.updateFriend(friendId, name, email)
    }

    suspend fun addActivity(title: String, description: String, time: String, iconName: String, tripId: Int? = null) {
        dao.insertActivity(RecentActivityEntity(title = title, description = description, time = time, iconName = iconName, tripId = tripId))
    }
    
    suspend fun addTrip(name: String, currency: String = "USD") {
        dao.insertTrip(TripEntity(name = name, currency = currency))
    }
    
    suspend fun insertTag(tag: TagEntity) {
        dao.insertTag(tag)
    }

    suspend fun addExpense(expense: ExpenseEntity) {
        dao.insertExpense(expense)
    }

    suspend fun updateTripParticipants(tripId: Int, participantIds: String) {
        dao.updateTripParticipants(tripId, participantIds)
    }

    suspend fun deleteFriend(friendId: Int) {
        dao.deleteFriend(friendId)
    }

    suspend fun deleteTrip(tripId: Int) {
        dao.deleteTrip(tripId)
    }

    suspend fun deleteExpense(expenseId: Int) {
        dao.deleteExpense(expenseId)
    }

    suspend fun initializeMockDataIfNeeded() {
        // No mock data needed
    }
}
