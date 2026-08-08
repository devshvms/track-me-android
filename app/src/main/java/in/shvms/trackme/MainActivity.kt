package `in`.shvms.trackme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import `in`.shvms.trackme.data.AgeSignalDecision
import `in`.shvms.trackme.ui.agegate.AgeRestrictedScreen
import `in`.shvms.trackme.ui.agegate.AgeSignalCheckingScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val app = applicationContext as TrackMeApp

    enableEdgeToEdge()
    setContent {
      val themeMode by app.preferencesManager.themeMode.collectAsState()
      val dynamicColor by app.preferencesManager.dynamicColor.collectAsState()
      val appLanguage by app.preferencesManager.appLanguage.collectAsState()
      val appStrings = remember(appLanguage) { getAppStrings(appLanguage) }
      val updateInfo by app.appUpdateChecker.updateInfo.collectAsState()
      val ageDecision by app.ageSignalManager.decision().collectAsState()
      val sosRemovalNotice by app.sosRemovalNotice.collectAsState()

      CompositionLocalProvider(LocalAppStrings provides appStrings) {
        TrackMeTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (ageDecision) {
              null -> AgeSignalCheckingScreen()
              AgeSignalDecision.BLOCKED -> AgeRestrictedScreen()
              AgeSignalDecision.ALLOWED -> {
                MainNavigation()
                updateInfo?.let { info ->
                  `in`.shvms.trackme.ui.update.AppUpdateDialog(
                    updateInfo = info,
                    onDismiss = { app.appUpdateChecker.dismissUpdate(info.latestVersionCode) }
                  )
                }
                // TG-A06: one-time, must-acknowledge notice for users who had completed
                // SOS setup before 1.6.4. Back press / outside tap must not dismiss it —
                // only the explicit acknowledgement clears it, permanently.
                if (sosRemovalNotice) {
                  androidx.compose.material3.AlertDialog(
                    onDismissRequest = { /* must acknowledge */ },
                    title = { androidx.compose.material3.Text(appStrings.sosRemovalNoticeTitle) },
                    text = {
                      androidx.compose.material3.Text(
                        text = appStrings.sosRemovalNoticeBody,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                      )
                    },
                    confirmButton = {
                      androidx.compose.material3.TextButton(
                        onClick = { app.acknowledgeSosRemovalNotice() }
                      ) {
                        androidx.compose.material3.Text(appStrings.sosRemovalNoticeAck)
                      }
                    }
                  )
                }
              }
            }
          }
        }
      }
    }
    if (!app.ageSignalManager.hasCheckedBefore()) {
      lifecycleScope.launch {
        app.ageSignalManager.checkAndPersist(this@MainActivity)
      }
    }
  }



  /**
   * §7.1's cadence lever. The relay picks 10s when a human is actually looking at the map and 20s
   * when the phone is pocketed — but only if the client says which, and nothing was saying. Left
   * unset, every member reported `foreground = false` forever and the group always ran at the
   * slower interval: the difference between a map that feels live and one that lags by twenty
   * seconds.
   */
  private fun setGroupForeground(inForeground: Boolean) {
      val app = applicationContext as? TrackMeApp ?: return
      app.groupSessionManager.isForeground = inForeground
      app.isAppInForeground = inForeground
      // A presence start refused while backgrounded (Android 12+ forbids a background
      // startForegroundService, and §16.4 keeps background location undeclared) is picked up here.
      if (inForeground) app.resumeGroupPresenceIfNeeded()
  }

  override fun onResume() {
      super.onResume()
      setGroupForeground(true)
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
  }
  override fun onPause() {
      super.onPause()
      setGroupForeground(false)
  }

}
