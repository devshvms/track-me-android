package com.example.trackme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.trackme.theme.TrackMeTheme

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val app = applicationContext as TrackMeApp
    lifecycleScope.launch {
        app.authManager.currentUser.collect { user ->
            if (user != null) {
                app.firestoreSyncManager.syncEmergencyConfigDownstream()
            }
        }
    }

    enableEdgeToEdge()
    setContent {
      TrackMeTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  override fun onResume() {
      super.onResume()
      val app = applicationContext as TrackMeApp
      lifecycleScope.launch {
          val settings = app.database.emergencyDao().getSettings()
          if (settings?.isSetupComplete == true) {
              if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                  app.database.emergencyDao().updateSettings(settings.copy(isSetupComplete = false))
              }
          }
      }
  }
}
