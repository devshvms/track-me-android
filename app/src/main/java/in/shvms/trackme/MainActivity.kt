package `in`.shvms.trackme

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Rational
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import `in`.shvms.trackme.data.AgeSignalDecision
import `in`.shvms.trackme.ui.agegate.AgeRestrictedScreen
import `in`.shvms.trackme.ui.agegate.AgeSignalCheckingScreen
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.service.TrackingService
import `in`.shvms.trackme.ui.home.components.PiPDashboard
import `in`.shvms.trackme.ui.home.components.PiPDashboardStateSource
import `in`.shvms.trackme.ui.home.components.PiPEntryTrigger
import `in`.shvms.trackme.ui.home.components.PiPModePolicy
import `in`.shvms.trackme.ui.home.components.PiPRemoteActionKind
import `in`.shvms.trackme.ui.home.components.PiPRideState
import `in`.shvms.trackme.ui.home.components.PiPSessionDurationBucket
import `in`.shvms.trackme.ui.home.components.toPiPRideState

class MainActivity : ComponentActivity() {

  private var pipMode by mutableStateOf(false)
  private var pipEligible = false
  private var pipRideState = PiPRideState.INACTIVE
  private var pendingPiPTrigger: PiPEntryTrigger? = null
  private var pipSessionStartedElapsedMillis: Long? = null
  private lateinit var pipDashboardStateSource: PiPDashboardStateSource

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

    pipDashboardStateSource = PiPDashboardStateSource(
      trackingManager = app.trackingManager,
      preferencesManager = app.preferencesManager,
      alertStore = app.pipAlertStore,
      scope = lifecycleScope,
    )
    observePiPLifecycle(app)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      pipMode = isInPictureInPictureMode
    }

    enableEdgeToEdge()
    setContent {
      if (pipMode) {
        val state by pipDashboardStateSource.state.collectAsState()
        PiPDashboard(state = state)
      } else {
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

  /** Keeps Android's auto-enter bit and the single Pause/Resume action aligned to live ride state. */
  private fun observePiPLifecycle(app: TrackMeApp) {
      lifecycleScope.launch {
          repeatOnLifecycle(Lifecycle.State.STARTED) {
              combine(
                  app.trackingManager.trackingState,
                  app.preferencesManager.pipDashboardEnabled,
              ) { trackingState, enabled -> trackingState.toPiPRideState() to enabled }
                  .collect { (rideState, enabled) ->
                      pipRideState = rideState
                      pipEligible = PiPModePolicy.isEligible(rideState, enabled) && supportsPiP()
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) updatePiPParams()
                      if (!pipEligible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                          isInPictureInPictureMode
                      ) {
                          // Ending/finalizing a ride closes the floating window. Finishing the
                          // task leaves the user's map in front instead of resurrecting TrackMe.
                          finishPiPSession()
                          finishAndRemoveTask()
                      }
                  }
          }
      }
  }

  private fun supportsPiP(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

  @RequiresApi(Build.VERSION_CODES.O)
  private fun updatePiPParams() {
      if (!supportsPiP()) return
      setPictureInPictureParams(buildPiPParams())
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun buildPiPParams(): PictureInPictureParams {
      val builder = PictureInPictureParams.Builder()
          .setAspectRatio(Rational(16, 9))
          .setActions(PiPModePolicy.remoteAction(pipRideState)?.let(::remoteAction)?.let(::listOf).orEmpty())
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          builder
              .setAutoEnterEnabled(pipEligible)
              .setSeamlessResizeEnabled(false)
      }
      return builder.build()
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun remoteAction(kind: PiPRemoteActionKind): RemoteAction {
      val app = applicationContext as TrackMeApp
      val strings = getAppStrings(app.preferencesManager.appLanguage.value)
      val (action, iconRes, label, requestCode) = when (kind) {
          PiPRemoteActionKind.PAUSE -> RemoteActionSpec(
              action = TrackingService.ACTION_PAUSE_SERVICE,
              iconRes = R.drawable.ic_notif_pause,
              label = strings.pauseTracking,
              requestCode = 41,
          )
          PiPRemoteActionKind.RESUME -> RemoteActionSpec(
              action = TrackingService.ACTION_START_OR_RESUME_SERVICE,
              iconRes = R.drawable.ic_notif_resume,
              label = strings.resumeTracking,
              requestCode = 42,
          )
      }
      val pendingIntent = PendingIntent.getService(
          this,
          requestCode,
          Intent(this, TrackingService::class.java).apply { this.action = action },
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      return RemoteAction(
          Icon.createWithResource(this, iconRes),
          label,
          label,
          pendingIntent,
      )
  }

  private data class RemoteActionSpec(
      val action: String,
      val iconRes: Int,
      val label: String,
      val requestCode: Int,
  )

  /** Android 8–11 fallback. Android 12+ uses setAutoEnterEnabled for gesture navigation. */
  override fun onUserLeaveHint() {
      super.onUserLeaveHint()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
          Build.VERSION.SDK_INT < Build.VERSION_CODES.S
      ) {
          val powerManager = getSystemService(PowerManager::class.java)
          if (!pipEligible || powerManager?.isInteractive != true) return
          pendingPiPTrigger = PiPEntryTrigger.USER_LEAVE_HINT
          if (!enterPictureInPictureMode(buildPiPParams())) pendingPiPTrigger = null
      }
  }

  override fun onPictureInPictureModeChanged(
      isInPictureInPictureMode: Boolean,
      newConfig: Configuration,
  ) {
      super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
      pipMode = isInPictureInPictureMode
      if (isInPictureInPictureMode) {
          pipSessionStartedElapsedMillis = SystemClock.elapsedRealtime()
          val trigger = pendingPiPTrigger
              ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  PiPEntryTrigger.AUTO_ENTER
              } else {
                  PiPEntryTrigger.USER_LEAVE_HINT
              }
          pendingPiPTrigger = null
          AnalyticsManager.trackPiPEntered(trigger.analyticsValue)
      } else {
          finishPiPSession()
      }
  }

  private fun finishPiPSession() {
      val started = pipSessionStartedElapsedMillis ?: return
      pipSessionStartedElapsedMillis = null
      val seconds = ((SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 1_000L)
      AnalyticsManager.trackPiPSession(
          PiPSessionDurationBucket.fromSeconds(seconds).analyticsValue,
      )
  }

  override fun onDestroy() {
      finishPiPSession()
      super.onDestroy()
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
