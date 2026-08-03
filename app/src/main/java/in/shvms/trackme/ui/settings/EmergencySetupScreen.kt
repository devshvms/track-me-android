package `in`.shvms.trackme.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import `in`.shvms.trackme.TrackMeApp
import `in`.shvms.trackme.ui.localization.LocalAppStrings

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

/**
 * TG-A07 (1.6.4): the SMS permission machinery, the real-SMS "test", and the dispatch
 * acknowledgment wizard are gone with the SOS feature. What remains is the trusted-contact
 * list itself — retained on purpose (the page is held for redesign), still capped at 5,
 * still synced through the emergency-config document so contacts survive sign-out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencySetupScreen(
    navController: NavController,
    viewModel: EmergencySettingsViewModel = viewModel(
        factory = EmergencySettingsViewModelFactory(LocalContext.current.applicationContext as TrackMeApp)
    )
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val contacts by viewModel.contacts.collectAsState()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        strings.trustedContactsTitle,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                actions = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = strings.close)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize()) {
            Text(strings.trustedContactsIntro, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                strings.emergencyContactSnapshotInfo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    pickPhoneLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = contacts.size < 5
            ) {
                Text(strings.trustedContactsAdd)
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.phoneNumber) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteContact(contact) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = strings.deleteContactFormat.format(contact.name)
                                )
                            }
                        }
                    )
                }
            }

            Button(
                onClick = {
                    viewModel.completeSetup()
                    navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = contacts.isNotEmpty()
            ) {
                Text(strings.done)
            }
        }
    }
}
