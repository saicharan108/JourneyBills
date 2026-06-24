package com.journeybills.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.journeybills.data.DriveSyncManager
import com.journeybills.data.FriendBalanceEntity
import com.journeybills.data.JourneyDatabase
import com.journeybills.data.JourneyRepository
import com.journeybills.data.RecentActivityEntity
import com.journeybills.data.TripEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.flatMapLatest

class JourneyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: JourneyRepository
    private val prefs = application.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
    val syncManager: DriveSyncManager

    private val _userName = MutableStateFlow<String?>(prefs.getString("user_name", null))
    val userName: StateFlow<String?> = _userName

    private val _isDarkTheme = MutableStateFlow<Boolean>(prefs.getBoolean("is_dark_theme", false))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme

    fun toggleTheme(isDark: Boolean) {
        prefs.edit().putBoolean("is_dark_theme", isDark).apply()
        _isDarkTheme.value = isDark
    }

    fun getTripSyncFileId(tripId: Int): String? {
        return prefs.getString("trip_sync_file_$tripId", null)
    }

    fun getTripSyncLastTime(tripId: Int): String? {
        return prefs.getString("trip_sync_last_time_$tripId", null)
    }

    fun getTripSyncLastTimestamp(tripId: Int): Long {
        return prefs.getLong("trip_sync_last_timestamp_$tripId", 0L)
    }

    fun markTripLocallyUpdated(tripId: Int?) {
        if (tripId == null) return
        prefs.edit().putLong("trip_last_local_update_time_$tripId", System.currentTimeMillis()).apply()
    }

    fun hasUnsyncedChanges(tripId: Int): Boolean {
        val lastSync = prefs.getLong("trip_sync_last_timestamp_$tripId", 0L)
        val lastUpdate = prefs.getLong("trip_last_local_update_time_$tripId", 0L)
        return lastUpdate > lastSync
    }

    val friends: StateFlow<List<FriendBalanceEntity>>
    private val _activityLimit = MutableStateFlow(20)
    val activities: StateFlow<List<RecentActivityEntity>>

    fun loadMoreActivities() {
        _activityLimit.value += 20
    }
    val trips: StateFlow<List<TripEntity>>
    val expenses: StateFlow<List<com.journeybills.data.ExpenseEntity>>
    
    val tags: StateFlow<List<com.journeybills.data.TagEntity>>

    private val _syncTrigger = MutableStateFlow(0L)
    val syncTrigger: StateFlow<Long> = _syncTrigger

    private val _driveDeletedEvents = kotlinx.coroutines.flow.MutableSharedFlow<Int>()
    val driveDeletedEvents = _driveDeletedEvents.asSharedFlow()

    fun triggerDriveDeletedEvent(tripId: Int) {
        viewModelScope.launch {
            _driveDeletedEvents.emit(tripId)
        }
    }

    init {
        val dao = JourneyDatabase.getDatabase(application).dao()
        repository = JourneyRepository(dao)
        syncManager = DriveSyncManager(application, dao)

        activities = _activityLimit.flatMapLatest { limit -> repository.getActivities(limit) }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        
        trips = repository.allTrips.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        
        tags = dao.getAllTags().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        
        val rawExpenses = repository.expenses
        val rawFriends = repository.allFriends
        val rawTrips = repository.allTrips

        expenses = combine(rawExpenses, rawTrips, _syncTrigger) { eList, tList, _ ->
            val activeTripIds = tList.map { it.id }.toSet()
            val activeExpenses = eList.filter { it.tripId == null || activeTripIds.contains(it.tripId) }
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter<Map<String, Double>>(com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, java.lang.Double::class.java))
            
            activeExpenses.map { exp ->
                val mapId = exp.tripId?.let { getMappedMyFriendId(it) }
                if (mapId != null) {
                    val newPaidById = when (exp.paidById) {
                        null -> mapId
                        mapId -> null
                        else -> exp.paidById
                    }
                    val splits = adapter.fromJson(exp.splitsJson) ?: emptyMap()
                    val newSplits = mutableMapOf<String, Double>()
                    for ((k, v) in splits) {
                        val newK = when (k) {
                            "me" -> mapId.toString()
                            mapId.toString() -> "me"
                            else -> k
                        }
                        newSplits[newK] = v
                    }
                    exp.copy(paidById = newPaidById, splitsJson = adapter.toJson(newSplits))
                } else {
                    exp
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        friends = combine(rawFriends, expenses) { fList, transformedEList ->
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter<Map<String, Double>>(com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, java.lang.Double::class.java))
            
            // Recompute balances locally from transformed expenses
            val computedBalances = mutableMapOf<Int, Double>()
            fList.forEach { computedBalances[it.id] = 0.0 }

            for (exp in transformedEList) {
                // Determine splits
                val splits = adapter.fromJson(exp.splitsJson) ?: emptyMap()
                val totalAmount = exp.amount
                
                // If paid by "Me" (null)
                if (exp.paidById == null) {
                    for ((k, v) in splits) {
                        if (k != "me") {
                            val fId = k.toIntOrNull()
                            if (fId != null) {
                                computedBalances[fId] = (computedBalances[fId] ?: 0.0) + v
                            }
                        }
                    }
                } else {
                    val payerId = exp.paidById
                    val mySplit = splits["me"] ?: 0.0
                    if (mySplit > 0.0) {
                        computedBalances[payerId] = (computedBalances[payerId] ?: 0.0) - mySplit
                    }
                }
            }

            fList.map { f ->
                // Rename mapped friends to original owner if needed
                // If this friend ID is used as 'myIdentity' in ANY trip, we rename it to identify the owner
                var finalName = f.name
                val mappedTripIds = getTripsWhereFriendIsMapped(f.id)
                if (mappedTripIds.isNotEmpty()) {
                    val emails = mappedTripIds.mapNotNull { prefs.getString("trip_sync_account_email_$it", null) }
                    if (emails.isNotEmpty()) {
                        finalName = "Owner (${emails.first().split("@")[0]})"
                    } else {
                        finalName = "Trip Owner"
                    }
                }
                f.copy(name = finalName, balance = computedBalances[f.id] ?: 0.0)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun getTripsWhereFriendIsMapped(friendId: Int): List<Int> {
        return prefs.all.filter { it.key.startsWith("trip_my_identity_") && it.value == friendId }
            .mapNotNull { it.key.removePrefix("trip_my_identity_").toIntOrNull() }
    }

    fun refreshSyncState() {
        _syncTrigger.value = System.currentTimeMillis()
    }

    fun setMappedMyFriendId(tripId: Int, friendId: Int) {
        prefs.edit().putInt("trip_my_identity_$tripId", friendId).apply()
    }

    fun getMappedMyFriendId(tripId: Int): Int? {
        val id = prefs.getInt("trip_my_identity_$tripId", -1)
        return if (id == -1) null else id
    }

    suspend fun shareFolderWithUser(account: GoogleSignInAccount, folderId: String, email: String) {
        syncManager.shareFolderWithUser(account, folderId, email)
    }

    suspend fun listFolderPermissions(account: GoogleSignInAccount, folderId: String) = syncManager.listFolderPermissions(account, folderId)

    suspend fun removeFolderAccess(account: GoogleSignInAccount, folderId: String, permissionId: String) {
        syncManager.removeFolderAccess(account, folderId, permissionId)
    }

    suspend fun syncTripToDrive(account: GoogleSignInAccount, tripId: Int): String? {
        val fileIdStr = prefs.getString("trip_sync_file_$tripId", null)
        val defaultOwner = _userName.value ?: account.displayName ?: "Me"
        val tripOwnerName = prefs.getString("trip_owner_name_$tripId", defaultOwner)
        try {
            val result = syncManager.syncTripToDrive(account, tripId, tripOwnerName, fileIdStr)
            if (result != null) {
                val (resultFileId, resultFolderId) = result
                val now = java.text.SimpleDateFormat("MMM dd, yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                val timestamp = System.currentTimeMillis()
                val email = account.email ?: "Unknown Account"
                prefs.edit()
                    .putString("trip_sync_file_$tripId", resultFileId)
                    .putString("trip_sync_folder_$tripId", resultFolderId)
                    .putString("trip_sync_last_time_$tripId", now)
                    .putLong("trip_sync_last_timestamp_$tripId", timestamp)
                    .putString("trip_sync_account_email_$tripId", email)
                    .remove("trip_drive_file_deleted_$tripId")
                    .apply()
                refreshSyncState()
                return resultFileId
            }
            return null
        } catch (e: com.journeybills.data.DriveFileDeletedException) {
            prefs.edit().putBoolean("trip_drive_file_deleted_$tripId", true).apply()
            refreshSyncState()
            throw e
        }
    }

    suspend fun listBackupsFromDrive(account: GoogleSignInAccount): List<Pair<String, String>> {
        return syncManager.listBackupsFromDrive(account)
    }

    suspend fun downloadSnapshot(account: GoogleSignInAccount, fileId: String): com.journeybills.data.TripDatabaseSnapshot? {
        val existingTrips = trips.value
        val alreadyImported = existingTrips.find { prefs.getString("trip_sync_file_${it.id}", null) == fileId }
        if (alreadyImported != null) {
            throw Exception("Trip already exists on this device as '${alreadyImported.name}'")
        }
        return syncManager.downloadSnapshot(account, fileId)
    }

    suspend fun importTripSnapshotWithMe(
        account: GoogleSignInAccount,
        fileId: String,
        snapshot: com.journeybills.data.TripDatabaseSnapshot,
        resolvedSameNameIds: Set<Int> = emptySet(),
        renamedCloudFriends: Map<Int, String> = emptyMap()
    ): Int? {
        // 1. Get existing friends for naming conflict detection
        val existingFriends = repository.dao.getAllFriends().first().toMutableList()
        val localOwnerName = userName.value.takeIf { !it.isNullOrBlank() } ?: "Me"
        existingFriends.add(com.journeybills.data.FriendBalanceEntity(-2, localOwnerName, 0.0, ""))

        // 2. Map of backup friend ID (Int) -> local friend ID (Int)
        val friendIdMap = mutableMapOf<Int, Int>()

        // Find the owner details from GoogleSignInAccount
        val ownerName = renamedCloudFriends[-1] ?: snapshot.ownerName ?: account.displayName ?: "Trip Owner"

        // 3. For each friend in the snapshot, insert them (handling conflicts)
        val cloudOwnerEntity = com.journeybills.data.FriendBalanceEntity(-1, ownerName, 0.0, account.email ?: "")
        val allSnapshotActors = snapshot.friends + cloudOwnerEntity
        
        allSnapshotActors.forEach { friend ->
            val friendName = renamedCloudFriends[friend.id] ?: friend.name
            val exactMatchFriend = existingFriends.find { 
                (it.name.equals(friendName, ignoreCase = true) && it.email.equals(friend.email, ignoreCase = true)) ||
                (it.name.equals(friendName, ignoreCase = true) && resolvedSameNameIds.contains(it.id))
            }
            if (exactMatchFriend != null) {
                friendIdMap[friend.id] = exactMatchFriend.id
            } else {
                // Check for naming conflict
                var uniqueName = friendName
                var suffix = 1
                while (existingFriends.any { it.name.equals(uniqueName, ignoreCase = true) }) {
                    uniqueName = "$friendName ($suffix)"
                    suffix++
                }
                val newFriend = com.journeybills.data.FriendBalanceEntity(
                    id = 0,
                    name = uniqueName,
                    balance = 0.0,
                    email = friend.email
                )
                val insertedId = repository.dao.insertFriend(newFriend).toInt()
                existingFriends.add(newFriend.copy(id = insertedId))
                friendIdMap[friend.id] = insertedId
            }
        }

        // Helper mapper function (null is preserved as null)
        fun mapId(backupId: Int?): Int? {
            val mapped = if (backupId == null) friendIdMap[-1] else friendIdMap[backupId]
            return if (mapped == -2) null else mapped
        }

        // 4. Create the new Trip Entity with auto-generated ID (id = 0)
        val originalParticipants = snapshot.trip.participantIds.split(",")
            .filter { it.isNotBlank() }
            .map { it.toInt() }

        val newParticipants = mutableListOf<Int>()
        originalParticipants.forEach { backupId ->
            val mapped = friendIdMap[backupId]
            if (mapped != null) {
                newParticipants.add(mapped)
            }
        }

        val newTripEntity = com.journeybills.data.TripEntity(
            id = 0,
            name = snapshot.trip.name,
            participantIds = newParticipants.joinToString(","),
            isDeleted = snapshot.trip.isDeleted,
            timestamp = snapshot.trip.timestamp,
            currency = snapshot.trip.currency
        )

        val insertedTripId = repository.dao.insertTrip(newTripEntity).toInt()
        
        // Save the owner name to shared preferences
        prefs.edit().putString("trip_owner_name_$insertedTripId", ownerName).apply()

        // 5. Insert tags
        snapshot.tags?.forEach { repository.dao.insertTag(it) }

        // 6. Map and insert expenses
        snapshot.expenses.forEach { expense ->
            val mappedPaidById = mapId(expense.paidById)
            val mappedSplits = mutableMapOf<Int?, Double>()

            val updatedSplitsJson = try {
                val originalSplits = org.json.JSONObject(expense.splitsJson)
                val newSplits = org.json.JSONObject()
                val keys = originalSplits.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val originalParticipantId: Int? = if (k == "me") null else k.toInt()
                    val share = originalSplits.getDouble(k)
                    
                    val mappedParticipantId = mapId(originalParticipantId)
                    mappedSplits[mappedParticipantId] = share
                    
                    val newKey = if (mappedParticipantId == null) "me" else mappedParticipantId.toString()
                    newSplits.put(newKey, share)
                }
                newSplits.toString()
            } catch (ex: Exception) {
                ex.printStackTrace()
                expense.splitsJson
            }

            val newExpense = com.journeybills.data.ExpenseEntity(
                id = 0,
                description = expense.description,
                amount = expense.amount,
                tripId = insertedTripId,
                paidById = mappedPaidById,
                splitsJson = updatedSplitsJson,
                tagId = expense.tagId,
                timestamp = expense.timestamp
            )
            repository.dao.insertExpense(newExpense)

            // Adjust global friend balances for the updated splits mapped to this local device
            if (mappedPaidById == null) {
                for ((friendId, splitAmount) in mappedSplits) {
                    if (friendId != null) {
                        repository.updateFriendBalance(friendId, splitAmount)
                    }
                }
            } else {
                val mySplit = mappedSplits[null] ?: 0.0
                if (mySplit > 0) {
                    repository.updateFriendBalance(mappedPaidById, -mySplit)
                }
            }
        }

        // 7. Map and insert Recent Activities
        snapshot.activities.forEach { activity ->
            val newActivity = com.journeybills.data.RecentActivityEntity(
                id = 0,
                title = activity.title,
                description = activity.description,
                time = activity.time,
                iconName = activity.iconName,
                timestamp = activity.timestamp,
                tripId = insertedTripId
            )
            repository.dao.insertActivity(newActivity)
        }

        // 8. Associate Google Drive Sync
        val now = java.text.SimpleDateFormat("MMM dd, yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val timestamp = System.currentTimeMillis()
        val email = account.email ?: "Unknown Account"
        val folderId = syncManager.getParentFolderId(account, fileId) ?: ""
        prefs.edit()
            .putString("trip_sync_file_$insertedTripId", fileId)
            .putString("trip_sync_folder_$insertedTripId", folderId)
            .putString("trip_sync_last_time_$insertedTripId", now)
            .putLong("trip_sync_last_timestamp_$insertedTripId", timestamp)
            .putString("trip_sync_account_email_$insertedTripId", email)
            .remove("trip_drive_file_deleted_$insertedTripId")
            .apply()

        refreshSyncState()
        return insertedTripId
    }

    suspend fun fetchTripFromDrive(account: GoogleSignInAccount, fileId: String): Int? {
        val existingTrips = trips.value
        val alreadyImported = existingTrips.find { prefs.getString("trip_sync_file_${it.id}", null) == fileId }
        if (alreadyImported != null) {
            throw Exception("Trip already exists on this device as '${alreadyImported.name}'")
        }
        val tripId = syncManager.fetchTripFromDrive(account, fileId)
        if (tripId != null) {
            val now = java.text.SimpleDateFormat("MMM dd, yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
            val timestamp = System.currentTimeMillis()
            val email = account.email ?: "Unknown Account"
            val folderId = syncManager.getParentFolderId(account, fileId) ?: ""
            prefs.edit()
                .putString("trip_sync_file_$tripId", fileId)
                .putString("trip_sync_folder_$tripId", folderId)
                .putString("trip_sync_last_time_$tripId", now)
                .putLong("trip_sync_last_timestamp_$tripId", timestamp)
                .putString("trip_sync_account_email_$tripId", email)
                .remove("trip_drive_file_deleted_$tripId")
                .apply()
            refreshSyncState()
            return tripId
        }
        return null
    }

    suspend fun checkDriveBackupExists(account: GoogleSignInAccount, tripId: Int): Boolean {
        val fileId = getTripSyncFileId(tripId) ?: return true
        val exists = syncManager.checkGoogleDriveFileExists(account, fileId)
        if (!exists) {
            prefs.edit().putBoolean("trip_drive_file_deleted_$tripId", true).apply()
        } else {
            prefs.edit().remove("trip_drive_file_deleted_$tripId").apply()
        }
        refreshSyncState()
        return exists
    }

    suspend fun deleteTripBackup(account: GoogleSignInAccount, tripId: Int): Boolean {
        val fileId = prefs.getString("trip_sync_file_$tripId", null) ?: return false
        val folderId = prefs.getString("trip_sync_folder_$tripId", null)
        val success = syncManager.deleteTripBackup(account, fileId, folderId)
        removeSyncAssociation(tripId)
        return success
    }

    fun removeSyncAssociation(tripId: Int) {
        prefs.edit()
            .remove("trip_sync_file_$tripId")
            .remove("trip_sync_folder_$tripId")
            .remove("trip_sync_last_time_$tripId")
            .remove("trip_sync_last_timestamp_$tripId")
            .remove("trip_last_local_update_time_$tripId")
            .remove("trip_drive_file_deleted_$tripId")
            .remove("trip_sync_account_email_$tripId")
            .apply()
    }

    fun clearDriveDeletedFlag(tripId: Int) {
        prefs.edit().remove("trip_drive_file_deleted_$tripId").apply()
    }

    fun isDriveFileDeletedFlagSelected(tripId: Int): Boolean {
        return prefs.getBoolean("trip_drive_file_deleted_$tripId", false)
    }

    fun getTripSyncFolderId(tripId: Int): String? {
        return prefs.getString("trip_sync_folder_$tripId", null)
    }

    fun getTripSyncAccountEmail(tripId: Int): String? {
        return prefs.getString("trip_sync_account_email_$tripId", null)
    }

    fun shareTripSyncLink(fileId: String) {
        syncManager.shareLinkViaEmail(fileId)
    }

    fun saveUserName(name: String) {
        prefs.edit().putString("user_name", name).apply()
        _userName.value = name
    }
    
    fun addFriend(name: String, email: String) {
        viewModelScope.launch {
            repository.addFriend(name, email, 0.0)
            repository.addActivity(
                title = "Added friend: $name",
                description = if (email.isNotBlank()) email else "No email provided",
                time = "Just now",
                iconName = "PersonAdd"
            )
        }
    }
    
    fun updateFriend(friendId: Int, name: String, email: String) {
        viewModelScope.launch {
            repository.updateFriend(friendId, name, email)
            repository.addActivity(
                title = "Updated friend: $name",
                description = "Details modified",
                time = "Just now",
                iconName = "Edit"
            )
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(getApplication())
            if (account != null) {
                val allTrips = trips.value
                allTrips.forEach { trip ->
                    val fileId = getTripSyncFileId(trip.id)
                    if (fileId != null && trip.participantIds.split(",").filter{it.isNotBlank()}.map { it.toInt() }.contains(friendId)) {
                        markTripLocallyUpdated(trip.id)
                        try {
                            syncTripToDrive(account, trip.id)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
    
    fun addTrip(name: String, currency: String = "USD") {
        viewModelScope.launch {
            repository.addTrip(name, currency)
            repository.addActivity(
                title = "Created trip: $name",
                description = "New trip started (${currency})",
                time = "Just now",
                iconName = "FlightTakeoff"
            )
        }
    }

    fun addFriendsToTrip(tripId: Int, friendIds: List<Int>) {
        if (friendIds.isEmpty()) return
        val trip = trips.value.find { it.id == tripId } ?: return
        val participants = trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toMutableSet()
        participants.addAll(friendIds)
        val namesStr = friendIds.mapNotNull { id -> friends.value.find { it.id == id }?.name }.joinToString(", ")
        viewModelScope.launch {
            repository.updateTripParticipants(tripId, participants.joinToString(","))
            repository.addActivity(
                title = "Added $namesStr to ${trip.name}",
                description = "Trip participants updated",
                time = "Just now",
                iconName = "PersonAdd",
                tripId = tripId
            )
            markTripLocallyUpdated(tripId)
        }
    }

    fun addFriendToTrip(tripId: Int, friendId: Int) {
        val trip = trips.value.find { it.id == tripId } ?: return
        val friendName = friends.value.find { it.id == friendId }?.name ?: "Friend"
        val participants = trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toMutableSet()
        participants.add(friendId)
        viewModelScope.launch {
            repository.updateTripParticipants(tripId, participants.joinToString(","))
            repository.addActivity(
                title = "Added $friendName to ${trip.name}",
                description = "Trip participants updated",
                time = "Just now",
                iconName = "PersonAdd",
                tripId = tripId
            )
            markTripLocallyUpdated(tripId)
        }
    }

    fun removeFriendFromTrip(tripId: Int, friendId: Int) {
        val trip = trips.value.find { it.id == tripId } ?: return
        val friendName = friends.value.find { it.id == friendId }?.name ?: "Friend"
        val participants = trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toMutableSet()
        participants.remove(friendId)
        viewModelScope.launch {
            repository.updateTripParticipants(tripId, participants.joinToString(","))
            repository.addActivity(
                title = "Removed $friendName from ${trip.name}",
                description = "Trip participants updated",
                time = "Just now",
                iconName = "PersonRemove",
                tripId = tripId
            )
            markTripLocallyUpdated(tripId)
        }
    }

    fun deleteTrip(tripId: Int) {
        val trip = trips.value.find { it.id == tripId }
        viewModelScope.launch {
            repository.deleteTrip(tripId)
            trip?.let {
                repository.addActivity(
                    title = "Deleted trip: ${it.name}",
                    description = "Trip and its details are hidden",
                    time = "Just now",
                    iconName = "Delete"
                )
            }
        }
    }

    fun deleteFriend(friendId: Int) {
        val friend = friends.value.find { it.id == friendId }
        viewModelScope.launch {
            repository.deleteFriend(friendId)
            friend?.let {
                repository.addActivity(
                    title = "Deleted friend: ${it.name}",
                    description = "Friend is hidden from lists",
                    time = "Just now",
                    iconName = "Delete"
                )
            }
        }
    }

    fun addExpense(description: String, amount: Double, tripId: Int?, paidById: Int?, splits: Map<Int?, Double>, tagId: String? = null, timestamp: Long = System.currentTimeMillis()) {
        if (amount <= 0 || splits.isEmpty()) return
        
        viewModelScope.launch {
            // Update simple balances based on who paid and who owes me / who I owe
            if (paidById == null) {
                // I paid. Others owe me their splits.
                for ((friendId, splitAmount) in splits) {
                    if (friendId != null) {
                        repository.updateFriendBalance(friendId, splitAmount)
                    }
                }
            } else {
                // Friend paid. I owe them my split.
                val mySplit = splits[null] ?: 0.0
                if (mySplit > 0) {
                    repository.updateFriendBalance(paidById, -mySplit)
                }
            }
            
            // Save expense entity
            val jsonObj = org.json.JSONObject()
            for ((k, v) in splits) {
                jsonObj.put(if (k == null) "me" else k.toString(), v)
            }
            val expenseEntity = com.journeybills.data.ExpenseEntity(
                description = description,
                amount = amount,
                tripId = tripId,
                paidById = paidById,
                splitsJson = jsonObj.toString(),
                tagId = tagId,
                timestamp = timestamp
            )
            repository.addExpense(expenseEntity)
            
            val trip = if (tripId != null) trips.value.find { it.id == tripId } else null
            val tripName = trip?.name
            val symbol = if (trip != null) com.journeybills.getCurrencySymbol(trip.currency) else "$"
            val titleStr = if (tripName != null) "$description ($tripName)" else description
            
            repository.addActivity(
                title = titleStr,
                description = if (paidById == null) "You paid $symbol${String.format("%.2f", amount)}" else "Friend paid $symbol${String.format("%.2f", amount)}",
                time = "Just now",
                iconName = "ReceiptLong",
                tripId = tripId
            )

            if (tripId != null) {
                markTripLocallyUpdated(tripId)
                val fileId = prefs.getString("trip_sync_file_$tripId", null)
                syncManager.autoSyncIfConnected(tripId, fileId, this@JourneyViewModel)
            }
        }
    }

    fun deleteExpense(expense: com.journeybills.data.ExpenseEntity) {
        viewModelScope.launch {
            val splits = mutableMapOf<Int?, Double>()
            try {
                val json = org.json.JSONObject(expense.splitsJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id: Int? = if (k == "me") null else k.toInt()
                    splits[id] = json.getDouble(k)
                }
            } catch (e: Exception) { e.printStackTrace() }

            if (expense.paidById == null) {
                for ((friendId, splitAmount) in splits) {
                    if (friendId != null) {
                        repository.updateFriendBalance(friendId, -splitAmount)
                    }
                }
            } else {
                val mySplit = splits[null] ?: 0.0
                if (mySplit > 0) {
                    repository.updateFriendBalance(expense.paidById, mySplit)
                }
            }

            repository.deleteExpense(expense.id)

            val trip = if (expense.tripId != null) trips.value.find { it.id == expense.tripId } else null
            val tripName = trip?.name
            val symbol = if (trip != null) com.journeybills.getCurrencySymbol(trip.currency) else "$"
            val titleStr = if (tripName != null) "Deleted: ${expense.description} ($tripName)" else "Deleted: ${expense.description}"
            repository.addActivity(
                title = titleStr,
                description = "Expense of $symbol${String.format("%.2f", expense.amount)} was removed",
                time = "Just now",
                iconName = "Delete",
                tripId = expense.tripId
            )

            if (expense.tripId != null) {
                markTripLocallyUpdated(expense.tripId)
                val fileId = prefs.getString("trip_sync_file_${expense.tripId}", null)
                syncManager.autoSyncIfConnected(expense.tripId, fileId, this@JourneyViewModel)
            }
        }
    }

    fun addTag(tag: com.journeybills.data.TagEntity) {
        viewModelScope.launch {
            repository.insertTag(tag)
        }
    }
    
    fun updateExpense(expenseId: Int, description: String, amount: Double, tripId: Int?, paidById: Int?, splits: Map<Int?, Double>, tagId: String? = null, timestamp: Long = System.currentTimeMillis()) {
        if (amount <= 0 || splits.isEmpty()) return
        viewModelScope.launch {
            val allExpenses = expenses.value
            val oldExpense = allExpenses.find { it.id == expenseId }
            if (oldExpense != null) {
                val oldSplits = mutableMapOf<Int?, Double>()
                try {
                    val json = org.json.JSONObject(oldExpense.splitsJson)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val id: Int? = if (k == "me") null else k.toInt()
                        oldSplits[id] = json.getDouble(k)
                    }
                } catch (e: Exception) { e.printStackTrace() }

                if (oldExpense.paidById == null) {
                    for ((friendId, splitAmount) in oldSplits) {
                        if (friendId != null) {
                            repository.updateFriendBalance(friendId, -splitAmount)
                        }
                    }
                } else {
                    val mySplit = oldSplits[null] ?: 0.0
                    if (mySplit > 0) {
                        repository.updateFriendBalance(oldExpense.paidById, mySplit)
                    }
                }
            }

            if (paidById == null) {
                for ((friendId, splitAmount) in splits) {
                    if (friendId != null) {
                        repository.updateFriendBalance(friendId, splitAmount)
                    }
                }
            } else {
                val mySplit = splits[null] ?: 0.0
                if (mySplit > 0) {
                    repository.updateFriendBalance(paidById, -mySplit)
                }
            }

            val jsonObj = org.json.JSONObject()
            for ((k, v) in splits) {
                jsonObj.put(if (k == null) "me" else k.toString(), v)
            }
            val adjustedTimestamp = if (oldExpense != null && timestamp == oldExpense.timestamp) timestamp + 1 else timestamp
            val updatedExpense = com.journeybills.data.ExpenseEntity(
                id = expenseId,
                description = description,
                amount = amount,
                tripId = tripId,
                paidById = paidById,
                splitsJson = jsonObj.toString(),
                tagId = tagId,
                timestamp = adjustedTimestamp
            )
            repository.addExpense(updatedExpense)

            val trip = if (tripId != null) trips.value.find { it.id == tripId } else null
            val tripName = trip?.name
            val symbol = if (trip != null) com.journeybills.getCurrencySymbol(trip.currency) else "$"
            val titleStr = if (tripName != null) "Updated: $description ($tripName)" else "Updated: $description"
            
            repository.addActivity(
                title = titleStr,
                description = if (paidById == null) "You paid $symbol${String.format("%.2f", amount)}" else "Friend paid $symbol${String.format("%.2f", amount)}",
                time = "Just now",
                iconName = "ReceiptLong",
                tripId = tripId
            )

            if (tripId != null) {
                markTripLocallyUpdated(tripId)
                val fileId = prefs.getString("trip_sync_file_$tripId", null)
                syncManager.autoSyncIfConnected(tripId, fileId, this@JourneyViewModel)
            }
        }
    }
}
