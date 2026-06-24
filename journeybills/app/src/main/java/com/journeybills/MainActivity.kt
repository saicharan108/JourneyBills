package com.journeybills

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import com.journeybills.ui.theme.JourneyBillsTheme
import com.journeybills.ui.theme.RedNegative
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeybills.ui.JourneyViewModel
import com.journeybills.data.FriendBalanceEntity
import com.journeybills.data.RecentActivityEntity
import com.journeybills.data.TripEntity
import com.journeybills.domain.DebtSimplification
import com.journeybills.domain.NetBalance
import com.journeybills.domain.Settlement

import com.journeybills.ui.theme.CardGreen
import com.journeybills.ui.theme.CardPurple
import com.journeybills.ui.theme.CardYellow
import com.journeybills.ui.theme.CardPink
import com.journeybills.ui.theme.getCardGreen
import com.journeybills.ui.theme.getCardPurple
import com.journeybills.ui.theme.getCardYellow
import com.journeybills.ui.theme.getCardPink
import java.util.Calendar
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState

fun String.toCamelCasePreserveSpaces(): String {
    val result = StringBuilder()
    var capitalizeNext = true
    for (char in this) {
        if (char.isWhitespace()) {
            result.append(char)
            capitalizeNext = true
        } else if (capitalizeNext) {
            result.append(char.uppercaseChar())
            capitalizeNext = false
        } else {
            result.append(char.lowercaseChar())
        }
    }
    return result.toString()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fileIdToSync = intent?.data?.getQueryParameter("fileId")
        setContent {
            val viewModel: JourneyViewModel = viewModel()
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            JourneyBillsTheme(darkTheme = isDarkTheme) {
                JourneyBillsApp(viewModel = viewModel, fileIdToSync = fileIdToSync)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyBillsApp(viewModel: JourneyViewModel, fileIdToSync: String? = null) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showAddTrip by remember { mutableStateOf(false) }
    var showAddFriend by remember { mutableStateOf(false) }
    
    var showRestoreDialog by remember { mutableStateOf(false) }
    var cloudBackups by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingBackups by remember { mutableStateOf(false) }
    
    var showExitDialog by remember { mutableStateOf(false) }
    var selectedAppTripId by remember { mutableStateOf<Int?>(null) }
    var selectedAppFriendId by remember { mutableStateOf<Int?>(null) }
    
    var showSplash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val expenses by viewModel.expenses.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
 
    var isFetchingData by remember { mutableStateOf(fileIdToSync != null) }
    
    var showImportSelectMeDialog by remember { mutableStateOf(false) }
    var importSnapshotToProcess by remember { mutableStateOf<com.journeybills.data.TripDatabaseSnapshot?>(null) }
    var importFileId by remember { mutableStateOf<String?>(null) }
    var importAccount by remember { mutableStateOf<com.google.android.gms.auth.api.signin.GoogleSignInAccount?>(null) }
    var resolvedSameNameIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var ignoredConflictLocalIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var renamedCloudFriendsState by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var importConflictsList by remember { mutableStateOf<List<Pair<com.journeybills.data.FriendBalanceEntity, com.journeybills.data.FriendBalanceEntity>>?>(null) }
    var isImportSaving by remember { mutableStateOf(false) }
    
    val receiveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (!isNetworkAvailable(context)) {
                android.widget.Toast.makeText(context, "Internet is not available. Cannot fetch trip from Drive.", android.widget.Toast.LENGTH_LONG).show()
                isFetchingData = false
                return@rememberLauncherForActivityResult
            }
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                isFetchingData = true
                viewModel.viewModelScope.launch {
                    try {
                        val snapshot = viewModel.downloadSnapshot(account, fileIdToSync!!)
                        isFetchingData = false
                        if (snapshot != null) {
                            importSnapshotToProcess = snapshot
                            importFileId = fileIdToSync
                            importAccount = account
                            showImportSelectMeDialog = true
                        } else {
                            android.widget.Toast.makeText(context, "Could not sync trip.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        isFetchingData = false
                        android.widget.Toast.makeText(context, e.message ?: "Could not sync trip.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isFetchingData = false
            }
        } else {
            isFetchingData = false
        }
    }

    val restoreAuthLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (!isNetworkAvailable(context)) {
                android.widget.Toast.makeText(context, "Internet is not available. Cannot fetch cloud backups.", android.widget.Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                isLoadingBackups = true
                showRestoreDialog = true
                viewModel.viewModelScope.launch {
                    cloudBackups = viewModel.listBackupsFromDrive(account)
                    isLoadingBackups = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val onRestoreAction = {
        if (!isNetworkAvailable(context)) {
            android.widget.Toast.makeText(context, "Internet is not available. Cannot restore from cloud.", android.widget.Toast.LENGTH_LONG).show()
        } else {
            val client = viewModel.syncManager.getSignInClient()
            client.signOut().addOnCompleteListener {
                restoreAuthLauncher.launch(client.signInIntent)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(fileIdToSync) {
        if (fileIdToSync != null) {
            if (!isNetworkAvailable(context)) {
                android.widget.Toast.makeText(context, "Cannot sync trip: You are offline.", android.widget.Toast.LENGTH_SHORT).show()
                isFetchingData = false
                return@LaunchedEffect
            }
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
            if (account != null && com.google.android.gms.auth.api.signin.GoogleSignIn.hasPermissions(account, com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_FILE))) {
                isFetchingData = true
                viewModel.viewModelScope.launch {
                    try {
                        val snapshot = viewModel.downloadSnapshot(account, fileIdToSync)
                        isFetchingData = false
                        if (snapshot != null) {
                            importSnapshotToProcess = snapshot
                            importFileId = fileIdToSync
                            importAccount = account
                            showImportSelectMeDialog = true
                        } else {
                            android.widget.Toast.makeText(context, "Could not sync trip. Check if drive file exists.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        isFetchingData = false
                        android.widget.Toast.makeText(context, e.message ?: "Could not sync trip.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                val client = viewModel.syncManager.getSignInClient()
                client.signOut().addOnCompleteListener {
                    receiveLauncher.launch(client.signInIntent)
                }
            }
        }
    }

    if (isFetchingData) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Syncing Trip Data...", color = MaterialTheme.colorScheme.onBackground)
            }
        }
        return
    }



    if (userName.isNullOrBlank()) {
        NamePromptScreen(onNameSaved = { viewModel.saveUserName(it) })
        return
    }
    
    if (showSplash) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(3000)
            showSplash = false
        }
        SplashScreen(userName = userName!!)
        return
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Exit App",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to exit?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val activity = context as? androidx.activity.ComponentActivity
                    activity?.finish()
                }) {
                    Text("Exit", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        )
    }

    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    val isImeVisible = imeBottom > 0
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            focusManager.clearFocus()
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (isImeVisible) {
            keyboardController?.hide()
            focusManager.clearFocus()
        } else if (selectedAppTripId != null) {
            selectedAppTripId = null
        } else if (selectedAppFriendId != null) {
            selectedAppFriendId = null
        } else {
            showExitDialog = true
        }
    }
    
    if (selectedAppTripId != null) {
        TripDetailScreen(
            tripId = selectedAppTripId!!,
            trips = trips,
            activities = activities,
            friends = friends,
            expenses = expenses,
            onBack = { selectedAppTripId = null },
            viewModel = viewModel
        )
        return
    }

    if (selectedAppFriendId != null) {
        FriendDetailScreen(
            friendId = selectedAppFriendId!!,
            friends = friends,
            trips = trips,
            onBack = { selectedAppFriendId = null },
            viewModel = viewModel
        )
        return
    }

    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    var isSearchVisible by remember { mutableStateOf(false) }
    var globalSearchQuery by remember { mutableStateOf("") }
    var showActivityLogScreen by remember { mutableStateOf(false) }

    if (showActivityLogScreen) {
        ActivityLogScreen(
            activities = activities,
            trips = trips,
            friends = friends,
            onBack = { showActivityLogScreen = false },
            onLoadMore = { viewModel.loadMoreActivities() }
        )
        return
    }

    Scaffold(
        topBar = {
            GreetingHeader(
                userName = userName.orEmpty(),
                isDarkTheme = isDarkTheme,
                onThemeToggle = { viewModel.toggleTheme(!isDarkTheme) },
                onAvatarClick = { showActivityLogScreen = true },
                searchQuery = globalSearchQuery,
                onSearchQueryChange = { globalSearchQuery = it },
                isSearchVisible = isSearchVisible,
                onSearchVisibleChange = {
                    isSearchVisible = it
                    if (!it) globalSearchQuery = "" // clear on close
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showActionSheet = true },
                containerColor = getCardYellow(),
                contentColor = if (isDarkTheme) Color.Black else Color.White,
                shape = RoundedCornerShape(16.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Expense", modifier = Modifier.size(16.dp))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            SummaryCard(friends, trips, expenses)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Tab Bar
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                initialPage = selectedTab,
                pageCount = { 2 }
            )
            androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
                selectedTab = pagerState.currentPage
            }
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
            val currentSelectedTab = pagerState.currentPage

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                TabButton("Trips", currentSelectedTab == 0, modifier = Modifier.weight(1f)) { coroutineScope.launch { pagerState.animateScrollToPage(0) } }
                TabButton("Friends", currentSelectedTab == 1, modifier = Modifier.weight(1f)) { coroutineScope.launch { pagerState.animateScrollToPage(1) } }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            val filteredTrips = remember(trips, globalSearchQuery) {
                if (globalSearchQuery.isBlank()) trips
                else trips.filter { it.name.contains(globalSearchQuery, ignoreCase = true) }
            }
            val filteredFriends = remember(friends, globalSearchQuery) {
                if (globalSearchQuery.isBlank()) friends
                else friends.filter { it.name.contains(globalSearchQuery, ignoreCase = true) }
            }

            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                when (page) {
                    0 -> TripsScreenList(
                        trips = filteredTrips,
                        expenses = expenses,
                        onTripClick = { tripId -> selectedAppTripId = tripId }
                    )
                    1 -> FriendsScreenList(
                        userName = userName,
                        friends = filteredFriends,
                        trips = trips,
                        expenses = expenses,
                        onFriendClick = { friendId -> selectedAppFriendId = friendId }
                    )
                }
            }
        }
    }


    if (showActionSheet) {
        ActionSheet(
            onDismiss = { showActionSheet = false },
            onAddTrip = { showAddTrip = true },
            onAddFriend = { showAddFriend = true },
            onRestoreFromCloud = { onRestoreAction() }
        )
    }

    if (showRestoreDialog) {
        val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Restore Trip from Cloud", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                if (isLoadingBackups) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (cloudBackups.isEmpty()) {
                    Text("No backups found in Google Drive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxHeight(0.5f)) {
                        items(cloudBackups) { backup ->
                            androidx.compose.material3.ListItem(
                                modifier = Modifier.clickable {
                                    if (!isNetworkAvailable(context)) {
                                        android.widget.Toast.makeText(context, "Internet is not available. Cannot restore backup. Please check your connection and try again.", android.widget.Toast.LENGTH_LONG).show()
                                    } else {
                                        showRestoreDialog = false
                                        isFetchingData = true
                                        viewModel.viewModelScope.launch {
                                            try {
                                                val snapshot = viewModel.downloadSnapshot(account!!, backup.first)
                                                isFetchingData = false
                                                if (snapshot != null) {
                                                    importSnapshotToProcess = snapshot
                                                    importFileId = backup.first
                                                    importAccount = account
                                                    showImportSelectMeDialog = true
                                                } else {
                                                    android.widget.Toast.makeText(context, "Could not restore trip.", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                isFetchingData = false
                                                android.widget.Toast.makeText(context, e.message ?: "Could not restore trip.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                headlineContent = { Text(backup.second, color = MaterialTheme.colorScheme.onSurface) },
                                leadingContent = {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = "Restore", tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    if (showImportSelectMeDialog && importSnapshotToProcess != null && importFileId != null && importAccount != null) {
        val snapshot = importSnapshotToProcess!!
        val fileId = importFileId!!
        val account = importAccount!!
        
        var isSaving by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { 
                if (!isSaving) {
                    showImportSelectMeDialog = false
                    importSnapshotToProcess = null
                    importFileId = null
                    importAccount = null
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Import Trip",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    "Do you want to import the trip '${snapshot.trip.name}'?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        val ownerName = renamedCloudFriendsState[-1] ?: snapshot.ownerName ?: account.displayName ?: "Trip Owner"
                        
                        val snapshotActors = mutableListOf<com.journeybills.data.FriendBalanceEntity>()
                        // The owner of the backup will be imported as a friend
                        snapshotActors.add(com.journeybills.data.FriendBalanceEntity(-1, ownerName, 0.0, account.email ?: ""))
                        // All friends in the backup will be imported
                        snapshot.friends.forEach { f ->
                            snapshotActors.add(f)
                        }
                        
                        val localOwnerName = if (userName.isNullOrBlank()) "Me" else userName!!
                        val localOwnerEntity = com.journeybills.data.FriendBalanceEntity(-2, localOwnerName, 0.0, "")
                        val allLocalFriends = friends + localOwnerEntity
                        
                        val conflicts = snapshotActors.mapNotNull { fs ->
                            val local = allLocalFriends.find { it.name.equals(fs.name, ignoreCase=true) }
                            if (local != null && !resolvedSameNameIds.contains(local.id) && !ignoredConflictLocalIds.contains(local.id)) {
                                Pair(fs, local)
                            } else null
                        }
                        
                        if (conflicts.isNotEmpty()) {
                            importConflictsList = conflicts
                            showImportSelectMeDialog = false
                        } else {
                            isSaving = true
                            viewModel.viewModelScope.launch {
                                try {
                                    val tripId = viewModel.importTripSnapshotWithMe(
                                        account = account,
                                        fileId = fileId,
                                        snapshot = snapshot,
                                        resolvedSameNameIds = resolvedSameNameIds,
                                        renamedCloudFriends = renamedCloudFriendsState
                                    )
                                    if (tripId != null) {
                                        android.widget.Toast.makeText(context, "Trip synced and imported successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                        selectedAppTripId = tripId
                                    } else {
                                        android.widget.Toast.makeText(context, "Could not import trip.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, e.message ?: "An error occurred during import.", android.widget.Toast.LENGTH_LONG).show()
                                } finally {
                                    isSaving = false
                                    showImportSelectMeDialog = false
                                    importSnapshotToProcess = null
                                    importFileId = null
                                    importAccount = null
                                }
                            }
                        }
                    }
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Import", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        showImportSelectMeDialog = false
                        importSnapshotToProcess = null
                        importFileId = null
                        importAccount = null
                    }
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    if (importConflictsList != null) {
        val conflicts = importConflictsList!!
        var choices by remember(conflicts) { 
            mutableStateOf(conflicts.associate { it.first.id to true })
        }
        var cloudNames by remember(conflicts) {
            mutableStateOf(conflicts.associate { it.first.id to it.first.name })
        }
        var nameErrors by remember { mutableStateOf<Set<Int>>(emptySet()) }
        
        AlertDialog(
            onDismissRequest = { 
                if (!isImportSaving) {
                    importConflictsList = null
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Resolve Friend Names", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    Text(
                        "Some imported friends have the same names as your existing friends.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(conflicts.size) { index ->
                            val conflict = conflicts[index]
                            val cloudFriend = conflict.first
                            val local = conflict.second
                            val isMerge = choices[cloudFriend.id] ?: true
                            val hasError = nameErrors.contains(cloudFriend.id)
                            
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val titleText = if (cloudFriend.id == -1) "Trip Owner '${cloudFriend.name}'" else "Friend '${cloudFriend.name}'"
                                    Text(
                                        titleText,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { choices = choices.toMutableMap().apply { put(cloudFriend.id, true) } }) {
                                        RadioButton(
                                            selected = isMerge,
                                            onClick = null
                                        )
                                        Text("Merge with existing '${local.name}'", modifier = Modifier.padding(start = 8.dp))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { choices = choices.toMutableMap().apply { put(cloudFriend.id, false) } }) {
                                        RadioButton(
                                            selected = !isMerge,
                                            onClick = null
                                        )
                                        Text("Keep separate (Rename cloud friend)", modifier = Modifier.padding(start = 8.dp))
                                    }
                                    if (!isMerge) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        androidx.compose.material3.OutlinedTextField(
                                            value = cloudNames[cloudFriend.id] ?: "",
                                            onValueChange = { newName ->
                                                cloudNames = cloudNames.toMutableMap().apply { put(cloudFriend.id, newName) }
                                                nameErrors = nameErrors - cloudFriend.id
                                            },
                                            label = { Text("New Name") },
                                            singleLine = true,
                                            isError = hasError,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (hasError) {
                                            Text(
                                                "Name must be different to keep separate.",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isImportSaving,
                    onClick = {
                    val toMergeCloudIds = choices.filterValues { it }.keys
                    val toIgnoreCloudIds = choices.filterValues { !it }.keys
                    
                    val currentErrors = mutableSetOf<Int>()
                    toIgnoreCloudIds.forEach { cloudId ->
                        val localFriend = conflicts.find { it.first.id == cloudId }?.second
                        val newName = cloudNames[cloudId]?.trim() ?: ""
                        if (localFriend != null && newName.equals(localFriend.name, ignoreCase = true)) {
                            currentErrors.add(cloudId)
                        }
                    }
                    
                    if (currentErrors.isNotEmpty()) {
                        nameErrors = currentErrors
                        return@TextButton
                    }
                    
                    val toMergeLocalIds = conflicts.filter { toMergeCloudIds.contains(it.first.id) }.map { it.second.id }.toSet()
                    val toIgnoreLocalIds = conflicts.filter { toIgnoreCloudIds.contains(it.first.id) }.map { it.second.id }.toSet()
                    
                    val finalResolvedSameNameIds = resolvedSameNameIds + toMergeLocalIds
                    val finalIgnoredConflictLocalIds = ignoredConflictLocalIds + toIgnoreLocalIds
                    
                    resolvedSameNameIds = finalResolvedSameNameIds
                    ignoredConflictLocalIds = finalIgnoredConflictLocalIds
                    
                    val newRenames = mutableMapOf<Int, String>()
                    conflicts.forEach { conflict ->
                        if (toIgnoreCloudIds.contains(conflict.first.id)) {
                            newRenames[conflict.first.id] = cloudNames[conflict.first.id]?.trim() ?: conflict.first.name
                        }
                    }
                    val finalRenamedCloudFriends = renamedCloudFriendsState + newRenames
                    renamedCloudFriendsState = finalRenamedCloudFriends
                    
                    // Auto-trigger the import
                    val snapshot = importSnapshotToProcess
                    val fileId = importFileId
                    val account = importAccount
                    if (snapshot != null && fileId != null && account != null) {
                        isImportSaving = true
                        viewModel.viewModelScope.launch {
                            try {
                                val tripId = viewModel.importTripSnapshotWithMe(
                                    account = account,
                                    fileId = fileId,
                                    snapshot = snapshot,
                                    resolvedSameNameIds = finalResolvedSameNameIds,
                                    renamedCloudFriends = finalRenamedCloudFriends
                                )
                                if (tripId != null) {
                                    android.widget.Toast.makeText(context, "Trip synced and imported successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                    selectedAppTripId = tripId
                                } else {
                                    android.widget.Toast.makeText(context, "Could not import trip.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, e.message ?: "An error occurred during import.", android.widget.Toast.LENGTH_LONG).show()
                            } finally {
                                isImportSaving = false
                                importConflictsList = null
                                showImportSelectMeDialog = false
                                importSnapshotToProcess = null
                                importFileId = null
                                importAccount = null
                            }
                        }
                    }
                }) {
                    if (isImportSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Continue & Import", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isImportSaving,
                    onClick = { 
                        importConflictsList = null 
                        importSnapshotToProcess = null
                        importFileId = null
                        importAccount = null
                    }
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    if (showAddTrip) {
        AddTripDialog(
            onDismiss = { showAddTrip = false },
            onSave = { name, currency ->
                viewModel.addTrip(name, currency)
                showAddTrip = false
            }
        )
    }

    if (showAddFriend) {
        AddFriendDialog(
            onDismiss = { showAddFriend = false },
            onSave = { name, email ->
                viewModel.addFriend(name, email)
                showAddFriend = false
            }
        )
    }
}

@Composable
fun GreetingHeader(
    userName: String,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onAvatarClick: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchVisible: Boolean,
    onSearchVisibleChange: (Boolean) -> Unit
) {
    val searchFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    
    androidx.activity.compose.BackHandler(enabled = isSearchVisible) {
        onSearchVisibleChange(false)
        onSearchQueryChange("")
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSearchVisible) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search Trips & Friends...", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.4f)) },
                leadingIcon = {
                    IconButton(onClick = {
                        onSearchVisibleChange(false)
                        onSearchQueryChange("")
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Search", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        } else {
            val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val greeting = when (currentHour) {
                in 0..11 -> "Good morning"
                in 12..16 -> "Good afternoon"
                else -> "Good evening"
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .offset(y = (-4).dp)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.getCardPurple(), com.journeybills.ui.theme.getCardPink())), CircleShape)
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = userName.firstOrNull()?.toString()?.uppercase() ?: "J", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = greeting, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(
                    text = userName,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { onSearchVisibleChange(true) }) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onThemeToggle) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = "Toggle Theme",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
fun SplashScreen(userName: String) {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    val icons = listOf(Icons.Rounded.Flight, Icons.Rounded.Train, Icons.Rounded.DirectionsBus, Icons.Rounded.BeachAccess)
    var currentIconIndex by remember { mutableIntStateOf(0) }
    
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while(true) {
            kotlinx.coroutines.delay(800)
            currentIconIndex = (currentIconIndex + 1) % icons.size
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val purpleBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.CardPurple, com.journeybills.ui.theme.CardPink))
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(purpleBrush, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = icons[currentIconIndex],
                    animationSpec = androidx.compose.animation.core.tween(500)
                ) { icon ->
                    Icon(
                        icon, 
                        contentDescription = null, 
                        modifier = Modifier.size(48.dp), 
                        tint = Color.Black
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "$greeting,",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
            Text(
                text = userName,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun NamePromptScreen(onNameSaved: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            }
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(80.dp).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, com.journeybills.ui.theme.getCardGreen())), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = Color.Black, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text("Journey Bills", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Text("What should we call you?", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(48.dp))
        OutlinedTextField(
            value = name, 
            onValueChange = { name = it.toCamelCasePreserveSpaces() },
            label = { Text("Your Name", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    if (name.isNotBlank()) onNameSaved(name)
                }
            )
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = { if (name.isNotBlank()) onNameSaved(name) },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) {
            Text("Let's Go", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TabButton(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (isSelected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            shape = RoundedCornerShape(24.dp),
            modifier = modifier
        ) {
            Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            shape = RoundedCornerShape(24.dp),
            modifier = modifier
        ) {
            Text(text, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SummaryCard(friends: List<com.journeybills.data.FriendBalanceEntity>, trips: List<TripEntity>, expenses: List<com.journeybills.data.ExpenseEntity>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${trips.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Trips", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${expenses.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Transactions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${friends.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("Friends", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun FriendsScreenList(
    userName: String?,
    friends: List<FriendBalanceEntity>,
    trips: List<TripEntity>,
    expenses: List<com.journeybills.data.ExpenseEntity>,
    onFriendClick: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val meName = if (userName.isNullOrBlank()) "Me" else userName
            val meEntity = com.journeybills.data.FriendBalanceEntity(
                id = -1, // Dummy ID for 'Me'
                name = meName,
                balance = 0.0,
                email = ""
            )
            FriendListItem(meEntity, trips, expenses) { /* Do Nothing */ }
        }
        items(friends) { friend ->
            FriendListItem(friend, trips, expenses) { onFriendClick(friend.id) }
        }
        if (friends.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val pinkBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.getCardPink(), com.journeybills.ui.theme.getCardPurple()))
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(pinkBrush, RoundedCornerShape(32.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.People, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Black)
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("No friends added", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a friend using the + button.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f))
                    }
                }
            }
        }
    }
}


@Composable
fun FriendListItem(
    friend: FriendBalanceEntity,
    trips: List<TripEntity>,
    expenses: List<com.journeybills.data.ExpenseEntity>,
    onClick: () -> Unit
) {
    val curBalances = remember(friend.id, trips, expenses) {
        getFriendBalancesByCurrency(friend.id, trips, expenses)
    }

    val pinkBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.CardPink, com.journeybills.ui.theme.CardPurple))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(pinkBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = friend.name.firstOrNull()?.toString()?.uppercase() ?: "F",
                color = Color.Black,
                style = MaterialTheme.typography.titleLarge
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Name & Email
        Column(modifier = Modifier.weight(1f)) {
            Text(text = friend.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(text = friend.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }
        
        // Dynamic Multi-Currency Balances!
        Column(horizontalAlignment = Alignment.End) {
            if (curBalances.isNotEmpty()) {
                val hasAnyNonZero = curBalances.values.any { kotlin.math.abs(it) > 0.005 }
                if (hasAnyNonZero) {
                    curBalances.forEach { (curr, bal) ->
                        val isZero = kotlin.math.abs(bal) <= 0.005
                        if (!isZero) {
                            val color = if (bal > 0) com.journeybills.ui.theme.getGreenPositive() else RedNegative
                            val symbol = getCurrencySymbol(curr)
                            val prefix = if (bal > 0) "+" else "-"
                            val displayAmt = "$prefix$symbol${String.format("%.2f", kotlin.math.abs(bal))}"
                            Text(
                                text = displayAmt,
                                color = color,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Settled",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.3f),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text(
                    text = "No trips",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.3f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TripsScreenList(trips: List<TripEntity>, expenses: List<com.journeybills.data.ExpenseEntity>, onTripClick: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp, start = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(trips, key = { it.id }) { trip ->
            val tripExpenses = expenses.filter { it.tripId == trip.id }
            var totalSpent = 0.0
            var validExpenseCount = 0
            for (expense in tripExpenses) {
                val isPaymentOrTransfer = expense.description.startsWith("Payment: ") || expense.description.startsWith("Debt Transfer: ")
                if (!isPaymentOrTransfer) {
                    totalSpent += expense.amount
                    validExpenseCount++
                }
            }
            val participantCount = trip.participantIds.split(",").filter { it.isNotBlank() }.size + 1 // including me

            val yellowBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.CardYellow, com.journeybills.ui.theme.CardGreen))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .clickable { onTripClick(trip.id) }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(yellowBrush, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = trip.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    Text(text = "Spent: ${getCurrencySymbol(trip.currency)} ${String.format(java.util.Locale.US, "%.2f", totalSpent)}", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f), style = MaterialTheme.typography.bodyMedium)
                    Text(text = "$validExpenseCount Expenses • $participantCount Members", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f), style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha=0.3f))
            }
        }
        
        if (trips.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val yellowBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.CardYellow, com.journeybills.ui.theme.CardGreen))
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(yellowBrush, RoundedCornerShape(32.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Black)
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("No trips yet.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Add a trip using the + button.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f))
                    }
                }
            }
        }
    }
}


@Composable
fun ActivityScreenList(activities: List<RecentActivityEntity>, onLoadMore: () -> Unit = {}) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && activities.isNotEmpty() && lastIndex >= activities.size - 2) {
                    onLoadMore()
                }
            }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp, start = 24.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(activities) { activity ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val greenBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, com.journeybills.ui.theme.getCardGreen()))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(greenBrush, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (activity.iconName) {
                        "Restaurant" -> Icons.Rounded.Restaurant
                        "DirectionsCar" -> Icons.Rounded.DirectionsCar
                        "ShoppingCart" -> Icons.Rounded.ShoppingCart
                        "Payments" -> Icons.Rounded.Payments
                        "FlightTakeoff" -> Icons.Rounded.FlightTakeoff
                        "PersonAdd" -> Icons.Rounded.PersonAdd
                        else -> Icons.AutoMirrored.Filled.ReceiptLong
                    }
                    Icon(
                        imageVector = icon, 
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color.Black
                    )
                }
                Spacer(modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = activity.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(text = activity.description, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f), style = MaterialTheme.typography.bodyMedium)
                }
                Text(text = activity.time, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.4f), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (activities.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No activity yet.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(onDismiss: () -> Unit, onAddTrip: () -> Unit, onAddFriend: () -> Unit, onRestoreFromCloud: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Create New", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
            
            Row(modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onAddTrip() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(CardYellow, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Trip", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }
            
            Row(modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onAddFriend() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(CardPink, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Friend", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f))

            Row(modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onRestoreFromCloud() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(CardGreen, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Restore from Cloud", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

fun getCurrencySymbol(currencyCode: String): String {
    return when (currencyCode) {
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "INR" -> "₹"
        "JPY" -> "¥"
        "CAD" -> "CA$"
        "AUD" -> "A$"
        "CHF" -> "Fr."
        "CNY" -> "¥"
        "SGD" -> "S$"
        else -> currencyCode
    }
}

fun getFriendBalancesByCurrency(
    friendId: Int,
    trips: List<TripEntity>,
    expenses: List<com.journeybills.data.ExpenseEntity>
): Map<String, Double> {
    val balances = mutableMapOf<String, Double>()
    
    // Filter non-deleted trips where friend is a participant OR friend is "Me"
    val friendTrips = trips.filter { trip ->
        !trip.isDeleted && (friendId == -1 || trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }.contains(friendId))
    }
    
    for (trip in friendTrips) {
        var tripNet = 0.0
        val tripExpenses = expenses.filter { it.tripId == trip.id }
        for (e in tripExpenses) {
            val paidId = if (friendId == -1) null else friendId
            if (e.paidById == paidId) {
                tripNet += e.amount
            }
            try {
                val json = org.json.JSONObject(e.splitsJson)
                val key = if (friendId == -1) "me" else friendId.toString()
                if (json.has(key)) {
                    tripNet -= json.getDouble(key)
                }
            } catch (ex: Exception) { ex.printStackTrace() }
        }
        val curr = trip.currency
        balances[curr] = (balances[curr] ?: 0.0) + tripNet
    }
    return balances
}

@Composable
fun AddTripDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var isSubmitted by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val majorCurrencies = listOf(
        "USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD", "CHF", "CNY", "SGD"
    )

    val trimmedName = name.trim()
    val isBlank = name.isBlank()
    val startsWithInvalid = !isBlank && !name.first().isLetterOrDigit()
    val isError = isSubmitted && (isBlank || startsWithInvalid)

    val errorMessage = when {
        isBlank -> "Trip name is mandatory"
        startsWithInvalid -> "Must start with a letter or number"
        else -> null
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text("Add Trip", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    }
            ) {
                OutlinedTextField(
                    value = name, 
                    onValueChange = { name = it.toCamelCasePreserveSpaces() }, 
                    label = { Text("Trip Name") }, 
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) { { Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error) } } else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            isSubmitted = true
                            if (!isBlank && !startsWithInvalid) {
                                onSave(trimmedName, currency)
                            }
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                Box(modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
                    OutlinedTextField(
                        value = "$currency (${getCurrencySymbol(currency)})",
                        onValueChange = {},
                        enabled = false,
                        readOnly = true,
                        label = { Text("Trip Default Currency") },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropDown,
                                contentDescription = "Select Currency",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .heightIn(max = 240.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        majorCurrencies.forEach { curr ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "$curr (${getCurrencySymbol(curr)})",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                },
                                onClick = {
                                    currency = curr
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        confirmButton = {
            Button(
                onClick = { 
                    isSubmitted = true
                    if (!isBlank && !startsWithInvalid) {
                        onSave(trimmedName, currency)
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) { 
                Text("Add", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) 
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(bottom = 8.dp)
            ) { 
                Text("Cancel", color = MaterialTheme.colorScheme.primary) 
            }
        }
    )
}

@Composable
fun AddFriendDialog(
    initialName: String = "",
    initialEmail: String = "",
    onDismiss: () -> Unit, 
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var email by remember { mutableStateOf(initialEmail) }
    var isSubmitted by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val isBlank = name.isBlank()
    val startsWithInvalid = !isBlank && !name.first().isLetterOrDigit()
    val isError = isSubmitted && (isBlank || startsWithInvalid)

    val errorMessage = when {
        isBlank -> "Friend name is mandatory"
        startsWithInvalid -> "Must start with a letter or number"
        else -> null
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (initialName.isEmpty()) "Add Friend" else "Edit Friend", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    }
            ) {
                OutlinedTextField(
                    value = name, 
                    onValueChange = { name = it.toCamelCasePreserveSpaces() }, 
                    label = { Text("Name") }, 
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) { { Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error) } } else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = email, 
                    onValueChange = { email = it }, 
                    label = { Text("Email (Optional)") }, 
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            isSubmitted = true
                            if (!isBlank && !startsWithInvalid) {
                                onSave(trimmedName, email)
                            }
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        confirmButton = {
            Button(
                onClick = { 
                    isSubmitted = true
                    if (!isBlank && !startsWithInvalid) {
                        onSave(trimmedName, email)
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) { 
                Text(if (initialName.isEmpty()) "Add" else "Save", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) 
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(bottom = 8.dp)
            ) { 
                Text("Cancel", color = MaterialTheme.colorScheme.primary) 
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionOptionsSheet(
    onDismiss: () -> Unit,
    onSelectType: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Add new...", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 16.dp))
            
            Row(modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onSelectType(0) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(com.journeybills.ui.theme.CardPurple, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Receipt, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Expense", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }
            
            Row(modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onSelectType(2) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(com.journeybills.ui.theme.CardYellow, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SyncAlt, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Transfer Debt", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }

            Row(modifier = Modifier.fillMaxWidth().clickable { onDismiss(); onSelectType(1) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(com.journeybills.ui.theme.CardGreen, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Payments, contentDescription = null, tint = Color.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Advance Payment", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseDialog(
    transactionType: Int = 0, // 0 = Expense, 1 = Income, 2 = Transfer
    friends: List<FriendBalanceEntity>,
    trips: List<TripEntity>,
    tags: List<com.journeybills.data.TagEntity> = emptyList(),
    initialTripId: Int? = null,
    initialExpense: com.journeybills.data.ExpenseEntity? = null,
    defaultCurrency: String = "USD",
    currentUserName: String = "Me",
    defaultPayerId: Int? = null,
    participantBalances: Map<Int?, UserTripBalance> = emptyMap(),
    onDismiss: () -> Unit,
    onSaveTag: (com.journeybills.data.TagEntity) -> Unit = {},
    onSave: (String, Double, Int?, Int?, Map<Int?, Double>, String?, Long) -> Unit
) {
    var selectedTripId by remember { mutableStateOf<Int?>(initialExpense?.tripId ?: initialTripId) }
    val selectedTrip = trips.find { it.id == selectedTripId }
    val tripCurrency = selectedTrip?.currency ?: defaultCurrency

    val conversionRegex = remember { Regex("""\s*\((\d+\.?\d*|\.\d+) (\w+) @ (\d+\.?\d*|\.\d+)\)$""") }
    val regexMatch = remember(initialExpense) {
        initialExpense?.description?.let { desc ->
            conversionRegex.find(desc)
        }
    }

    val initialExpenseCurrency = remember(regexMatch, tripCurrency) {
        regexMatch?.groupValues?.get(2) ?: tripCurrency
    }

    val initialConversionRateInput = remember(regexMatch) {
        regexMatch?.groupValues?.get(3) ?: ""
    }

    val cleanedDescription = remember(initialExpense, regexMatch) {
        val raw = initialExpense?.description ?: ""
        if (regexMatch != null) {
            raw.substring(0, regexMatch.range.first)
        } else {
            raw
        }
    }

    val initialAmountInput = remember(initialExpense, regexMatch) {
        regexMatch?.groupValues?.get(1) ?: initialExpense?.amount?.toString() ?: ""
    }

    var description by remember(initialExpense) { mutableStateOf(cleanedDescription) }
    var amount by remember(initialExpense) { mutableStateOf(initialAmountInput) }
    var selectedTagId by remember(initialExpense) { mutableStateOf<String?>(initialExpense?.tagId) }
    var paidById by remember(initialExpense) { mutableStateOf<Int?>(initialExpense?.paidById ?: if (initialExpense == null && transactionType != 0) -1 else defaultPayerId) }
    
    val initialSplitMapForSpecific = remember(initialExpense) {
        val map = mutableMapOf<Int?, Double>()
        if (initialExpense != null) {
            try {
                val json = org.json.JSONObject(initialExpense.splitsJson)
                val keys = json.keys()
                while(keys.hasNext()) {
                    val k = keys.next()
                    val id: Int? = if(k=="me") null else k.toInt()
                    map[id] = json.getDouble(k)
                }
            } catch (e: Exception) {}
        }
        map
    }
    var specificPayeeId by remember(initialExpense) { 
        mutableStateOf<Int?>(if (initialExpense == null && transactionType != 0) -1 else initialSplitMapForSpecific.keys.firstOrNull()) 
    }
    
    // Dynamic currency states
    var expenseCurrency by remember(selectedTripId, tripCurrency, initialExpense) {
        mutableStateOf(if (selectedTripId == initialExpense?.tripId && initialExpenseCurrency != tripCurrency) initialExpenseCurrency else tripCurrency)
    }
    var conversionRateInput by remember(initialExpense) { mutableStateOf(initialConversionRateInput) }
    
    val allMembersIds = listOf<Int?>(null) + friends.map { it.id }
    
    val initialSplitsMap = remember(initialExpense) {
        val map = mutableMapOf<Int?, Double>()
        if (initialExpense != null) {
            try {
                val json = org.json.JSONObject(initialExpense.splitsJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id: Int? = if (k == "me") null else k.toInt()
                    map[id] = json.getDouble(k)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        map
    }

    val initialSplitEqually = remember(initialExpense, initialSplitsMap) {
        if (initialExpense == null) {
            true
        } else {
            if (initialSplitsMap.isEmpty()) {
                true
            } else {
                val values = initialSplitsMap.values.toList()
                val first = values.firstOrNull() ?: 0.0
                val allSame = values.all { kotlin.math.abs(it - first) < 0.05 }
                allSame
            }
        }
    }

    var splitMode by remember(initialExpense) { mutableStateOf(if (initialSplitEqually) 0 else 1) }

    val initialSplitWithIds = remember(initialExpense, initialSplitsMap) {
        if (initialExpense == null) {
            allMembersIds.toSet()
        } else {
            initialSplitsMap.keys.toSet()
        }
    }
    var splitWithIds by remember(initialExpense) { mutableStateOf(initialSplitWithIds) }

    val initialPercentages = remember(initialExpense, initialSplitsMap) {
        val map = mutableMapOf<Int?, String>()
        if (initialExpense != null && initialExpense.amount > 0) {
            for ((id, share) in initialSplitsMap) {
                val pct = (share / initialExpense.amount) * 100.0
                map[id] = String.format(java.util.Locale.US, "%.1f", pct)
            }
        }
        map
    }
    var percentages by remember(initialExpense) { mutableStateOf(initialPercentages.toMap()) }
    
    val initialExactAmounts = remember(initialExpense, initialSplitsMap) {
        val map = mutableMapOf<Int?, String>()
        if (initialExpense != null) {
            for ((id, share) in initialSplitsMap) {
                map[id] = String.format(java.util.Locale.US, "%.2f", share)
            }
        }
        map
    }
    var exactAmounts by remember(initialExpense) { mutableStateOf(initialExactAmounts.toMap()) }
    
    var showNoShareConfirm by remember { mutableStateOf(false) }
    var showNoPercentConfirm by remember { mutableStateOf(false) }
    var pendingSplits by remember { mutableStateOf<Map<Int?, Double>?>(null) }
    
    var selectedDateMillis by remember(initialExpense) { mutableStateOf(initialExpense?.timestamp ?: System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }

    val isConversionRateRequired = expenseCurrency != tripCurrency
    val parsedConversionRate = conversionRateInput.toDoubleOrNull() ?: 0.0
    val conversionRateError = isSubmitted && isConversionRateRequired && parsedConversionRate <= 0.0

    val parsedAmountVal = amount.toDoubleOrNull() ?: 0.0
    val isDescriptionEmpty = description.isBlank()
    val isAmountInvalid = parsedAmountVal <= 0.0 || (transactionType == 2 && parsedAmountVal > java.lang.Math.max(0.0, -(participantBalances[paidById]?.net ?: 0.0)) + 0.005)

    val descriptionError = isSubmitted && isDescriptionEmpty
    val amountError = isSubmitted && isAmountInvalid

    val targetAmount = remember(parsedAmountVal, isConversionRateRequired, parsedConversionRate) {
        if (isConversionRateRequired && parsedConversionRate > 0.0) {
            parsedAmountVal * parsedConversionRate
        } else {
            parsedAmountVal
        }
    }

    val savedDescription = remember(description, isConversionRateRequired, amount, expenseCurrency, conversionRateInput) {
        val baseDesc = description.trim()
        if (isConversionRateRequired && parsedConversionRate > 0.0) {
            val suffix = " ($amount $expenseCurrency @ $conversionRateInput)"
            if (baseDesc.endsWith(suffix)) baseDesc else "$baseDesc$suffix"
        } else {
            baseDesc
        }
    }

    val hasAnyPercentage = remember(percentages, splitWithIds, splitMode) {
        splitMode == 1 && percentages.any { (id, value) -> splitWithIds.contains(id) && value.isNotBlank() }
    }
    val sumPercent = remember(percentages, splitWithIds, splitMode) {
        if (splitMode != 1) 0.0 else splitWithIds.sumOf { id -> percentages[id]?.toDoubleOrNull() ?: 0.0 }
    }
    val membersWithoutPercentCount = remember(percentages, splitWithIds, splitMode) {
        if (splitMode != 1) 0 else splitWithIds.count { id -> percentages[id].isNullOrBlank() }
    }
    val percentageErrorMsg = remember(hasAnyPercentage, sumPercent, membersWithoutPercentCount, splitMode) {
        if (splitMode != 1) null
        else if (!hasAnyPercentage) null
        else if (sumPercent > 100.2) {
            "Total cannot exceed 100% (currently ${String.format(java.util.Locale.US, "%.1f", sumPercent)}%)"
        }
        else if (membersWithoutPercentCount > 0 && Math.abs(sumPercent - 100.0) < 0.2) {
            "Total is 100%, but some selected members have no %. Unselect them if they shouldn't share."
        }
        else if (membersWithoutPercentCount == 0 && Math.abs(sumPercent - 100.0) > 0.2) {
            "Total must equal 100% (currently ${String.format(java.util.Locale.US, "%.1f", sumPercent)}%)"
        }
        else null
    }

    val hasAnyExactAmount = remember(exactAmounts, splitWithIds, splitMode) {
        splitMode == 2 && exactAmounts.any { (id, value) -> splitWithIds.contains(id) && value.isNotBlank() }
    }
    val sumExactAmount = remember(exactAmounts, splitWithIds, splitMode) {
        if (splitMode != 2) 0.0 else splitWithIds.sumOf { id -> exactAmounts[id]?.toDoubleOrNull() ?: 0.0 }
    }
    val membersWithoutExactCount = remember(exactAmounts, splitWithIds, splitMode) {
        if (splitMode != 2) 0 else splitWithIds.count { id -> exactAmounts[id].isNullOrBlank() }
    }
    val exactAmountErrorMsg = remember(hasAnyExactAmount, sumExactAmount, targetAmount, membersWithoutExactCount, splitMode, expenseCurrency) {
        if (splitMode != 2) null
        else if (!hasAnyExactAmount) null
        else if (sumExactAmount > targetAmount + 0.01) {
            "Total cannot exceed $targetAmount $expenseCurrency (currently ${String.format(java.util.Locale.US, "%.2f", sumExactAmount)} $expenseCurrency)"
        }
        else if (membersWithoutExactCount > 0 && Math.abs(sumExactAmount - targetAmount) < 0.01) {
            "Total equals $targetAmount $expenseCurrency, but some selected members have no amount. Unselect them if they shouldn't share."
        }
        else if (membersWithoutExactCount == 0 && Math.abs(sumExactAmount - targetAmount) >= 0.01) {
            "Total must equal $targetAmount $expenseCurrency (currently ${String.format(java.util.Locale.US, "%.2f", sumExactAmount)} $expenseCurrency)"
        }
        else null
    }
    
    val isSplitError = percentageErrorMsg != null || exactAmountErrorMsg != null

    fun handleSave() {
        isSubmitted = true
        val parsedAmount = amount.toDoubleOrNull() ?: 0.0
        val isConversionRateRequired = expenseCurrency != tripCurrency
        val rate = if (isConversionRateRequired) (conversionRateInput.toDoubleOrNull() ?: 0.0) else 1.0
        
        if (parsedAmount <= 0) return
        if (transactionType == 2 && parsedAmount > java.lang.Math.max(0.0, -(participantBalances[paidById]?.net ?: 0.0)) + 0.005) return
        if (isConversionRateRequired && rate <= 0) return

        if (transactionType != 0) { // Income or Transfer
            if (paidById == -1 || specificPayeeId == -1) return
            val allParticipantsWithMe = listOf(null to currentUserName) + friends.map { it.id to it.name }
            val fromName = allParticipantsWithMe.find { it.first == paidById }?.second ?: currentUserName
            val toName = allParticipantsWithMe.find { it.first == specificPayeeId }?.second ?: currentUserName
            
            val exactBalance = java.lang.Math.max(0.0, -(participantBalances[paidById]?.net ?: 0.0))
            val adjustedParsedAmount = if (transactionType == 2 && kotlin.math.abs(parsedAmount - exactBalance) < 0.005) exactBalance else parsedAmount
            
            val adjustedTargetAmount = if (isConversionRateRequired && parsedConversionRate > 0.0) {
                adjustedParsedAmount * parsedConversionRate
            } else {
                adjustedParsedAmount
            }
            
            val autoDesc = if (transactionType == 1) "Payment: $fromName -> $toName" else "Debt Transfer: $fromName -> $toName"
            val finalDesc = if (isConversionRateRequired && parsedConversionRate > 0.0) {
                "$autoDesc ($amount $expenseCurrency @ $conversionRateInput)"
            } else {
                autoDesc
            }
            onSave(finalDesc, adjustedTargetAmount, selectedTripId, paidById, mapOf(specificPayeeId to adjustedTargetAmount), selectedTagId, selectedDateMillis)
            return
        }

        if (description.isBlank()) return
        if (selectedTagId == null) return
        if (isSplitError) return
        
        val activeSplitIds = splitWithIds
        val splits = mutableMapOf<Int?, Double>()
        
        if (splitMode == 0) {
            if (activeSplitIds.isEmpty()) return
            val each = targetAmount / activeSplitIds.size
            activeSplitIds.forEach { splits[it] = each }
        } else if (splitMode == 1) {
            val membersWithPercent = mutableListOf<Int?>()
            val membersWithoutPercent = mutableListOf<Int?>()
            
            for (id in activeSplitIds) {
                val p = percentages[id]?.toDoubleOrNull()
                if (p != null) {
                    membersWithPercent.add(id)
                    splits[id] = (p / 100.0) * targetAmount
                } else {
                    membersWithoutPercent.add(id)
                }
            }
            
            if (membersWithoutPercent.isNotEmpty()) {
                val remainingPercentage = maxOf(0.0, 100.0 - sumPercent)
                val remainingAmount = (remainingPercentage / 100.0) * targetAmount
                val eachRemaining = remainingAmount / membersWithoutPercent.size
                for (id in membersWithoutPercent) {
                    splits[id] = eachRemaining
                }
            }
        } else if (splitMode == 2) {
            val membersWithAmount = mutableListOf<Int?>()
            val membersWithoutAmount = mutableListOf<Int?>()
            
            for (id in activeSplitIds) {
                val a = exactAmounts[id]?.toDoubleOrNull()
                if (a != null) {
                    membersWithAmount.add(id)
                    splits[id] = a
                } else {
                    membersWithoutAmount.add(id)
                }
            }
            
            if (membersWithoutAmount.isNotEmpty()) {
                val remainingAmount = maxOf(0.0, targetAmount - sumExactAmount)
                val eachRemaining = remainingAmount / membersWithoutAmount.size
                for (id in membersWithoutAmount) {
                    splits[id] = eachRemaining
                }
            }
        }

        // Adjust splits to ensure the total perfectly matches targetAmount
        val sumSplits = splits.values.sum()
        val diff = targetAmount - sumSplits
        if (kotlin.math.abs(diff) > 0.005) {
            val idToAdjust = if (splits.containsKey(paidById)) paidById else splits.keys.firstOrNull()
            if (idToAdjust != null) {
                splits[idToAdjust] = (splits[idToAdjust] ?: 0.0) + diff
            }
        }

        // Final UI logic and save
        val hasMissingInput = (splitMode == 1 && activeSplitIds.any { percentages[it].isNullOrBlank() }) || 
                              (splitMode == 2 && activeSplitIds.any { exactAmounts[it].isNullOrBlank() })

        if (activeSplitIds.isEmpty() || (activeSplitIds.size == 1 && activeSplitIds.contains(paidById))) {
            pendingSplits = splits
            showNoShareConfirm = true
        } else if (hasMissingInput) {
            pendingSplits = splits
            showNoPercentConfirm = true
        } else {
            onSave(savedDescription, targetAmount, selectedTripId, paidById, splits, selectedTagId, selectedDateMillis)
        }
    }
    
    if (showNoShareConfirm) {
        AlertDialog(
            onDismissRequest = { showNoShareConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Confirm Unshared Expense",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "This expense is not being shared by anyone except the person who paid for it. Do you want to add it anyway?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNoShareConfirm = false
                    pendingSplits?.let { onSave(savedDescription, targetAmount, selectedTripId, paidById, it, selectedTagId, selectedDateMillis) }
                }) {
                    Text("Confirm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoShareConfirm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        )
    }
    
    if (showNoPercentConfirm) {
        AlertDialog(
            onDismissRequest = { showNoPercentConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Confirm Split",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = if (splitMode == 1) "Since percentage is not added for some members, the remaining expense is shared equally among them. Confirm?" 
                           else "Since amount is not added for some members, the remaining balance is shared equally among them. Confirm?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNoPercentConfirm = false
                    pendingSplits?.let { onSave(savedDescription, targetAmount, selectedTripId, paidById, it, selectedTagId, selectedDateMillis) }
                }) {
                    Text("Confirm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoPercentConfirm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        )
    }
    
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val titleText = when (transactionType) {
            1 -> if (initialExpense == null) "Add Advance Payment" else "Edit Advance Payment"
            2 -> if (initialExpense == null) "Transfer Debt" else "Edit Transfer"
            else -> if (initialExpense == null) "Add an Expense" else "Edit Expense"
        }
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                androidx.compose.material3.TopAppBar(
                    title = { Text(titleText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .windowInsetsPadding(WindowInsets.ime)
                        .padding(start = 24.dp, end = 24.dp, bottom = screenHeight * 0.1f, top = 16.dp)
                ) {
                    Button(
                        onClick = { handleSave() }, 
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
                        shape = RoundedCornerShape(28.dp),
                        contentPadding = PaddingValues(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (initialExpense == null) "Add" else "Save", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                if (transactionType == 0) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.toCamelCasePreserveSpaces() },
                        label = { Text("Enter a description") },
                        leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = descriptionError,
                        supportingText = if (descriptionError) { { Text("Description is mandatory", color = MaterialTheme.colorScheme.error) } } else null,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Next
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            errorLabelColor = MaterialTheme.colorScheme.error
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val defaultTags = listOf(
                        com.journeybills.data.TagEntity("default1", "Food & Drink", "🍔"),
                        com.journeybills.data.TagEntity("default2", "Groceries", "🛒"),
                        com.journeybills.data.TagEntity("default3", "Transport", "🚗"),
                        com.journeybills.data.TagEntity("default4", "Flight", "✈️"),
                        com.journeybills.data.TagEntity("default5", "Hotel", "🏨"),
                        com.journeybills.data.TagEntity("default6", "Sightseeing", "🏞️"),
                        com.journeybills.data.TagEntity("default7", "Shopping", "🛍️"),
                        com.journeybills.data.TagEntity("default8", "Gas/Fuel", "⛽"),
                        com.journeybills.data.TagEntity("default9", "Entertainment", "🎭")
                    )
                    val allAvailableTags = (tags + defaultTags).distinctBy { it.id }.distinctBy { it.name.lowercase() }
                    
                    var showTagDialog by remember { mutableStateOf(false) }
                    var editingTag by remember { mutableStateOf<com.journeybills.data.TagEntity?>(null) }
                    
                    var newTagName by remember { mutableStateOf("") }
                    var newTagEmoji by remember { mutableStateOf("") }
                    
                    var tagDropdownExpanded by remember { mutableStateOf(false) }
                    
                    val selectedTag = allAvailableTags.find { it.id == selectedTagId }
                    val tagDisplayText = if (selectedTag != null) {
                        "${selectedTag.emoji} ${selectedTag.name}"
                    } else {
                        ""
                    }
                    
                    val isTagMissing = selectedTagId == null
                    val tagError = isSubmitted && isTagMissing

                    androidx.compose.material3.ExposedDropdownMenuBox(
                        expanded = tagDropdownExpanded,
                        onExpandedChange = { tagDropdownExpanded = !tagDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = tagDisplayText,
                            onValueChange = {},
                            readOnly = true,
                            leadingIcon = { Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            placeholder = { Text("Select a category...") },
                            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = tagDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            isError = tagError,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.outline,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                errorBorderColor = MaterialTheme.colorScheme.error,
                                errorLabelColor = MaterialTheme.colorScheme.error
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = tagDropdownExpanded,
                            onDismissRequest = { tagDropdownExpanded = false },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .heightIn(max = 240.dp) // Make it scrollable and not too long
                        ) {
                            allAvailableTags.forEach { tag ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("${tag.emoji} ${tag.name}") },
                                    onClick = {
                                        selectedTagId = tag.id
                                        tagDropdownExpanded = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                newTagName = tag.name
                                                newTagEmoji = tag.emoji
                                                editingTag = tag
                                                showTagDialog = true
                                                tagDropdownExpanded = false
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = "Edit Category",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("+ Custom Category", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                onClick = {
                                    newTagName = ""
                                    newTagEmoji = ""
                                    editingTag = null
                                    showTagDialog = true
                                    tagDropdownExpanded = false
                                }
                            )
                        }
                    }
                    if (tagError) {
                        Text(
                            text = "Please select a category.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (showTagDialog) {
                        AlertDialog(
                            onDismissRequest = { showTagDialog = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            title = { Text(if (editingTag != null) "Edit Category" else "New Category") },
                            text = {
                                Column {
                                    // Keep Name first, Emoji second and make Emoji optional (using default)
                                    OutlinedTextField(
                                        value = newTagName,
                                        onValueChange = { newTagName = it.toCamelCasePreserveSpaces() },
                                        label = { Text("Name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    OutlinedTextField(
                                        value = newTagEmoji,
                                        onValueChange = { newTagEmoji = it },
                                        label = { Text("Emoji (Optional)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    if (newTagName.isNotBlank()) {
                                        val finalEmoji = if (newTagEmoji.isBlank()) "🏷️" else newTagEmoji.trim()
                                        val finalTag = com.journeybills.data.TagEntity(
                                            id = editingTag?.id ?: java.util.UUID.randomUUID().toString(),
                                            name = newTagName.trim(),
                                            emoji = finalEmoji
                                        )
                                        onSaveTag(finalTag)
                                        selectedTagId = finalTag.id
                                        showTagDialog = false
                                        editingTag = null
                                    }
                                }) { Text("Save", color = MaterialTheme.colorScheme.primary) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTagDialog = false }) { Text("Cancel", color = MaterialTheme.colorScheme.primary) }
                            }
                        )
                    }
                }
                
                var expenseCurrencyExpanded by remember { mutableStateOf(false) }

                val renderAmount: @Composable () -> Unit = {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    leadingIcon = { 
                        Text(
                            text = getCurrencySymbol(expenseCurrency), 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 20.sp, 
                            color = MaterialTheme.colorScheme.primary, 
                            modifier = Modifier.padding(start = 12.dp)
                        ) 
                    },
                    trailingIcon = {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { expenseCurrencyExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = expenseCurrency, 
                                    fontWeight = FontWeight.ExtraBold, 
                                    fontSize = 16.sp, 
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown, 
                                    contentDescription = "Change Currency",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            DropdownMenu(
                                expanded = expenseCurrencyExpanded,
                                onDismissRequest = { expenseCurrencyExpanded = false },
                                modifier = Modifier
                                    .width(140.dp)
                                    .heightIn(max = 240.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                val majorCurrencies = listOf(
                                    "USD", "EUR", "GBP", "INR", "JPY", "CAD", "AUD", "CHF", "CNY", "SGD"
                                )
                                majorCurrencies.forEach { curr ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "$curr (${getCurrencySymbol(curr)})",
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                        },
                                        onClick = {
                                            expenseCurrency = curr
                                            expenseCurrencyExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = amountError,
                    supportingText = if (amountError) { { Text(if (transactionType == 2) "Amount cannot be greater than owed amount" else "Please enter an amount greater than 0", color = MaterialTheme.colorScheme.error) } } else null,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        imeAction = if (isConversionRateRequired) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        errorLabelColor = MaterialTheme.colorScheme.error
                    )
                )

                if (transactionType == 2 && paidById != -1 && paidById != null) {
                    val owed = participantBalances[paidById]?.net ?: 0.0
                    val maxAmount = if (owed < 0) -owed else 0.0
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Maximum transfer amount: ${getCurrencySymbol(tripCurrency)}${String.format("%.2f", maxAmount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }

                if (isConversionRateRequired) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Trip default currency is $tripCurrency. Conversion required.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = conversionRateInput,
                        onValueChange = { conversionRateInput = it },
                        label = { Text("1 $expenseCurrency = ? $tripCurrency") },
                        placeholder = { Text("e.g. 1.10") },
                        leadingIcon = { Icon(Icons.Rounded.Payments, contentDescription = null) },
                        singleLine = true,
                        isError = conversionRateError,
                        supportingText = if (conversionRateError) { { Text("Mandatory field: Please enter conversion rate > 0", color = MaterialTheme.colorScheme.error) } } else null,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            errorLabelColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (parsedAmountVal > 0 && parsedConversionRate > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Equivalent in Trip Currency: ${getCurrencySymbol(tripCurrency)} ${String.format("%.2f", parsedAmountVal * parsedConversionRate)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                }
                
                val renderDate: @Composable () -> Unit = {
                val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                val dateString = dateFormat.format(java.util.Date(selectedDateMillis))
                OutlinedTextField(
                    value = dateString,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Date") },
                    leadingIcon = { Icon(Icons.Rounded.DateRange, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true },
                    enabled = false,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                }
                
                val renderFrom: @Composable () -> Unit = {
                val fromLabel = when (transactionType) {
                    1 -> "From"
                    2 -> "Transferred From"
                    else -> "Paid By"
                }
                
                var paidByDropdownExpanded by remember { mutableStateOf(false) }
                val paidByName = if (paidById == -1) "" else if (paidById == null) currentUserName else friends.find { it.id == paidById }?.name ?: "Unknown"

                androidx.compose.material3.ExposedDropdownMenuBox(
                    expanded = paidByDropdownExpanded,
                    onExpandedChange = { paidByDropdownExpanded = !paidByDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = paidByName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(fromLabel) },
                        trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = paidByDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = paidByDropdownExpanded,
                        onDismissRequest = { paidByDropdownExpanded = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(currentUserName) },
                            onClick = {
                                paidById = null
                                paidByDropdownExpanded = false
                            }
                        )
                        friends.forEach { friend ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(friend.name) },
                                onClick = {
                                    paidById = friend.id
                                    paidByDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                }
                
                val renderTo: @Composable () -> Unit = {
                if (transactionType != 0) {
                    val toLabel = if (transactionType == 1) "To" else "Transferred To"
                    
                    var specificPayeeDropdownExpanded by remember { mutableStateOf(false) }
                    val specificPayeeName = if (specificPayeeId == -1) "" else if (specificPayeeId == null) currentUserName else friends.find { it.id == specificPayeeId }?.name ?: "Unknown"

                    androidx.compose.material3.ExposedDropdownMenuBox(
                        expanded = specificPayeeDropdownExpanded,
                        onExpandedChange = { specificPayeeDropdownExpanded = !specificPayeeDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = specificPayeeName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(toLabel) },
                            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = specificPayeeDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.outline,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = specificPayeeDropdownExpanded,
                            onDismissRequest = { specificPayeeDropdownExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(currentUserName) },
                                onClick = {
                                    specificPayeeId = null
                                    specificPayeeDropdownExpanded = false
                                }
                            )
                            friends.forEach { friend ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(friend.name) },
                                    onClick = {
                                        specificPayeeId = friend.id
                                        specificPayeeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                }
                
                if (transactionType == 2) {
                    renderFrom()
                    Spacer(modifier = Modifier.height(24.dp))
                    renderTo()
                    Spacer(modifier = Modifier.height(24.dp))
                    renderDate()
                    Spacer(modifier = Modifier.height(24.dp))
                    renderAmount()
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    renderAmount()
                    Spacer(modifier = Modifier.height(16.dp))
                    renderDate()
                    Spacer(modifier = Modifier.height(24.dp))
                    renderFrom()
                    if (transactionType != 0) {
                        Spacer(modifier = Modifier.height(24.dp))
                        renderTo()
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                if (transactionType == 0) {
                
                Column {
                    val allSelected = splitWithIds.size == allMembersIds.size
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            splitWithIds = if (allSelected) emptySet() else allMembersIds.toSet()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Split", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        var splitMethodExpanded by remember { mutableStateOf(false) }
                        val splitMethodText = when(splitMode) {
                            0 -> "Equally"
                            1 -> "As Parts"
                            else -> "As Amounts"
                        }
                        Box {
                            Row(
                                modifier = Modifier.clickable { splitMethodExpanded = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(splitMethodText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(
                                expanded = splitMethodExpanded,
                                onDismissRequest = { splitMethodExpanded = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            ) {
                                val methods = listOf(0 to "Equally", 1 to "As Parts", 2 to "As Amounts")
                                methods.forEach { (mode, text) ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(text) },
                                        trailingIcon = if (splitMode == mode) { { Icon(Icons.Rounded.Check, null) } } else null,
                                        onClick = {
                                            splitMode = mode
                                            splitMethodExpanded = false
                                            if (mode == 0) { percentages = emptyMap(); exactAmounts = emptyMap() }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        allMembersIds.forEach { memberId ->
                            val memberName = if (memberId == null) currentUserName else friends.find { it.id == memberId }?.name ?: "Unknown"
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val newSet = splitWithIds.toMutableSet()
                                if (newSet.contains(memberId)) newSet.remove(memberId) else newSet.add(memberId)
                                splitWithIds = newSet
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = splitWithIds.contains(memberId), 
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(memberName, color = MaterialTheme.colorScheme.onSurface)
                            
                            if (splitMode == 1 && splitWithIds.contains(memberId)) {
                                Spacer(modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = percentages[memberId] ?: "",
                                    onValueChange = { newStr ->
                                        var dots = 0
                                        val filtered = newStr.filter { char ->
                                            if (char == '.') {
                                                dots++
                                                dots <= 1
                                            } else {
                                                char.isDigit()
                                            }
                                        }
                                        val map = percentages.toMutableMap()
                                        map[memberId] = filtered
                                        percentages = map
                                    },
                                    placeholder = { Text("%") },
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true,
                                    isError = isSubmitted && percentageErrorMsg != null,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        }
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        errorBorderColor = MaterialTheme.colorScheme.error,
                                        errorLabelColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            } else if (splitMode == 2 && splitWithIds.contains(memberId)) {
                                Spacer(modifier = Modifier.weight(1f))
                                OutlinedTextField(
                                    value = exactAmounts[memberId] ?: "",
                                    onValueChange = { newStr ->
                                        var dots = 0
                                        val filtered = newStr.filter { char ->
                                            if (char == '.') {
                                                dots++
                                                dots <= 1
                                            } else {
                                                char.isDigit()
                                            }
                                        }
                                        val map = exactAmounts.toMutableMap()
                                        map[memberId] = filtered
                                        exactAmounts = map
                                    },
                                    placeholder = { Text(expenseCurrency) },
                                    modifier = Modifier.width(100.dp),
                                    singleLine = true,
                                    isError = isSubmitted && exactAmountErrorMsg != null,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        }
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        errorBorderColor = MaterialTheme.colorScheme.error,
                                        errorLabelColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    }
                    }
                    if (splitMode == 1 && hasAnyPercentage) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val isErr = isSubmitted && percentageErrorMsg != null
                        val textColor = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isErr) Icons.Rounded.Error else Icons.Rounded.Info,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isErr) {
                                    percentageErrorMsg ?: ""
                                } else {
                                    "Total percentage: ${String.format(java.util.Locale.US, "%.1f", sumPercent)}% / 100%"
                                },
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = if (isErr) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    } else if (splitMode == 2 && hasAnyExactAmount) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val isErr = isSubmitted && exactAmountErrorMsg != null
                        val textColor = if (isErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isErr) Icons.Rounded.Error else Icons.Rounded.Info,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isErr) {
                                    exactAmountErrorMsg ?: ""
                                } else {
                                    "Total amount: ${String.format(java.util.Locale.US, "%.2f", sumExactAmount)} / ${String.format(java.util.Locale.US, "%.2f", targetAmount)} $expenseCurrency"
                                },
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = if (isErr) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    } // end else if (splitMode == 2 && hasAnyExactAmount)
                } // end Column { from 2095
                } // end if (transactionType == 0)
                } // end Scrollable Column
                Spacer(modifier = Modifier.height(32.dp))
            } // end Container Column
        } // end Scaffold
    } // end Dialog
    
    if (showDatePicker) {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { utcTime ->
                            val offset = java.util.TimeZone.getDefault().getOffset(utcTime)
                            selectedDateMillis = utcTime - offset
                        }
                        showDatePicker = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDatePicker = false },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            },
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .scale(0.85f)
            ) {
                androidx.compose.material3.DatePicker(
                    state = datePickerState,
                    showModeToggle = false,
                    colors = androidx.compose.material3.DatePickerDefaults.colors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        headlineContentColor = MaterialTheme.colorScheme.onSurface,
                        weekdayContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        subheadContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        navigationContentColor = MaterialTheme.colorScheme.onSurface,
                        yearContentColor = MaterialTheme.colorScheme.onSurface,
                        currentYearContentColor = MaterialTheme.colorScheme.primary,
                        selectedYearContentColor = MaterialTheme.colorScheme.onPrimary,
                        selectedYearContainerColor = MaterialTheme.colorScheme.primary,
                        dayContentColor = MaterialTheme.colorScheme.onSurface,
                        selectedDayContentColor = MaterialTheme.colorScheme.onPrimary,
                        selectedDayContainerColor = MaterialTheme.colorScheme.primary,
                        todayContentColor = MaterialTheme.colorScheme.primary,
                        todayDateBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@Composable
fun FilterButton(text: String, isSelected: Boolean, color: Color, onClick: () -> Unit) {
    val isSystemInDark = androidx.compose.foundation.isSystemInDarkTheme()
    val desiredContentColor = if (isSystemInDark) Color.Black else Color.White
    if (isSelected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = desiredContentColor),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(text)
        }
    }
}

data class UserTripBalance(
    val spent: Double = 0.0,
    val share: Double = 0.0,
    val net: Double = 0.0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: Int,
    trips: List<TripEntity>,
    activities: List<RecentActivityEntity>,
    friends: List<FriendBalanceEntity>,
    expenses: List<com.journeybills.data.ExpenseEntity>,
    onBack: () -> Unit,
    viewModel: JourneyViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val defaultMe = if (!userName.isNullOrBlank()) userName!! else "Me"
    
    val prefs = context.getSharedPreferences("journey_prefs", android.content.Context.MODE_PRIVATE)
    var localMeId by remember(tripId) { 
        val savedId = prefs.getInt("trip_me_id_$tripId", -1)
        mutableStateOf(if (savedId == -1) null else savedId)
    }
    
    var tripOwnerName by remember(tripId) { mutableStateOf(prefs.getString("trip_owner_name_$tripId", defaultMe) ?: defaultMe) }
    var isOwnerRemoved by remember(tripId) { mutableStateOf(prefs.getBoolean("trip_owner_removed_$tripId", false)) }
    val resolvedMe = tripOwnerName

    val trip = trips.find { it.id == tripId } ?: return
    val tripActivities = activities.filter { it.tripId == tripId }
    val tripParticipants = trip.participantIds.split(",").filter { it.isNotBlank() }.mapNotNull { idStr -> friends.find { it.id == idStr.toInt() } }
    
    val tripExpenses = expenses.filter { it.tripId == tripId }
    val tripCurrency = trip.currency
    val tripSymbol = getCurrencySymbol(tripCurrency)
    val allTags = viewModel.tags.collectAsStateWithLifecycle().value
    val defaultTags = listOf(
        com.journeybills.data.TagEntity("default1", "Food & Drink", "🍔"),
        com.journeybills.data.TagEntity("default2", "Groceries", "🛒"),
        com.journeybills.data.TagEntity("default3", "Transport", "🚗"),
        com.journeybills.data.TagEntity("default4", "Flight", "✈️"),
        com.journeybills.data.TagEntity("default5", "Hotel", "🏨"),
        com.journeybills.data.TagEntity("default6", "Sightseeing", "🏞️"),
        com.journeybills.data.TagEntity("default7", "Shopping", "🛍️"),
        com.journeybills.data.TagEntity("default8", "Gas/Fuel", "⛽"),
        com.journeybills.data.TagEntity("default9", "Entertainment", "🎭")
    )
    val fullTagsList = (defaultTags + allTags).distinctBy { it.name.lowercase() }
    fun formatTripCurrency(amt: Double): String {
        return "$tripSymbol${String.format("%.2f", amt)}"
    }
    val participantBalances = mutableMapOf<Int?, UserTripBalance>()
    val allTripMembers = (if (isOwnerRemoved) emptyList<Int?>() else listOf<Int?>(null)) + tripParticipants.map { it.id }
    
    for (m in allTripMembers) {
        participantBalances[m] = UserTripBalance()
    }
    for (expense in tripExpenses) {
        val p = expense.paidById
        if (participantBalances.containsKey(p)) {
            val curr = participantBalances[p]!!
            participantBalances[p] = UserTripBalance(curr.spent + expense.amount, curr.share, curr.net + expense.amount)
        }
        try {
            val json = org.json.JSONObject(expense.splitsJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id: Int? = if (k == "me") null else k.toInt()
                val share = json.getDouble(k)
                if (participantBalances.containsKey(id)) {
                    val curr = participantBalances[id]!!
                    participantBalances[id] = UserTripBalance(curr.spent, curr.share + share, curr.net - share)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    val netBalances = remember(participantBalances) { participantBalances.map { NetBalance(it.key, java.math.BigDecimal(it.value.net.toString())) } }
    val settlements = remember(netBalances) { DebtSimplification.calculateSettlements(netBalances).sortedBy { it.fromId ?: -1 } }
    
    var showTransactionSelector by remember { mutableStateOf(false) }
    var showAddDialogType by remember { mutableStateOf<Int?>(null) }
    var showAddFriendToTrip by remember { mutableStateOf(false) }
    
    if (showTransactionSelector) {
        TransactionOptionsSheet(
            onDismiss = { showTransactionSelector = false },
            onSelectType = { type ->
                showTransactionSelector = false
                showAddDialogType = type
            }
        )
    }

    if (showAddDialogType != null) {
        val tags = viewModel.tags.collectAsStateWithLifecycle().value
        AddExpenseDialog(
            transactionType = showAddDialogType!!,
            friends = tripParticipants,
            trips = listOf(trip),
            tags = tags,
            initialTripId = tripId,
            defaultCurrency = trip.currency,
            currentUserName = resolvedMe,
            defaultPayerId = localMeId,
            participantBalances = participantBalances,
            onDismiss = { showAddDialogType = null },
            onSaveTag = { tag -> 
                viewModel.addTag(tag)
            },
            onSave = { description, amount, _, paidById, splits, tagId, timestamp ->
                viewModel.addExpense(description, amount, tripId, paidById, splits, tagId, timestamp)
                showAddDialogType = null
            }
        )
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSimplifyDebts by remember { mutableStateOf(false) }
    var settlementToProcess by remember { mutableStateOf<Settlement?>(null) }
    var settlementAmountInput by remember { mutableStateOf("") }

    var expenseSelectedForAction by remember { mutableStateOf<com.journeybills.data.ExpenseEntity?>(null) }
    var showExpenseOptions by remember { mutableStateOf(false) }
    var showDeleteExpenseConfirm by remember { mutableStateOf(false) }
    var showEditExpenseDialog by remember { mutableStateOf(false) }
    var participantSelectedForRemoval by remember { mutableStateOf<FriendBalanceEntity?>(null) }
    var showOwnerOptions by remember { mutableStateOf(false) }

    val isOnline = rememberIsOnline(context)
    var isSyncing by remember { mutableStateOf(false) }
    val syncTrigger by viewModel.syncTrigger.collectAsStateWithLifecycle()
    var currentSyncFileId by remember(tripId, syncTrigger) { mutableStateOf(viewModel.getTripSyncFileId(tripId)) }
    
    val isDriveDeleted = remember(tripId, syncTrigger) { viewModel.isDriveFileDeletedFlagSelected(tripId) }
    var showDriveDeletedDialog by remember { mutableStateOf(isDriveDeleted) }
    var showManageSyncDialog by remember { mutableStateOf(false) }
    var showDeleteBackupConfirm by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(isDriveDeleted) {
        if (isDriveDeleted) {
            showDriveDeletedDialog = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(tripId, currentSyncFileId, isOnline) {
        if (currentSyncFileId != null && isOnline) {
            viewModel.syncManager.autoSyncIfConnected(tripId, currentSyncFileId, viewModel)
            
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                try {
                    viewModel.checkDriveBackupExists(account, tripId)
                } catch (e: Exception) {
                    // Ignore transient exceptions on initial check
                }
            }
        }
    }
    
    val lastSyncTime = remember(tripId, currentSyncFileId, expenses, isSyncing, syncTrigger) { viewModel.getTripSyncLastTime(tripId) }
    val syncFolderId = remember(tripId, currentSyncFileId, isSyncing) { viewModel.getTripSyncFolderId(tripId) }
    val hasUnsynced = remember(tripId, currentSyncFileId, expenses, isSyncing, syncTrigger) { viewModel.hasUnsyncedChanges(tripId) }
    
    val driveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            if (!isNetworkAvailable(context)) {
                android.widget.Toast.makeText(context, "Internet is not available. Cannot sync trip.", android.widget.Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                isSyncing = true
                viewModel.viewModelScope.launch {
                    try {
                        val fileId = kotlinx.coroutines.withTimeoutOrNull(60000) {
                            viewModel.syncTripToDrive(account, tripId)
                        }
                        isSyncing = false
                        if (fileId != null) {
                            currentSyncFileId = fileId
                            android.widget.Toast.makeText(context, "Trip Synced!", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "Sync failed or timed out after 1 minute: check space, permissions, or network status.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (e: com.journeybills.data.DriveFileDeletedException) {
                        isSyncing = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isSyncing = false
                android.widget.Toast.makeText(context, "Sign-in failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            isSyncing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = trip.name, 
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        val formattedDate = remember(trip.timestamp) {
                            java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(trip.timestamp))
                        }
                        Text(
                            text = "Created: $formattedDate • Default: ${trip.currency}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = {
                            if (!isNetworkAvailable(context)) {
                                android.widget.Toast.makeText(context, "Internet is not available. Cannot sync.", android.widget.Toast.LENGTH_LONG).show()
                                return@IconButton
                            }
                            if (currentSyncFileId != null && (hasUnsynced || isDriveDeleted)) {
                                val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                                if (account != null) {
                                    isSyncing = true
                                    viewModel.viewModelScope.launch {
                                        try {
                                            if (isDriveDeleted) {
                                                viewModel.removeSyncAssociation(tripId)
                                                val newFileId = kotlinx.coroutines.withTimeoutOrNull(60000) {
                                                    viewModel.syncTripToDrive(account, tripId)
                                                }
                                                isSyncing = false
                                                if (newFileId != null) {
                                                    currentSyncFileId = newFileId
                                                    android.widget.Toast.makeText(context, "Trip Synced!", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "Sync failed or timed out after 1 minute.", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                val fileId = kotlinx.coroutines.withTimeoutOrNull(60000) {
                                                    viewModel.syncTripToDrive(account, tripId)
                                                }
                                                isSyncing = false
                                                if (fileId != null) {
                                                    currentSyncFileId = fileId
                                                    android.widget.Toast.makeText(context, "Trip Synced!", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(context, "Sync failed or timed out after 1 minute.", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        } catch (e: com.journeybills.data.DriveFileDeletedException) {
                                            isSyncing = false
                                        } catch (e: Exception) {
                                            isSyncing = false
                                            android.widget.Toast.makeText(context, "Sync Error.", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            } else if (currentSyncFileId != null) {
                                showManageSyncDialog = true
                            } else {
                                viewModel.viewModelScope.launch {
                                    val client = viewModel.syncManager.getSignInClient()
                                    client.signOut().addOnCompleteListener {
                                        driveLauncher.launch(client.signInIntent)
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Rounded.CloudSync, contentDescription = "Sync to Google Drive", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { 
                        val resolvedMe = viewModel.userName.value ?: "Me"
                        val currentContext = context
                        viewModel.viewModelScope.launch {
                            val pdfFile = kotlinx.coroutines.Dispatchers.IO.let {
                                kotlinx.coroutines.withContext(it) {
                                    PdfGenerator.generateTripReport(
                                        context = currentContext,
                                        trip = trip,
                                        friends = friends,
                                        expenses = tripExpenses,
                                        settlements = settlements,
                                        tags = fullTagsList,
                                        resolvedMe = resolvedMe
                                    )
                                }
                            }
                            if (pdfFile != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    currentContext,
                                    "${currentContext.packageName}.fileprovider",
                                    pdfFile
                                )
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    type = "application/pdf"
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Trip Report")
                                currentContext.startActivity(shareIntent)
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share Balances", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete Trip", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTransactionSelector = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black,
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Expense", modifier = Modifier.size(16.dp))
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
            // Google Drive backup & sync status banner
            if (currentSyncFileId != null) {
                val bannerBgColor = if (isDriveDeleted) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                } else if (!isOnline) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                } else if (hasUnsynced) {
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.40f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                }

                val bannerIcon = if (isDriveDeleted) {
                    Icons.Rounded.CloudOff
                } else if (!isOnline) {
                    Icons.Rounded.CloudOff
                } else if (hasUnsynced) {
                    Icons.Rounded.CloudQueue
                } else {
                    Icons.Rounded.CloudDone
                }

                val bannerIconTint = if (isDriveDeleted) {
                    RedNegative
                } else if (!isOnline) {
                    RedNegative
                } else if (hasUnsynced) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    com.journeybills.ui.theme.getGreenPositive()
                }

                val bannerTitle = if (isDriveDeleted) {
                    "Backup Missing/Deleted"
                } else if (!isOnline) {
                    "Sync Paused (Offline)"
                } else if (hasUnsynced) {
                    "Unsynced Changes"
                } else {
                    "Backup & Sync Active"
                }

                val bannerTitleColor = if (isDriveDeleted) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else if (!isOnline) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else if (hasUnsynced) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }

                val subtitleText = if (isDriveDeleted) {
                    "The Drive backup was moved to trash. Please click the sync icon above to re-upload it."
                } else if (!isOnline) {
                    if (lastSyncTime != null) {
                        "You are offline. Last synced: $lastSyncTime. Your changes will sync automatically when network is restored."
                    } else {
                        "You are offline. Your changes are saved locally and will sync when network is restored."
                    }
                } else if (hasUnsynced) {
                    if (lastSyncTime != null) {
                        "Changes have been made locally since last sync ($lastSyncTime). Tap the Sync button at the top right to upload them."
                    } else {
                        "Changes have been made locally but not yet uploaded to Google Drive. Tap the Sync button to upload."
                    }
                } else {
                    if (lastSyncTime != null) {
                        "Successfully uploaded to Google Drive. Last synced: $lastSyncTime."
                    } else {
                        "Successfully uploaded to Google Drive and in sync with local database."
                    }
                }

                val bannerSubtitleColor = bannerTitleColor.copy(alpha = 0.82f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(bannerBgColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = bannerIcon,
                        contentDescription = bannerTitle,
                        tint = bannerIconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bannerTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = bannerTitleColor
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (lastSyncTime != null) "Last: $lastSyncTime" else "Never synced",
                                style = MaterialTheme.typography.labelSmall,
                                color = bannerSubtitleColor
                            )
                            if (hasUnsynced) {
                                Text(
                                    text = " • Unsynced changes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        /*
                        if (syncFolderId != null && syncFolderId.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "👥 Share",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = bannerTitleColor,
                                modifier = Modifier.clickable {
                                    // showShareDialog = true
                                }
                            )
                        }
                        */
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudQueue,
                        contentDescription = "Not backup synced",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud Sync Available",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap the Cloud Sync icon has on the top right to backup your trip database safely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Text("Participants", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                val purpleBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.getCardPurple(), com.journeybills.ui.theme.getCardPink()))
                val allCircles = (if (isOwnerRemoved) emptyList() else listOf(null)) + tripParticipants.map { it.id }
                val sortedCircles = allCircles.sortedByDescending { it == localMeId }
                
                sortedCircles.forEachIndexed { index, cId ->
                    if (index > 0) Spacer(modifier = Modifier.width(12.dp))
                    
                    if (cId == null) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(if (localMeId == null) purpleBrush else androidx.compose.ui.graphics.SolidColor(com.journeybills.ui.theme.CardYellow), CircleShape)
                                .clickable { showOwnerOptions = true },
                            contentAlignment = Alignment.Center
                        ) {
                            val avatarChar = if (resolvedMe == "Me") "Me" else resolvedMe.firstOrNull()?.toString()?.uppercase() ?: "M"
                            Text(avatarChar, color = Color.Black, style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        val p = tripParticipants.find { it.id == cId }!!
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(if (localMeId == cId) purpleBrush else androidx.compose.ui.graphics.SolidColor(com.journeybills.ui.theme.CardYellow), CircleShape)
                                .clickable { participantSelectedForRemoval = p },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(p.name.firstOrNull()?.toString()?.uppercase() ?: "F", color = Color.Black, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surface, CircleShape).clickable { showAddFriendToTrip = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = "Add Friend", tint = MaterialTheme.colorScheme.primary)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            val pagerState = rememberPagerState(pageCount = { 3 })
            val coroutineScope = rememberCoroutineScope()
            val tabs = listOf("Balances", "Expenses", "Activity")
            
            androidx.compose.material3.TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                tabs.forEachIndexed { index, title ->
                    androidx.compose.material3.Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(title) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                if (page == 0) {
                    if (tripExpenses.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No expenses yet.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f))
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                                items(allTripMembers) { memberId ->
                                    val b = participantBalances[memberId] ?: UserTripBalance()
                                    val rawName = if (memberId == null) resolvedMe else friends.find { it.id == memberId }?.name ?: "Unknown"
                                    val nameStr = if (memberId == localMeId) "$rawName (Me)" else rawName
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                                            .clip(RoundedCornerShape(24.dp))
                                            .clickable(enabled = b.net < -0.01) {
                                                val s = settlements.firstOrNull { it.fromId == memberId || it.toId == memberId }
                                                if (s != null) {
                                                    settlementToProcess = s
                                                    settlementAmountInput = s.amount.toString()
                                                } else if (memberId != null) {
                                                    val isOwed = b.net > 0 
                                                    settlementToProcess = Settlement(
                                                        fromId = if (isOwed) null else memberId,
                                                        toId = if (isOwed) memberId else null,
                                                        amount = java.math.BigDecimal(kotlin.math.abs(b.net).toString()).setScale(2, java.math.RoundingMode.HALF_UP)
                                                    )
                                                    settlementAmountInput = String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(b.net))
                                                }
                                            }
                                            .padding(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val pinkBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.getCardPink(), MaterialTheme.colorScheme.primary))
                                            Box(modifier = Modifier.size(56.dp).background(pinkBrush, CircleShape), contentAlignment = Alignment.Center) {
                                                Text(nameStr.firstOrNull()?.toString()?.uppercase() ?: "F", style = MaterialTheme.typography.titleLarge, color = Color.Black)
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(text = nameStr, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                                                Text(text = "Spent: ${formatTripCurrency(b.spent)}", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f), style = MaterialTheme.typography.bodyMedium)
                                                Text(text = "Share: ${formatTripCurrency(b.share)}", color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f), style = MaterialTheme.typography.bodyMedium)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                val net = b.net
                                                val color = if (net > 0.01) com.journeybills.ui.theme.getGreenPositive() else if (net < -0.01) RedNegative else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                                val textStr = if (net > 0.01) "Gets back" else if (net < -0.01) "Owes" else "Settled"
                                                Text(textStr, style = MaterialTheme.typography.labelSmall, color = color)
                                                Text(formatTripCurrency(kotlin.math.abs(net)), style = MaterialTheme.typography.titleMedium, color = color)
                                            }
                                        }
            
                                        val userSettlements = settlements.filter { it.fromId == memberId }
                                        if (userSettlements.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                            Spacer(modifier = Modifier.height(8.dp))
                                            userSettlements.forEach { settlement ->
                                                val toRawName = if (settlement.toId == null) resolvedMe else friends.find { it.id == settlement.toId }?.name ?: "Unknown"
                                                val toName = if (settlement.toId == localMeId) "$toRawName (Me)" else toRawName
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column {
                                                        Text("Owes $toName", style = MaterialTheme.typography.bodyMedium)
                                                        Text(formatTripCurrency(settlement.amount.toDouble()), style = MaterialTheme.typography.titleMedium, color = RedNegative)
                                                    }
                                                    TextButton(onClick = { 
                                                        settlementToProcess = settlement
                                                        settlementAmountInput = settlement.amount.toString() 
                                                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                                        Text("Settle Up")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (page == 1) {
                    if (tripExpenses.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No expenses yet.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f))
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                            items(tripExpenses.filter { !it.description.startsWith("Settlement:") }.sortedByDescending { it.timestamp }) { expense ->
                                val rawPayerName = if (expense.paidById == null) resolvedMe else friends.find { it.id == expense.paidById }?.name ?: "Unknown"
                                val payerName = if (expense.paidById == localMeId) "$rawPayerName (Me)" else rawPayerName
                                val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                val dateString = dateFormat.format(java.util.Date(expense.timestamp))
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                                        .clickable {
                                            expenseSelectedForAction = expense
                                            showExpenseOptions = true
                                        }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val tagObj = expense.tagId?.let { tid -> fullTagsList.find { it.id == tid } }
                                    Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                                        if (tagObj != null) {
                                            Text(tagObj.emoji, fontSize = 24.sp)
                                        } else {
                                            Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(expense.description, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                                        Text("Paid by $payerName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f))
                                        if (tagObj != null) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(dateString, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.4f))
                                                Text(" • ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.4f))
                                                androidx.compose.material3.Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(4.dp),
                                                    modifier = Modifier.padding(start = 4.dp)
                                                ) {
                                                    Text(tagObj.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                                }
                                            }
                                        } else {
                                            Text(dateString, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.4f))
                                        }
                                    }
                                    Text(formatTripCurrency(expense.amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                    }
                } else if (page == 2) {
                    if (tripActivities.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No activity yet.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f))
                        }
                    } else {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()

                        LaunchedEffect(listState) {
                            androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                                .collect { lastIndex ->
                                    if (lastIndex != null && tripActivities.isNotEmpty() && lastIndex >= tripActivities.size - 2) {
                                        viewModel.loadMoreActivities()
                                    }
                                }
                        }

                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                            items(tripActivities) { activity ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                                        val icon = when (activity.iconName) {
                                            "PersonAdd" -> Icons.Rounded.PersonAdd
                                            "Edit" -> Icons.Rounded.Edit
                                            "Delete" -> Icons.Rounded.Delete
                                            "ReceiptLong" -> Icons.AutoMirrored.Filled.ReceiptLong
                                            else -> Icons.Rounded.Notifications
                                        }
                                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(activity.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                                        Text(activity.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                                        Text(activity.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddFriendToTrip) {
        val nonParticipants = friends.filter { it.id !in tripParticipants.map { p -> p.id } }
        if (nonParticipants.isEmpty()) {
            AlertDialog(
                onDismissRequest = { showAddFriendToTrip = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("All Friends Added", color = MaterialTheme.colorScheme.onSurface) },
                text = { Text("All your friends are already in this trip. Add more friends from the main screen first.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                confirmButton = {
                    TextButton(onClick = { showAddFriendToTrip = false }) {
                        Text("OK", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        } else {
            var selectedFriendIds by remember { mutableStateOf(setOf<Int>()) }
            AlertDialog(
                onDismissRequest = { showAddFriendToTrip = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Add Friends to Trip", color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        nonParticipants.forEach { f ->
                            val isChecked = f.id in selectedFriendIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedFriendIds = if (isChecked) {
                                            selectedFriendIds - f.id
                                        } else {
                                            selectedFriendIds + f.id
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(f.name, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.addFriendsToTrip(tripId, selectedFriendIds.toList())
                            showAddFriendToTrip = false
                        },
                        enabled = selectedFriendIds.isNotEmpty()
                    ) {
                        Text(
                            text = "Add",
                            color = if (selectedFriendIds.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddFriendToTrip = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f))
                    }
                }
            )
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    if (showDeleteConfirm) {
        var confirmName by remember { mutableStateOf("") }
        var isSubmitted by remember { mutableStateOf(false) }
        var deleteDriveBackup by remember { mutableStateOf(false) }
        var isDeletingBackup by remember { mutableStateOf(false) }
        val isNameMatching = confirmName.trim().equals(trip.name, ignoreCase = true)
        val isError = isSubmitted && !isNameMatching

        AlertDialog(
            onDismissRequest = { if (!isDeletingBackup) showDeleteConfirm = false },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete Trip?", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    }
                ) {
                    Text("Are you sure you want to delete this trip and its history?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Type the trip name '${trip.name}' to confirm:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmName,
                        onValueChange = { confirmName = it.toCamelCasePreserveSpaces() },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = isError,
                        enabled = !isDeletingBackup,
                        supportingText = if (isError) { { Text("Does not match trip name", color = MaterialTheme.colorScheme.error) } } else null,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            errorLabelColor = MaterialTheme.colorScheme.error
                        )
                    )
                    
                    if (currentSyncFileId != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(enabled = !isDeletingBackup) { deleteDriveBackup = !deleteDriveBackup }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = deleteDriveBackup,
                                onCheckedChange = { if (!isDeletingBackup) deleteDriveBackup = it },
                                enabled = !isDeletingBackup,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.error,
                                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Also delete Google Drive backup folder",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "This will remove the synced data from your Google Drive storage.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (isDeletingBackup) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.error,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Deleting backup from Google Drive...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeletingBackup,
                    onClick = {
                        isSubmitted = true
                        if (isNameMatching) {
                            if (deleteDriveBackup && currentSyncFileId != null) {
                                if (!isNetworkAvailable(context)) {
                                    android.widget.Toast.makeText(context, "Internet is not available. Cannot delete cloud backup. Please check your connection and try again.", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                                    if (account != null) {
                                        isDeletingBackup = true
                                        viewModel.viewModelScope.launch {
                                            var isBackupDeletedSuccessfully = false
                                             try {
                                                 val success = kotlinx.coroutines.withTimeoutOrNull(20000) {
                                                     viewModel.deleteTripBackup(account, tripId)
                                                 }
                                                 if (success == true) {
                                                     isBackupDeletedSuccessfully = true
                                                 }
                                             } catch (e: Exception) {
                                                 e.printStackTrace()
                                             }
                                             
                                             if (isBackupDeletedSuccessfully) {
                                                 viewModel.deleteTrip(tripId)
                                                 isDeletingBackup = false
                                                 showDeleteConfirm = false
                                                 onBack()
                                                 android.widget.Toast.makeText(context, "Cloud backup and trip deleted successfully.", android.widget.Toast.LENGTH_SHORT).show()
                                             } else {
                                                 isDeletingBackup = false
                                                 android.widget.Toast.makeText(context, "Halted: Cloud deletion timed out or was unsuccessful. No local changes made.", android.widget.Toast.LENGTH_LONG).show()
                                             }
                                        }
                                    } else {
                                        viewModel.deleteTrip(tripId)
                                        showDeleteConfirm = false
                                        onBack()
                                    }
                                }
                            } else {
                                viewModel.deleteTrip(tripId)
                                showDeleteConfirm = false
                                onBack()
                            }
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeletingBackup,
                    onClick = { showDeleteConfirm = false }
                ) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f))
                }
            }
        )
    }

    if (showDriveDeletedDialog) {
        AlertDialog(
            onDismissRequest = { showDriveDeletedDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CloudOff,
                        contentDescription = "Backup Deleted",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Drive Backup Deleted", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            text = {
                Text(
                    text = "The Google Drive backup folder for this trip was intentionally deleted or is missing on Google Drive.\n\n" +
                            "Would you like to re-sync all local trip data to a new Google Drive backup folder, or stop syncing this trip?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDriveDeletedDialog = false
                        viewModel.clearDriveDeletedFlag(tripId)
                        viewModel.removeSyncAssociation(tripId)
                        currentSyncFileId = null
                        viewModel.viewModelScope.launch {
                            val client = viewModel.syncManager.getSignInClient()
                            client.signOut().addOnCompleteListener {
                                driveLauncher.launch(client.signInIntent)
                            }
                        }
                    }
                ) {
                    Text("Re-Sync", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDriveDeletedDialog = false
                        viewModel.removeSyncAssociation(tripId)
                        currentSyncFileId = null
                        android.widget.Toast.makeText(context, "Sync disabled for this trip.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Stop Syncing", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.8f))
                }
            }
        )
    }

    if (showDeleteBackupConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteBackupConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text("Delete Backup?", color = MaterialTheme.colorScheme.onSurface)
            },
            text = {
                Text(
                    text = "Are you sure you want to delete the Google Drive backup folder and stop syncing? This action cannot be undone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)
                        if (account != null) {
                            if (!isNetworkAvailable(context)) {
                                android.widget.Toast.makeText(context, "Internet is not available. Cannot delete cloud backup. Please check your connection and try again.", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                showDeleteBackupConfirm = false
                                isSyncing = true
                                viewModel.viewModelScope.launch {
                                    var isDeleted = false
                                    try {
                                        val success = kotlinx.coroutines.withTimeoutOrNull(20000) {
                                            viewModel.deleteTripBackup(account, tripId)
                                        }
                                        if (success == true) {
                                            isDeleted = true
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    isSyncing = false
                                    if (isDeleted) {
                                        currentSyncFileId = null
                                        android.widget.Toast.makeText(context, "Google Drive backup folder deleted and sync stopped.", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Operation failed or timed out after 20 seconds. Sync folder was not deleted and sync has not been stopped.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            viewModel.removeSyncAssociation(tripId)
                            currentSyncFileId = null
                            android.widget.Toast.makeText(context, "Sync association removed.", android.widget.Toast.LENGTH_SHORT).show()
                            showDeleteBackupConfirm = false
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBackupConfirm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        )
    }

    if (showManageSyncDialog) {
        AlertDialog(
            onDismissRequest = { showManageSyncDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CloudSync,
                        contentDescription = "Manage Sync",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Manage Backup & Sync", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            text = {
                Column {
                    Text(
                        text = "This trip is currently backed up and in sync with Google Drive.\n\n" +
                                "What would you like to do?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (syncFolderId != null && syncFolderId.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://drive.google.com/drive/folders/$syncFolderId")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Could not open Google Drive folder.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Launch,
                                contentDescription = "Launch Drive",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open Folder in Google Drive")
                        }
                    }
                }
            },
            confirmButton = {
                val myIdentity = viewModel.getMappedMyFriendId(tripId)
                if (myIdentity == null) {
                    TextButton(
                        onClick = {
                            showManageSyncDialog = false
                            showDeleteBackupConfirm = true
                        }
                    ) {
                        Text("Delete Backup & Stop Sync", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(
                        onClick = {
                            showManageSyncDialog = false
                            viewModel.removeSyncAssociation(tripId)
                            currentSyncFileId = null
                            android.widget.Toast.makeText(context, "Sync stopped locally.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Stop Syncing Locally", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showManageSyncDialog = false
                    }
                ) {
                    Text("Keep Syncing", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        )
    }

    if (settlementToProcess != null) {
        val fromName = if (settlementToProcess!!.fromId == null) resolvedMe else friends.find { it.id == settlementToProcess!!.fromId }?.name ?: "Unknown"
        val toName = if (settlementToProcess!!.toId == null) resolvedMe else friends.find { it.id == settlementToProcess!!.toId }?.name ?: "Unknown"

        AlertDialog(
            onDismissRequest = { settlementToProcess = null },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Settle Debt", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    }
                ) {
                    Text("$fromName paying $toName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settlementAmountInput,
                        onValueChange = { settlementAmountInput = it },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                val amount = settlementAmountInput.toDoubleOrNull()
                                if (amount != null && amount > 0) {
                                    viewModel.addExpense(
                                        description = "Settlement: $fromName paid $toName",
                                        amount = amount,
                                        tripId = tripId,
                                        paidById = settlementToProcess!!.fromId,
                                        splits = mapOf(settlementToProcess!!.toId to amount)
                                    )
                                    settlementToProcess = null
                                }
                            }
                        ),
                        singleLine = true,
                        label = { Text("Amount") },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = settlementAmountInput.toDoubleOrNull()
                    if (amount != null && amount > 0) {
                        viewModel.addExpense(
                            description = "Settlement: $fromName paid $toName",
                            amount = amount,
                            tripId = tripId,
                            paidById = settlementToProcess!!.fromId,
                            splits = mapOf(settlementToProcess!!.toId to amount)
                        )
                        settlementToProcess = null
                    }
                }) {
                    Text("Confirm", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { settlementToProcess = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f))
                }
            }
        )
    }

    if (showExpenseOptions && expenseSelectedForAction != null) {
        val expense = expenseSelectedForAction!!
        AlertDialog(
            onDismissRequest = { showExpenseOptions = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Expense Action",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "What would you like to do with the expense \"${expense.description}\"?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        showExpenseOptions = false
                        showDeleteExpenseConfirm = true
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            showExpenseOptions = false
                            showEditExpenseDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Edit", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showExpenseOptions = false 
                    expenseSelectedForAction = null
                }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        )
    }

    if (showDeleteExpenseConfirm && expenseSelectedForAction != null) {
        val expense = expenseSelectedForAction!!
        AlertDialog(
            onDismissRequest = { showDeleteExpenseConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Delete Expense?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${expense.description}\"? All participants' balances will be recalculated. This action cannot be undone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteExpenseConfirm = false
                    viewModel.deleteExpense(expense)
                    expenseSelectedForAction = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteExpenseConfirm = false 
                    expenseSelectedForAction = null
                }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        )
    }

    if (showEditExpenseDialog && expenseSelectedForAction != null) {
        val expense = expenseSelectedForAction!!
        
        // determine the transactionType
        val isTransfer = expense.description.startsWith("Debt Transfer:")
        val isIncome = expense.description.startsWith("Payment:")
        val editType = if (isTransfer) 2 else if (isIncome) 1 else 0
        
        val tags = viewModel.tags.collectAsStateWithLifecycle().value

        AddExpenseDialog(
            transactionType = editType,
            friends = tripParticipants,
            trips = emptyList(), // Disable trip selection
            tags = tags,
            initialTripId = tripId,
            initialExpense = expense,
            defaultCurrency = trip.currency,
            currentUserName = resolvedMe,
            defaultPayerId = localMeId,
            participantBalances = participantBalances,
            onDismiss = { 
                showEditExpenseDialog = false 
                expenseSelectedForAction = null
            },
            onSaveTag = { tag -> 
                viewModel.addTag(tag)
            },
            onSave = { description, amount, _, paidById, splitWithIds, tagId, timestamp ->
                viewModel.updateExpense(
                    expenseId = expense.id,
                    description = description,
                    amount = amount,
                    tripId = tripId,
                    paidById = paidById,
                    splits = splitWithIds,
                    tagId = tagId,
                    timestamp = timestamp
                )
                showEditExpenseDialog = false
                expenseSelectedForAction = null
            }
        )
    }

    if (participantSelectedForRemoval != null) {
        val f = participantSelectedForRemoval!!
        val netBalance = participantBalances[f.id]?.net ?: 0.0
        val isNonZero = kotlin.math.abs(netBalance) > 0.01
        
        AlertDialog(
            onDismissRequest = { participantSelectedForRemoval = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Friend Options: ${f.name}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    if (localMeId != f.id) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                prefs.edit().putInt("trip_me_id_$tripId", f.id).apply()
                                localMeId = f.id
                                participantSelectedForRemoval = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Mark as Me")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (isNonZero) {
                        Text(
                            text = "${f.name} still has a pending balance of ${formatTripCurrency(netBalance)} in this trip. They must be settled up before they can be removed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Are you sure you want to remove ${f.name} from this trip?",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (isNonZero) {
                    TextButton(onClick = { participantSelectedForRemoval = null }) {
                        Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = {
                        viewModel.removeFriendFromTrip(tripId, f.id)
                        participantSelectedForRemoval = null
                    }) {
                        Text("Remove Friend", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isNonZero) {
                    TextButton(onClick = { participantSelectedForRemoval = null }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        )
    }

    if (showOwnerOptions) {
        val ownerNetBalance = participantBalances[null]?.net ?: 0.0
        val isNonZero = kotlin.math.abs(ownerNetBalance) > 0.01
        
        AlertDialog(
            onDismissRequest = { showOwnerOptions = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Owner Options: $resolvedMe",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    if (localMeId != null) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                prefs.edit().remove("trip_me_id_$tripId").apply()
                                localMeId = null
                                showOwnerOptions = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Mark as Me")
                        }
                    } else if (isNonZero) {
                        Text("This participant has a non-zero balance. Settle all debts before removing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Are you sure you want to remove this participant from the trip?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                if (isNonZero) {
                    TextButton(onClick = { showOwnerOptions = false }) {
                        Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = {
                        prefs.edit().putBoolean("trip_owner_removed_$tripId", true).apply()
                        isOwnerRemoved = true
                        showOwnerOptions = false
                    }) {
                        Text("Remove from Trip", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isNonZero) {
                    TextButton(onClick = { showOwnerOptions = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendDetailScreen(
    friendId: Int,
    friends: List<FriendBalanceEntity>,
    trips: List<TripEntity>,
    onBack: () -> Unit,
    viewModel: JourneyViewModel
) {
    val friend = friends.find { it.id == friendId } ?: return
    val associatedTrips = trips.filter { trip ->
        trip.participantIds.split(",").filter { it.isNotBlank() }.map { it.toInt() }.contains(friendId)
    }

    val expenses by viewModel.expenses.collectAsStateWithLifecycle()
    val curBalances = remember(friendId, trips, expenses) {
        getFriendBalancesByCurrency(friendId, trips, expenses)
    }
    val nonZeroBalances = remember(curBalances) {
        curBalances.filter { kotlin.math.abs(it.value) > 0.01 }
    }
    val hasPendingBalances = remember(nonZeroBalances, friend.balance) {
        nonZeroBalances.isNotEmpty() || kotlin.math.abs(friend.balance) > 0.01
    }

    var showDeleteWarning by remember { mutableStateOf(false) }
    var showEditFriendSheet by remember { mutableStateOf(false) }

    if (showEditFriendSheet) {
        AddFriendDialog(
            initialName = friend.name,
            initialEmail = friend.email,
            onDismiss = { showEditFriendSheet = false },
            onSave = { updatedName, updatedEmail ->
                viewModel.updateFriend(friend.id, updatedName, updatedEmail)
                showEditFriendSheet = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(friend.name, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditFriendSheet = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit Friend")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp)) {
            // Friend Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pinkBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.getCardPink(), com.journeybills.ui.theme.getCardPurple()))
                Box(
                    modifier = Modifier.size(80.dp).background(pinkBrush, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = friend.name.firstOrNull()?.toString()?.uppercase() ?: "F",
                        color = Color.Black,
                        style = MaterialTheme.typography.displayMedium
                    )
                }
                Spacer(modifier = Modifier.width(20.dp))
                Column {
                    Text(text = friend.name, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(text = friend.email, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text("Associated Trips", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(16.dp))

            if (associatedTrips.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    Text("Not part of any active trips.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.5f))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                    items(associatedTrips) { trip ->
                        val yellowBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(com.journeybills.ui.theme.CardYellow, com.journeybills.ui.theme.CardGreen))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(56.dp).background(yellowBrush, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Text(text = trip.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { showDeleteWarning = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Delete Friend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDeleteWarning) {
        val formatter = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US)
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = if (hasPendingBalances) "Cannot Delete Friend" else "Delete Friend?",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (hasPendingBalances) {
                        if (nonZeroBalances.size > 1) {
                            "${friend.name} still has pending balances across multiple currencies. All balances must be settled and exactly zero before you can remove this friend."
                        } else if (nonZeroBalances.size == 1) {
                            val entry = nonZeroBalances.entries.first()
                            val symbol = getCurrencySymbol(entry.key)
                            val prefix = if (entry.value > 0) "+" else "-"
                            val displayAmt = "$prefix$symbol${String.format("%.2f", kotlin.math.abs(entry.value))}"
                            "${friend.name} still has a pending balance of $displayAmt. All balances must be exactly zero before you can remove this friend."
                        } else {
                            "${friend.name} still has a pending balance of ${formatter.format(friend.balance)}. All balances must be exactly zero before you can remove this friend."
                        }
                    } else {
                        "Are you sure you want to delete ${friend.name}? They will be removed from your friends list. This action cannot be undone."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                if (hasPendingBalances) {
                    TextButton(onClick = { showDeleteWarning = false }) {
                        Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = {
                        showDeleteWarning = false
                        viewModel.deleteFriend(friendId)
                        onBack()
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!hasPendingBalances) {
                    TextButton(onClick = { showDeleteWarning = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        )
    }
}

fun isNetworkAvailable(context: android.content.Context): Boolean {
    val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    if (connectivityManager != null) {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities != null) {
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return true
            }
        }
    }
    return false
}

@Composable
fun rememberIsOnline(context: android.content.Context): Boolean {
    val isOnline = remember { mutableStateOf(isNetworkAvailable(context)) }
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                isOnline.value = true
            }
            override fun onLost(network: android.net.Network) {
                isOnline.value = false
            }
        }
        if (connectivityManager != null) {
            try {
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            } catch (e: Exception) {
                // Fail-safe
            }
        }
        onDispose {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Fail-safe
            }
        }
    }
    return isOnline.value
}