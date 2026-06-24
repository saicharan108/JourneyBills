package com.journeybills.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream

class DriveFileDeletedException(val fileId: String) : Exception("Google Drive file not found.")

@JsonClass(generateAdapter = true)
data class TripDatabaseSnapshot(
    val friends: List<FriendBalanceEntity>,
    val trip: TripEntity,
    val expenses: List<ExpenseEntity>,
    val activities: List<RecentActivityEntity>,
    val tags: List<TagEntity>? = null,
    val ownerName: String? = null
)

class DriveSyncManager(private val context: Context, private val dao: JourneyDao) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(TripDatabaseSnapshot::class.java)

    fun getSignInClient(): GoogleSignInClient {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, signInOptions)
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        )
        if (account.account != null) {
            credential.selectedAccount = account.account
        } else if (account.email != null) {
            credential.selectedAccountName = account.email
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("JourneyBills")
            .build()
    }

    suspend fun syncTripToDrive(account: GoogleSignInAccount, tripId: Int, ownerName: String?, existingFileId: String? = null): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            val trips = dao.getAllTrips().first()
            val trip = trips.find { it.id == tripId } ?: return@withContext null
            
            val participantsIds = trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }
            val friends = dao.getAllFriends().first().filter { participantsIds.contains(it.id) }
            val expenses = dao.getAllExpenses().first().filter { it.tripId == tripId }
            val activities = dao.getAllActivities().first().filter { it.tripId == tripId }
            val tags = dao.getAllTags().first()

            val snapshot = TripDatabaseSnapshot(friends, trip, expenses, activities, tags, ownerName)
            val json = EncryptionUtils.encrypt(adapter.toJson(snapshot))
            val fileContent = com.google.api.client.http.ByteArrayContent.fromString("application/json", json)

            var effectiveFileId = existingFileId

            if (effectiveFileId == null) {
                val backups = listBackupsFromDrive(account)
                for (backup in backups) {
                    val candidateFileId = backup.first
                    try {
                        val outputStream = ByteArrayOutputStream()
                        driveService.files().get(candidateFileId).executeMediaAndDownloadTo(outputStream)
                        val candidateJson = EncryptionUtils.decrypt(outputStream.toString("UTF-8"))
                        val candidateSnapshot = adapter.fromJson(candidateJson)
                        if (candidateSnapshot != null && candidateSnapshot.trip.timestamp == trip.timestamp && candidateSnapshot.trip.name == trip.name) {
                            effectiveFileId = candidateFileId
                            break
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }

            if (effectiveFileId != null && effectiveFileId.isNotBlank()) {
                try {
                    val fileMetaData = driveService.files().get(effectiveFileId).setFields("trashed").execute()
                    if (fileMetaData.trashed == true) {
                        Log.w("DriveSyncManager", "Drive file in trash: $effectiveFileId")
                        throw DriveFileDeletedException(effectiveFileId)
                    }

                    driveService.files().update(effectiveFileId, File(), fileContent).execute()
                    Log.d("DriveSyncManager", "Updated existing trip file: $effectiveFileId")
                    
                    // Attempt to resolve parent folder ID
                    var folderId = ""
                    try {
                        val file = driveService.files().get(effectiveFileId).setFields("parents").execute()
                        val parents = file.parents
                        if (!parents.isNullOrEmpty()) {
                            folderId = parents[0]
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return@withContext Pair(effectiveFileId, folderId)
                } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                    if (e.statusCode == 404) {
                        Log.w("DriveSyncManager", "Drive file not found: $effectiveFileId")
                        throw DriveFileDeletedException(effectiveFileId)
                    } else {
                        throw e
                    }
                }
            } else {
                val timestamp = System.currentTimeMillis()
                val folderMetadata = File().apply {
                    name = "JourneyBills - ${trip.name} ($timestamp)"
                    mimeType = "application/vnd.google-apps.folder"
                }
                val folder = driveService.files().create(folderMetadata).setFields("id").execute()
                val folderId = folder.id
                
                friends.forEach { friend ->
                    if (!friend.email.isNullOrBlank()) {
                        try {
                            val permission = com.google.api.services.drive.model.Permission().apply {
                                type = "user"
                                role = "writer"
                                emailAddress = friend.email
                            }
                            driveService.permissions().create(folderId, permission)
                                .setFields("id")
                                .setSendNotificationEmail(false)
                                .execute()
                        } catch (e: Exception) {
                            Log.e("DriveSyncManager", "Failed to auto-share with ${friend.email}", e)
                        }
                    }
                }

                val fileMetadata = File().apply {
                    name = "trip_data.json"
                    mimeType = "application/json"
                    parents = listOf(folderId)
                }
                val createdFile = driveService.files().create(fileMetadata, fileContent).setFields("id").execute()
                Log.d("DriveSyncManager", "Created new trip file: ${createdFile.id}")
                return@withContext Pair(createdFile.id, folderId)
            }
        } catch (e: DriveFileDeletedException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getParentFolderId(account: GoogleSignInAccount, fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            val file = driveService.files().get(fileId).setFields("parents").execute()
            file.parents?.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun checkGoogleDriveFileExists(account: GoogleSignInAccount, fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            val file = driveService.files().get(fileId).setFields("id, trashed").execute()
            if (file.trashed == true) {
                false
            } else {
                true
            }
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            if (e.statusCode == 404) {
                false
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    suspend fun deleteTripBackup(account: GoogleSignInAccount, fileId: String, folderId: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            var deletedFolder = false

            // Try to delete folder folderId directly
            if (!folderId.isNullOrBlank()) {
                try {
                    driveService.files().delete(folderId).execute()
                    Log.d("DriveSyncManager", "Deleted folder directly: $folderId")
                    deletedFolder = true
                } catch (e: Exception) {
                    Log.e("DriveSyncManager", "Failed to delete folder directly $folderId", e)
                }
            }

            // Retrieve parents of the json file, and delete those parents
            val parentsList = mutableListOf<String>()
            try {
                val file = driveService.files().get(fileId).setFields("parents").execute()
                val parents = file.parents
                if (!parents.isNullOrEmpty()) {
                    parentsList.addAll(parents)
                }
            } catch (e: Exception) {
                Log.e("DriveSyncManager", "Failed to get parents for fileId $fileId", e)
            }

            for (parentId in parentsList) {
                if (parentId != folderId) { // avoid deleting twice
                    try {
                        driveService.files().delete(parentId).execute()
                        Log.d("DriveSyncManager", "Deleted folder parentId: $parentId")
                        deletedFolder = true
                    } catch (e: Exception) {
                        Log.e("DriveSyncManager", "Failed to delete parentId $parentId", e)
                    }
                }
            }

            // Also delete the file itself if still existing
            try {
                driveService.files().delete(fileId).execute()
                Log.d("DriveSyncManager", "Deleted file directly: $fileId")
            } catch (e: Exception) {
                // Ignore if already deleted when deleting parent
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            // Make one last attempt on fileId
            try {
                val driveService = getDriveService(account)
                driveService.files().delete(fileId).execute()
                true
            } catch (ex: Exception) {
                ex.printStackTrace()
                false
            }
        }
    }

    suspend fun listBackupsFromDrive(account: GoogleSignInAccount): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            val folderResult = driveService.files().list()
                .setQ("mimeType = 'application/vnd.google-apps.folder' and trashed = false and name contains 'JourneyBills'")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            val backups = mutableListOf<Pair<String, String>>()
            val folders = folderResult.files ?: emptyList()
            for (folder in folders) {
                val fileResult = driveService.files().list()
                    .setQ("'${folder.id}' in parents and name = 'trip_data.json' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()
                
                val tripFile = fileResult.files?.firstOrNull()
                if (tripFile != null) {
                    backups.add(Pair(tripFile.id, folder.name))
                }
            }
            backups
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun downloadSnapshot(account: GoogleSignInAccount, fileId: String): TripDatabaseSnapshot? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            val json = EncryptionUtils.decrypt(outputStream.toString("UTF-8"))
            adapter.fromJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun fetchTripFromDrive(account: GoogleSignInAccount, fileId: String): Int? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            val json = EncryptionUtils.decrypt(outputStream.toString("UTF-8"))
            val snapshot = adapter.fromJson(json)
            
            if (snapshot != null) {
                snapshot.friends.forEach { dao.insertFriend(it) }
                // insertTrip returns the row ID effectively? Wait, Dao insertTrip if it has an id, it might replace. 
                // But we can get the trip ID from snapshot.trip.id
                val tripId = snapshot.trip.id
                dao.insertTrip(snapshot.trip)
                snapshot.expenses.forEach { dao.insertExpense(it) }
                snapshot.activities.forEach { dao.insertActivity(it) }
                snapshot.tags?.forEach { dao.insertTag(it) }
                return@withContext tripId
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun autoSyncAndMerge(account: GoogleSignInAccount, tripId: Int, fileId: String, ownerName: String?): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(account)
            
            // Check if file exists.
            val exists = checkGoogleDriveFileExists(account, fileId)
            if (!exists) {
                throw DriveFileDeletedException("Drive file was deleted")
            }

            // 1. Download/Fetch current file from Drive
            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            val json = EncryptionUtils.decrypt(outputStream.toString("UTF-8"))
            val cloudSnapshot = adapter.fromJson(json)

            if (cloudSnapshot == null) {
                return@withContext syncTripToDrive(account, tripId, fileId)
            }

            // Retrieve last sync timestamp managed locally
            val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
            val lastSync = prefs.getLong("trip_sync_last_timestamp_$tripId", 0L)

            // Retrieve current local tables
            val localTrips = dao.getAllTrips().first()
            val localTrip = localTrips.find { it.id == tripId } ?: return@withContext null

            val originalParticipantsLocal = localTrip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }
            val localFriends = dao.getAllFriends().first().filter { originalParticipantsLocal.contains(it.id) }
            val localExpenses = dao.getAllExpenses().first().filter { it.tripId == tripId }
            val localActivities = dao.getAllActivities().first().filter { it.tripId == tripId }

            // --- MERGING STRATEGY ---
            
            // A. MERGE FRIENDS / PARTICIPANTS (Using Name + Email hash)
            val friendKey = { it: FriendBalanceEntity -> "${it.name}_${it.email}" }
            val localFriendsMap = localFriends.associateBy(friendKey)
            val cloudFriendsMap = cloudSnapshot.friends.associateBy(friendKey)
            
            val friendIdMap = mutableMapOf<Int, Int>()
            val mergedFriends = mutableListOf<FriendBalanceEntity>()
            val allFriendKeys = (localFriendsMap.keys + cloudFriendsMap.keys).distinct()
            
            for (key in allFriendKeys) {
                val localFriend = localFriendsMap[key]
                val cloudFriend = cloudFriendsMap[key]
                
                if (localFriend != null && cloudFriend != null) {
                    friendIdMap[cloudFriend.id] = localFriend.id
                    mergedFriends.add(localFriend)
                } else if (localFriend != null) {
                    mergedFriends.add(localFriend)
                } else if (cloudFriend != null) {
                    val newId = dao.insertFriend(cloudFriend.copy(id = 0)).toInt()
                    friendIdMap[cloudFriend.id] = newId
                    mergedFriends.add(cloudFriend.copy(id = newId))
                }
            }
            
            // B. MERGE TRIP DETAILS
            val originalCloudParticipants = cloudSnapshot.trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }
            val mappedCloudParticipants = originalCloudParticipants.mapNotNull { friendIdMap[it] }.joinToString(",")
            val mappedCloudTrip = cloudSnapshot.trip.copy(id = localTrip.id, participantIds = mappedCloudParticipants)

            val mergedTrip = if (cloudSnapshot.trip.timestamp > localTrip.timestamp) {
                mappedCloudTrip
            } else {
                localTrip
            }
            dao.insertTrip(mergedTrip)

            // C. MERGE EXPENSES (Using Description + Amount + Timestamp hash)
            val expenseKey = { it: ExpenseEntity -> "${it.description}_${it.amount}_${it.timestamp}" }
            val localExpenseMap = localExpenses.associateBy(expenseKey)
            val cloudExpenseMap = cloudSnapshot.expenses.associateBy(expenseKey)
            val allExpenseKeys = (localExpenseMap.keys + cloudExpenseMap.keys).distinct()
            val mergedExpenses = mutableListOf<ExpenseEntity>()

            for (key in allExpenseKeys) {
                val localExpense = localExpenseMap[key]
                val cloudExpense = cloudExpenseMap[key]
                
                val mappedCloudExpense = if (cloudExpense != null) {
                    val mappedPaidById = if (cloudExpense.paidById == null) null else friendIdMap[cloudExpense.paidById]
                    val updatedSplitsJson = try {
                        val originalSplits = org.json.JSONObject(cloudExpense.splitsJson)
                        val newSplits = org.json.JSONObject()
                        val keys = originalSplits.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val originalParticipantId: Int? = if (k == "me") null else k.toInt()
                            val share = originalSplits.getDouble(k)
                            val mappedParticipantId = if (originalParticipantId == null) null else friendIdMap[originalParticipantId]
                            val newKey = if (mappedParticipantId == null) "me" else mappedParticipantId.toString()
                            newSplits.put(newKey, share)
                        }
                        newSplits.toString()
                    } catch(e: Exception) { cloudExpense.splitsJson }
                    
                    cloudExpense.copy(paidById = mappedPaidById, splitsJson = updatedSplitsJson)
                } else null

                if (localExpense != null && mappedCloudExpense != null) {
                    if (mappedCloudExpense.lastUpdatedTimestamp >= localExpense.lastUpdatedTimestamp) {
                        val updated = mappedCloudExpense.copy(id = localExpense.id, tripId = tripId)
                        dao.insertExpense(updated)
                        mergedExpenses.add(updated)
                    } else {
                        dao.insertExpense(localExpense)
                        mergedExpenses.add(localExpense)
                    }
                } else if (localExpense != null) {
                    if (localExpense.lastUpdatedTimestamp > lastSync) {
                        mergedExpenses.add(localExpense)
                    } else {
                        dao.deleteExpense(localExpense.id)
                    }
                } else if (mappedCloudExpense != null) {
                    if (mappedCloudExpense.lastUpdatedTimestamp > lastSync) {
                        val newId = dao.insertExpense(mappedCloudExpense.copy(id = 0, tripId = tripId)).toInt()
                        mergedExpenses.add(mappedCloudExpense.copy(id = newId, tripId = tripId))
                    }
                }
            }

            // D. MERGE RECENT ACTIVITIES
            val activityKey = { it: RecentActivityEntity -> "${it.title}_${it.description}_${it.timestamp}" }
            val localActivityMap = localActivities.associateBy(activityKey)
            val cloudActivityMap = cloudSnapshot.activities.associateBy(activityKey)
            val allActivityKeys = (localActivityMap.keys + cloudActivityMap.keys).distinct()
            val mergedActivities = mutableListOf<RecentActivityEntity>()
            
            for (key in allActivityKeys) {
                val localAct = localActivityMap[key]
                val cloudAct = cloudActivityMap[key]
                
                if (localAct != null && cloudAct != null) {
                    dao.insertActivity(localAct)
                    mergedActivities.add(localAct)
                } else if (localAct != null) {
                    mergedActivities.add(localAct)
                } else if (cloudAct != null) {
                    val newId = dao.insertActivity(cloudAct.copy(id = 0, tripId = tripId)).toInt()
                    mergedActivities.add(cloudAct.copy(id = newId, tripId = tripId))
                }
            }
            
            cloudSnapshot.tags?.forEach { dao.insertTag(it) }

            // 2. Upload the fully merged snapshot back to Google Drive
            val updatedParticipants = mergedTrip.participantIds.split(",").filter { partStr -> partStr.isNotBlank() }.map { partStr -> partStr.toInt() }
            val finalFriends = dao.getAllFriends().first().filter { friend -> updatedParticipants.contains(friend.id) }
            val finalExpenses = dao.getAllExpenses().first().filter { it.tripId == tripId }
            val finalActivities = dao.getAllActivities().first().filter { it.tripId == tripId }
            val finalTags = dao.getAllTags().first()

            val mergedSnapshot = TripDatabaseSnapshot(finalFriends, mergedTrip, finalExpenses, finalActivities, finalTags, ownerName)
            
            val jsonToUpload = EncryptionUtils.encrypt(adapter.toJson(mergedSnapshot))
            val fileContent = com.google.api.client.http.ByteArrayContent.fromString("application/json", jsonToUpload)
            
            driveService.files().update(fileId, File(), fileContent).execute()
            Log.d("DriveSyncManager", "Auto-merged and updated Drive file: $fileId")

            var folderId = ""
            try {
                val file = driveService.files().get(fileId).setFields("parents").execute()
                val parents = file.parents
                if (!parents.isNullOrEmpty()) {
                    folderId = parents[0]
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Pair(fileId, folderId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun shareFolderWithUser(account: GoogleSignInAccount, folderId: String, email: String) = withContext(Dispatchers.IO) {
        val driveService = getDriveService(account)
        val permission = Permission().apply {
            type = "user"
            role = "writer"
            this.emailAddress = email
        }
        driveService.permissions().create(folderId, permission).setSendNotificationEmail(true).execute()
    }

    suspend fun listFolderPermissions(account: GoogleSignInAccount, folderId: String) = withContext(Dispatchers.IO) {
        val driveService = getDriveService(account)
        driveService.permissions().list(folderId).setFields("permissions(id, emailAddress, role, type)").execute().permissions
    }

    suspend fun removeFolderAccess(account: GoogleSignInAccount, folderId: String, permissionId: String) = withContext(Dispatchers.IO) {
        val driveService = getDriveService(account)
        driveService.permissions().delete(folderId, permissionId).execute()
    }

    fun shareLinkViaEmail(fileId: String) {
        val shareUrl = "https://journeybills.app/sync?fileId=$fileId"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join my JourneyBills Trip!")
            putExtra(Intent.EXTRA_TEXT, "Hey! I've shared our trip expenses. Use this link to sync with your JourneyBills app: $shareUrl")
        }
        val chooser = Intent.createChooser(intent, "Share via Email")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    suspend fun autoSyncIfConnected(tripId: Int, existingFileId: String?, viewModel: com.journeybills.ui.JourneyViewModel) {
        if (existingFileId == null) return
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return
        try {
            val result = autoSyncAndMerge(account, tripId, existingFileId, viewModel.userName.value)
            if (result != null) {
                val now = java.text.SimpleDateFormat("MMM dd, yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                val timestamp = System.currentTimeMillis()
                val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("trip_sync_last_time_$tripId", now)
                    .putLong("trip_sync_last_timestamp_$tripId", timestamp)
                    .apply()
                viewModel.refreshSyncState()
            }
        } catch (e: DriveFileDeletedException) {
            val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("trip_drive_file_deleted_$tripId", true).apply()
            viewModel.triggerDriveDeletedEvent(tripId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
