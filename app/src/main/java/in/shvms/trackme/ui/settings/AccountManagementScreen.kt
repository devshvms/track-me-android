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
import androidx.compose.material.icons.filled.Download
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
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory((LocalContext.current.applicationContext as TrackMeApp))
    )
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val user by viewModel.currentUser.collectAsState()
    var isPrivacyExpanded by remember { mutableStateOf(false) }
    var showSignOutWarning by remember { mutableStateOf(false) }
    var showDeleteDataWarning by remember { mutableStateOf(false) }
    var showDeleteAccountWarning by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var showExportScheduledDialog by remember { mutableStateOf(false) }
    var exportDialogTitle by remember { mutableStateOf("") }
    var exportDialogMessage by remember { mutableStateOf("") }
    var exportDownloadUrl by remember { mutableStateOf<String?>(null) }


    
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
                title = { Text(strings.accountManagement) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = strings.back)
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
                Text(user?.displayName ?: strings.guest, style = MaterialTheme.typography.titleLarge)
                Text(user?.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { navController.navigate("emergency_setup") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.configureEmergencySetup)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedButton(
                onClick = { showSignOutWarning = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.signOut)
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
                        Text(strings.privacyAndSecurity, style = MaterialTheme.typography.titleMedium)
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
                                    text = strings.privacyPolicyText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            OutlinedButton(
                                onClick = {
                                    if (!isExporting) {
                                        isExporting = true
                                        scope.launch {
                                            val result = viewModel.requestCompleteDataExport()
                                            isExporting = false
                                            if (result.isSuccess && result.getOrNull() != null) {
                                                when (val statusRes = result.getOrNull()!!) {
                                                    is SettingsViewModel.ExportRequestResult.Completed -> {
                                                        exportDialogTitle = "Archive Ready for Download 📦"
                                                        exportDialogMessage = "Your complete data archive has been processed and is ready for download."
                                                        exportDownloadUrl = statusRes.downloadUrl
                                                        showExportScheduledDialog = true
                                                    }
                                                    is SettingsViewModel.ExportRequestResult.Queued -> {
                                                        exportDialogTitle = if (statusRes.isExisting) "Archive Request Processing ⏳" else "Archive Export Scheduled ⏳"
                                                        exportDialogMessage = statusRes.message
                                                        exportDownloadUrl = null
                                                        showExportScheduledDialog = true
                                                    }

                                                }
                                            } else {

                                                android.widget.Toast.makeText(
                                                    context,
                                                    result.exceptionOrNull()?.message ?: strings.dataExportFailed,
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },

                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isExporting
                            ) {
                                if (isExporting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(strings.downloadAllMyData)
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedButton(
                                onClick = { showDeleteDataWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(strings.deleteCloudData)
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Button(
                                onClick = { showDeleteAccountWarning = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(strings.deleteAccountAndData)
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
            title = { Text(strings.signOutWarningTitle) },
            text = { Text(strings.signOutWarningText) },
            confirmButton = {
                TextButton(onClick = { 
                    showSignOutWarning = false
                    viewModel.signOut()
                }) {
                    Text(strings.signOut)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutWarning = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showDeleteDataWarning) {
        AlertDialog(
            onDismissRequest = { showDeleteDataWarning = false },
            title = { Text(strings.deleteCloudDataTitle) },
            text = { Text(strings.deleteCloudDataWarningText) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDataWarning = false
                    scope.launch {
                        val result = viewModel.deleteCloudData()
                        if (result.isSuccess) {
                            android.widget.Toast.makeText(context, strings.cloudDataDeletedSuccess, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, strings.cloudDataDeletedFailed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text(strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDataWarning = false }) {
                    Text(strings.cancel)
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
            title = { Text(strings.deleteAccountTitle) },
            text = {
                Column {
                    Text(strings.deleteAccountWarningText, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        label = { Text(strings.whyLeavingOptional) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isDeleting
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(strings.confirmTypeDelete, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                                android.widget.Toast.makeText(context, strings.accountDeletedSuccess, android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                val err = result.exceptionOrNull()?.message ?: strings.unknown
                                android.widget.Toast.makeText(context, "${strings.accountDeletedFailed}$err", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isDeleting && confirmText == "DELETE"
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings.deleteEverything, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountWarning = false }, enabled = !isDeleting) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showExportScheduledDialog) {
        AlertDialog(
            onDismissRequest = { showExportScheduledDialog = false },
            title = { Text(exportDialogTitle) },
            text = { Text(exportDialogMessage) },
            confirmButton = {
                if (exportDownloadUrl != null) {
                    Button(onClick = {
                        showExportScheduledDialog = false
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(exportDownloadUrl)
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "Could not open browser for download",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Text("Download .zip")
                    }
                } else {
                    Button(onClick = { showExportScheduledDialog = false }) {
                        Text(strings.ok)
                    }
                }
            },
            dismissButton = {
                if (exportDownloadUrl != null) {
                    TextButton(onClick = { showExportScheduledDialog = false }) {
                        Text(strings.cancel)
                    }
                }
            }
        )
    }
}


