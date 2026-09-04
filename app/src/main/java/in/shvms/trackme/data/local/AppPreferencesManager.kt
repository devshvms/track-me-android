package `in`.shvms.trackme.data.local

import android.content.Context
import android.content.SharedPreferences
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
    private val _appLanguage = MutableStateFlow(prefs.getString("app_language", "en") ?: "en")
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

    init {
        updateSystemLocale(_appLanguage.value)
    }

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

    fun setAppLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
        _appLanguage.value = lang
        updateSystemLocale(lang)
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

    private fun updateSystemLocale(lang: String) {
        try {
            val locale = java.util.Locale.forLanguageTag(lang.ifBlank { "en" })
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
