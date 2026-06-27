package `in`.shvms.trackme.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import `in`.shvms.trackme.TrackMeApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory((LocalContext.current.applicationContext as TrackMeApp))
    )
) {
    val user by viewModel.currentUser.collectAsState()
    var isPrivacyExpanded by remember { mutableStateOf(false) }
    var showSignOutWarning by remember { mutableStateOf(false) }
    var showDeleteDataWarning by remember { mutableStateOf(false) }
    var showDeleteAccountWarning by remember { mutableStateOf(false) }
    
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(user) {
        if (user == null) {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Management") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (user != null) {
                if (user?.photoUrl != null) {
                    Image(
                        painter = rememberAsyncImagePainter(user?.photoUrl),
                        contentDescription = "Profile Picture",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(100.dp)
                    ) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", modifier = Modifier.padding(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(user?.displayName ?: "User", style = MaterialTheme.typography.titleLarge)
                Text(user?.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { navController.navigate("emergency_setup") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Configure Emergency Setup")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = { showSignOutWarning = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign Out")
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isPrivacyExpanded = !isPrivacyExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Privacy and Security", style = MaterialTheme.typography.titleMedium)
                        Icon(
                            if (isPrivacyExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand"
                        )
                    }
                    
                    AnimatedVisibility(visible = isPrivacyExpanded) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                Text(
                                    text = "Privacy Policy\n\nYour data is completely under your control. We only store data locally by default. If you enable Cloud Sync, your rides are securely stored on our servers. You can permanently delete your synced data or your entire account at any time using the options below. Deleted data cannot be recovered.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            OutlinedButton(
                                onClick = { showDeleteDataWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete Cloud Data")
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Button(
                                onClick = { showDeleteAccountWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete Account & Data")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSignOutWarning) {
        AlertDialog(
            onDismissRequest = { showSignOutWarning = false },
            title = { Text("Sign Out Warning") },
            text = { Text("Signing out will clear all your synced rides from this phone's local history. They will remain safely in the cloud, and any new rides will be saved locally. Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(onClick = { 
                    showSignOutWarning = false
                    viewModel.signOut()
                }) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDataWarning) {
        AlertDialog(
            onDismissRequest = { showDeleteDataWarning = false },
            title = { Text("Delete Cloud Data") },
            text = { Text("Are you sure you want to delete all your synced rides from the cloud? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDataWarning = false
                    scope.launch {
                        val result = viewModel.deleteCloudData()
                        if (result.isSuccess) {
                            snackbarHostState.showSnackbar("Cloud data deleted successfully")
                        } else {
                            snackbarHostState.showSnackbar("Failed to delete cloud data")
                        }
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDataWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteAccountWarning) {
        var feedbackText by remember { mutableStateOf("") }
        var confirmText by remember { mutableStateOf("") }
        var isDeleting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteAccountWarning = false },
            title = { Text("Delete Account") },
            text = {
                Column {
                    Text("This is a permanent action. Your account and all cloud data will be deleted forever.", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        label = { Text("Why are you leaving? (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isDeleting
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("To confirm, type DELETE below:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isDeleting
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleting = true
                        scope.launch {
                            val result = viewModel.deleteAccountAndData(feedbackText)
                            isDeleting = false
                            showDeleteAccountWarning = false
                            if (result.isSuccess) {
                                snackbarHostState.showSnackbar("Account deleted successfully")
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                                snackbarHostState.showSnackbar("Failed to delete account: $err")
                            }
                        }
                    },
                    enabled = !isDeleting && confirmText == "DELETE"
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Delete Everything", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountWarning = false }, enabled = !isDeleting) {
                    Text("Cancel")
                }
            }
        )
    }
}
