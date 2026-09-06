package `in`.shvms.trackme.data.local

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import `in`.shvms.trackme.domain.model.RidePersona

class AppPreferencesManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)

    // Theme mode: 0 = System Default, 1 = Light, 2 = Dark
    private val _themeMode = MutableStateFlow(prefs.getInt("theme_mode", 0))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    // Material You wallpaper colors are opt-in so the TrackMe cyan palette stays the default.
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamic_color", false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    // Product analytics are opt-in. A missing key defaults to false so fresh installs and
    // existing installs upgrading from the pre-toggle build do not send telemetry implicitly.
    private val _telemetryEnabled = MutableStateFlow(prefs.getBoolean("telemetry_enabled", false))
    val telemetryEnabled: StateFlow<Boolean> = _telemetryEnabled.asStateFlow()

    // App language code: "en", "es", "fr", "de", "hi", "ja", "zh"
    private val _appLanguage = MutableStateFlow(
        AppLanguageCatalog.normalize(prefs.getString(APP_LANGUAGE_KEY, DEFAULT_LANGUAGE))
            ?: DEFAULT_LANGUAGE
    )
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _unitSystem = MutableStateFlow(prefs.getString("unit_system", null) ?: defaultUnitFromLocale())
    val unitSystem: StateFlow<String> = _unitSystem.asStateFlow()

    // First use is enabled by design: a recording ride enters PiP without an interrupting dialog.
    private val _pipDashboardEnabled = MutableStateFlow(prefs.getBoolean("pip_dashboard_enabled", true))
    val pipDashboardEnabled: StateFlow<Boolean> = _pipDashboardEnabled.asStateFlow()

    private val _lastStartedPersona = MutableStateFlow(
        prefs.getString("last_started_persona", null)
            ?.let { runCatching { RidePersona.valueOf(it) }.getOrNull() }
            ?: prefs.getString("onboarding_persona", null)
                ?.let { runCatching { RidePersona.valueOf(it) }.getOrNull() }
            ?: RidePersona.AUTO
    )
    val lastStartedPersona: StateFlow<RidePersona> = _lastStartedPersona.asStateFlow()

    init { updateProcessLocale(_appLanguage.value) }

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _dynamicColor.value = enabled
    }

    /**
     * TASK-284 — whether TrackMe has ever asked for POST_NOTIFICATIONS on this install.
     *
     * Not a StateFlow: nothing renders from it, it is read once at the moment of asking, and a
     * flow would invite someone to observe it and re-ask on change. Defaults false so an existing
     * install that was already nagged gets exactly one more ask and then stops — which is the
     * kindest available migration, since we cannot tell from here whether the system has already
     * taken their answer permanently.
     */
    fun hasAskedNotificationPermission(): Boolean =
        prefs.getBoolean("notification_permission_asked", false)

    fun markNotificationPermissionAsked() {
        prefs.edit().putBoolean("notification_permission_asked", true).apply()
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("telemetry_enabled", enabled).apply()
        _telemetryEnabled.value = enabled
    }

    /**
     * One-time handoff from TrackMe's pre-TASK-306 preference into Android's locale store.
     * Called before AppCompatActivity.onCreate so the selected resources are attached immediately.
     */
    @MainThread
    fun prepareApplicationLocale() {
        if (prefs.getBoolean(APP_LANGUAGE_MIGRATED_KEY, false)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Framework-backed locales are already attached before Activity.onCreate. Import a
            // restored/system choice now; an empty first-run value is published after super.
            val systemChoice = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.get(0)
                ?.toLanguageTag()
            AppLanguageCatalog.normalize(systemChoice)?.let { restoredLanguage ->
                storeLanguage(restoredLanguage)
                markLanguageMigrated()
            }
            return
        }

        // Android 12 and lower need the custom preference before onCreate. AndroidX then owns its
        // backward-compatible store through AppLocalesMetadataHolderService.
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(_appLanguage.value)
        )
        markLanguageMigrated()
    }

    /** Imports an app-locale choice made outside TrackMe, including Android system Settings. */
    @MainThread
    fun reconcileApplicationLocale() {
        val requestedTag = AppCompatDelegate.getApplicationLocales()
            .get(0)
            ?.toLanguageTag()
        val requestedLanguage = AppLanguageCatalog.normalize(requestedTag)
        if (requestedLanguage != null) {
            storeLanguage(requestedLanguage)
            markLanguageMigrated()
            return
        }

        if (!prefs.getBoolean(APP_LANGUAGE_MIGRATED_KEY, false)) {
            // Android 13+ requires an attached AppCompat delegate for the one-time framework
            // handoff. This may recreate the Activity, which is AppCompat's documented behavior.
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(_appLanguage.value)
            )
            markLanguageMigrated()
            return
        }

        // Empty after migration means the user selected "System default" in Android Settings.
        storeLanguage(effectiveSystemLanguage())
    }

    @MainThread
    fun setAppLanguage(lang: String) {
        val normalized = AppLanguageCatalog.normalize(lang) ?: DEFAULT_LANGUAGE
        storeLanguage(normalized)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
    }

    fun setUnitSystem(system: String) {
        val normalized = if (system == "imperial") "imperial" else "metric"
        prefs.edit().putString("unit_system", normalized).apply()
        _unitSystem.value = normalized
    }

    fun setPiPDashboardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pip_dashboard_enabled", enabled).apply()
        _pipDashboardEnabled.value = enabled
    }

    /** Called only after the service has inserted the recording row successfully. */
    fun setLastStartedPersona(persona: RidePersona) {
        prefs.edit().putString("last_started_persona", persona.name).apply()
        _lastStartedPersona.value = persona
    }

    /** The onboarding choice seeds Home until a real recording has committed. */
    fun setOnboardingPersona(persona: RidePersona) {
        prefs.edit().putString("onboarding_persona", persona.name).apply()
        if (!prefs.contains("last_started_persona")) _lastStartedPersona.value = persona
    }

    private fun defaultUnitFromLocale(): String = if (Locale.getDefault().country.uppercase() in setOf("US", "GB", "MM", "LR")) "imperial" else "metric"

    private fun storeLanguage(language: String) {
        if (_appLanguage.value != language || prefs.getString(APP_LANGUAGE_KEY, null) != language) {
            prefs.edit().putString(APP_LANGUAGE_KEY, language).apply()
            _appLanguage.value = language
        }
        updateProcessLocale(language)
    }

    private fun effectiveSystemLanguage(): String {
        val locale = context.resources.configuration.locales.get(0) ?: Locale.ENGLISH
        return AppLanguageCatalog.normalize(locale.toLanguageTag()) ?: DEFAULT_LANGUAGE
    }

    private fun updateProcessLocale(language: String) {
        Locale.setDefault(Locale.forLanguageTag(language))
    }

    private fun markLanguageMigrated() {
        if (!prefs.getBoolean(APP_LANGUAGE_MIGRATED_KEY, false)) {
            // commit() is intentional: AppCompat and Android's framework locale service consume
            // this state during the same launch, and process death must not repeat the handoff.
            prefs.edit().putBoolean(APP_LANGUAGE_MIGRATED_KEY, true).commit()
        }
    }

    private companion object {
        const val APP_LANGUAGE_KEY = "app_language"
        const val APP_LANGUAGE_MIGRATED_KEY = "app_language_system_migrated"
        const val DEFAULT_LANGUAGE = "en"
    }
}
