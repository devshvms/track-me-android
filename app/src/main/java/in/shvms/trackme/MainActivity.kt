package `in`.shvms.trackme

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import `in`.shvms.trackme.theme.TrackMeTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

  /**
   * Receives the result of Play's in-app update flow. Registered as a field so it is in place
   * before `onCreate` finishes, as the Activity Result API requires. The result itself needs no
   * handling — a cancelled or failed update simply leaves the prompt to reappear on the next
   * check.
   */
  private val updateLauncher =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
      handleGroupInvite(intent)
    val app = applicationContext as TrackMeApp

    enableEdgeToEdge()
    setContent {
      val themeMode by app.preferencesManager.themeMode.collectAsState()
      val dynamicColor by app.preferencesManager.dynamicColor.collectAsState()
      val appLanguage by app.preferencesManager.appLanguage.collectAsState()
      val appStrings = remember(appLanguage) { getAppStrings(appLanguage) }
      val updatePrompt by app.appUpdateChecker.prompt.collectAsState()
      val updateReadyToInstall by app.appUpdateChecker.readyToInstall.collectAsState()
      val ageDecision by app.ageSignalManager.decision().collectAsState()
      val sosRemovalNotice by app.sosRemovalNotice.collectAsState()

      CompositionLocalProvider(LocalAppStrings provides appStrings) {
        TrackMeTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (ageDecision) {
              null -> AgeSignalCheckingScreen()
              AgeSignalDecision.BLOCKED -> AgeRestrictedScreen()
              AgeSignalDecision.ALLOWED -> {
                var showOnboarding by remember {
                  mutableStateOf(
                    app.onboardingState == `in`.shvms.trackme.ui.onboarding.OnboardingState.PENDING
                  )
                }
                if (showOnboarding) {
                  // Everything below is deliberately not composed underneath this. A fresh install
                  // has nothing to update from and no legacy SOS state to acknowledge, and a
                  // dialog over the first screen someone ever sees would be its own answer to
                  // "what is this app like".
                  `in`.shvms.trackme.ui.onboarding.OnboardingScreen(
                    onFinish = { outcome ->
                      app.completeOnboarding(outcome)
                      showOnboarding = false
                    }
                  )
                } else {
                MainNavigation()
                updatePrompt?.let { prompt ->
                  `in`.shvms.trackme.ui.update.AppUpdateDialog(
                    prompt = prompt,
                    onUpdate = {
                      // Play installs in-app. Only if that flow can't start — a sideloaded build,
                      // or Play unavailable — do we fall back to opening the listing.
                      if (!app.appUpdateChecker.startUpdate(updateLauncher)) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt.updateUrl)))
                      }
                    },
                    onDismiss = { app.appUpdateChecker.dismissUpdate(prompt.latestVersionCode) }
                  )
                }
                if (updateReadyToInstall) {
                  `in`.shvms.trackme.ui.update.UpdateReadyDialog(
                    onRestart = { app.appUpdateChecker.completeUpdate() },
                    onDismiss = { app.appUpdateChecker.dismissInstallPrompt() }
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
  /**
   * Reads a group invite out of the launching intent.
   *
   * Runs on both `onCreate` and `onNewIntent`: a cold start delivers it to the former, and a tap
   * while the app is already open delivers it to the latter. Missing the second would mean the
   * button silently did nothing for anyone who happened to have the app in the background —
   * which is most people, most of the time.
   */
  private fun handleGroupInvite(intent: android.content.Intent?) {
      val data = intent?.data
      val invite = `in`.shvms.trackme.domain.group.GroupInviteLink.parse(
          uriString = data?.toString(),
          fragment = data?.fragment,
          queryCode = runCatching {
              data?.getQueryParameter(`in`.shvms.trackme.domain.group.GroupInviteLink.QUERY_CODE)
          }.getOrNull(),
          extraToken = intent?.getStringExtra(`in`.shvms.trackme.domain.group.GroupInviteLink.EXTRA_TOKEN),
          extraCode = intent?.getStringExtra(`in`.shvms.trackme.domain.group.GroupInviteLink.EXTRA_CODE),
      ) ?: return
      // Top of the link half of the join funnel. Fired on arrival rather than on join, so an
      // invite that opens the app and is then abandoned is distinguishable from one nobody tapped.
      `in`.shvms.trackme.analytics.AnalyticsManager.trackGroupInviteOpened(viaCode = false)
      (applicationContext as? TrackMeApp)?.setPendingGroupInvite(invite)
  }

  override fun onNewIntent(intent: android.content.Intent) {
      super.onNewIntent(intent)
      setIntent(intent)
      handleGroupInvite(intent)
  }

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
