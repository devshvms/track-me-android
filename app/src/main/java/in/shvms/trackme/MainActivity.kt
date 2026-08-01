package `in`.shvms.trackme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import `in`.shvms.trackme.theme.TrackMeTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import `in`.shvms.trackme.ui.localization.getAppStrings

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
      val themeMode by app.preferencesManager.themeMode.collectAsState()
      val dynamicColor by app.preferencesManager.dynamicColor.collectAsState()
      val appLanguage by app.preferencesManager.appLanguage.collectAsState()
      val appStrings = remember(appLanguage) { getAppStrings(appLanguage) }
      val updateInfo by app.appUpdateChecker.updateInfo.collectAsState()

      CompositionLocalProvider(LocalAppStrings provides appStrings) {
        TrackMeTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation()
            updateInfo?.let { info ->
              `in`.shvms.trackme.ui.update.AppUpdateDialog(
                updateInfo = info,
                onDismiss = { app.appUpdateChecker.dismissUpdate(info.latestVersionCode) }
              )
            }
          }
        }
      }
    }
  }

  override fun onResume() {
      super.onResume()
      val app = applicationContext as TrackMeApp
      // Service restoration belongs to the foreground lifecycle. Starting from onCreate can
      // race the activity launch and is rejected by Android 12+ background-start policy.
      app.resumePersistedTrackingIfNeeded()
      val locationPermissionGranted = ContextCompat.checkSelfPermission(
          this@MainActivity,
          android.Manifest.permission.ACCESS_FINE_LOCATION
      ) == android.content.pm.PackageManager.PERMISSION_GRANTED
      if (locationPermissionGranted) {
          app.setLocationPermissionRevokedNotice(false)
      }
      // B2: surface a weekly recap for a just-completed week (shared foreground trigger).
      app.checkWeeklyRecap()
      lifecycleScope.launch {
          val settings = app.database.emergencyDao().getSettings()
          val smsPermissionGranted = ContextCompat.checkSelfPermission(
              this@MainActivity,
              android.Manifest.permission.SEND_SMS
          ) == android.content.pm.PackageManager.PERMISSION_GRANTED
          if (settings?.isSetupComplete == true && !smsPermissionGranted) {
              app.setSmsPermissionRevokedNotice(true)
              app.database.emergencyDao().updateSettings(settings.copy(isSetupComplete = false))
          } else if (smsPermissionGranted) {
              app.setSmsPermissionRevokedNotice(false)
          }
      }
  }
}
