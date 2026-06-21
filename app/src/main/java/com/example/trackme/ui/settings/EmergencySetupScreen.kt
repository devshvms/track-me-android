package com.example.trackme.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.trackme.TrackMeApp
import com.example.trackme.data.local.entity.EmergencyContactEntity
import com.example.trackme.data.local.entity.EmergencySettingsEntity

@SuppressLint("Range")
fun getPhoneContactInfo(context: Context, uri: android.net.Uri): Pair<String?, String?> {
    var name: String? = null
    var phone: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIndex != -1) name = it.getString(nameIndex)
            if (numberIndex != -1) phone = it.getString(numberIndex)
        }
    }
    return Pair(name, phone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencySetupScreen(
    navController: NavController,
    viewModel: EmergencySettingsViewModel = viewModel(
        factory = EmergencySettingsViewModelFactory(LocalContext.current.applicationContext as TrackMeApp)
    )
) {
    val settings by viewModel.settings.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    
    var currentStep by remember { mutableIntStateOf(0) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Emergency Broadcast") },
                actions = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith slideOutHorizontally { width -> -width } + fadeOut()
                }, label = "Wizard Transition"
            ) { step ->
                when (step) {
                    0 -> ContactsStep(viewModel, contacts) { currentStep = 1 }
                    1 -> PermissionAndTestStep(viewModel, contacts) { currentStep = 2 }
                    2 -> AcknowledgmentStep(settings, viewModel) { navController.popBackStack() }
                }
            }
        }
    }
}

@Composable
fun ContactsStep(viewModel: EmergencySettingsViewModel, contacts: List<EmergencyContactEntity>, onNext: () -> Unit) {
    val context = LocalContext.current
    val pickPhoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val (name, phone) = getPhoneContactInfo(context, uri)
                if (name != null && phone != null) {
                    if (contacts.size < 5) {
                        viewModel.addContact(name, phone, "SMS")
                    }
                }
            }
        }
    }
    
    Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
        Text("Trusted Contacts", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Even in remote areas without internet, standard SMS has a much higher chance of reaching cell towers.", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Add up to 5 contacts who will receive your emergency broadcast.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                pickPhoneLauncher.launch(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = contacts.size < 5
        ) {
            Text("Add from Phonebook")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts) { contact ->
                ListItem(
                    headlineContent = { Text(contact.name) },
                    supportingContent = { Text(contact.phoneNumber) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.deleteContact(contact) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                )
            }
        }
        
        Button(
            onClick = onNext, 
            modifier = Modifier.fillMaxWidth(), 
            enabled = contacts.isNotEmpty()
        ) {
            Text(if (contacts.isEmpty()) "Add at least 1 contact" else "Continue")
        }
    }
}

@Composable
fun PermissionAndTestStep(viewModel: EmergencySettingsViewModel, contacts: List<EmergencyContactEntity>, onNext: () -> Unit) {
    val context = LocalContext.current
    var permissionGranted by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) 
    }
    var testSent by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        permissionGranted = isGranted
    }
    
    Column(modifier = Modifier.padding(24.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Permissions & Testing", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("To send automated messages during an emergency, TrackMe needs permission to send SMS messages on your behalf.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        
        if (permissionGranted) {
            Text("Permission Granted! ✅", color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(24.dp))
            
            if (!testSent) {
                Button(
                    onClick = {
                        try {
                            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.getSystemService(SmsManager::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsManager.getDefault()
                            }
                            val firstContact = contacts.firstOrNull()
                            if (firstContact != null) {
                                val dateTime = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                                val name = user?.displayName?.takeIf { it.isNotBlank() } 
                                    ?: user?.email?.substringBefore("@") 
                                    ?: "A TrackMe user"
                                
                                val message = "Hello, I have added you as my emergency contact in TrackMe.\nYou are receiving this SMS as part of a test to validate the contact.\nThanks,\n$name\n$dateTime"
                                smsManager.sendTextMessage(firstContact.phoneNumber, null, message, null, null)
                                viewModel.logTestMessage(message, firstContact.phoneNumber)
                                Log.d("EmergencySetup", "Test SMS dispatched")
                                testSent = true
                            }
                        } catch (e: Exception) {
                            Log.e("EmergencySetup", "Failed to send test SMS", e)
                        }
                    }, 
                    modifier = Modifier.fillMaxWidth()
                ) { 
                    Text("Send Test SMS to ${contacts.firstOrNull()?.name}") 
                }
            } else {
                Text("Test SMS Dispatched! ✅", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.SEND_SMS) }, modifier = Modifier.fillMaxWidth()) { Text("Grant SMS Permission") }
        }
    }
}

@Composable
fun AcknowledgmentStep(settings: EmergencySettingsEntity?, viewModel: EmergencySettingsViewModel, onFinish: () -> Unit) {
    var template by remember { mutableStateOf("") }
    
    LaunchedEffect(settings) {
        if (settings != null && template.isEmpty()) {
            template = settings.messageTemplate
        }
    }
    
    Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("How it works", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("In case of emergency, your phone will send your location to your selected contacts every 2 minutes for the first 10 minutes, then every 10 minutes for the next 1 hour, and then every 1 hour for the next 24 hours.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = template,
            onValueChange = { if (it.length <= 150) template = it },
            label = { Text("Emergency Message Template") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { 
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tap tags below to insert them into your message.")
                    Text("${template.length}/150")
                }
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Available Tags:", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        // Use a horizontally scrollable Row to keep tags inline and save vertical space
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tags = listOf("[Location Link]", "[Battery Percent]", "[Device Name]", "[Timestamp]")
            tags.forEach { tag ->
                val isPresent = template.contains(tag)
                AssistChip(
                    onClick = {
                        if (!isPresent) {
                            val space = if (template.isNotEmpty() && !template.endsWith(" ")) " " else ""
                            val newTemplate = "$template$space$tag"
                            if (newTemplate.length <= 150) {
                                template = newTemplate
                            }
                        }
                    },
                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                    enabled = !isPresent
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        } // End of scrollable column
        
        Button(
            onClick = {
                viewModel.completeSetupAndSync(template)
                onFinish()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}
