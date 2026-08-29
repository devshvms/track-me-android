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

    // Gamification Presentation State
    private val _lastSeenLevel = MutableStateFlow(prefs.getInt("gamification_last_seen_level", 1))
    val lastSeenLevel: StateFlow<Int> = _lastSeenLevel.asStateFlow()

    private val _lastSeenAchievements = MutableStateFlow(prefs.getStringSet("gamification_last_seen_achievements", emptySet()) ?: emptySet())
    val lastSeenAchievements: StateFlow<Set<String>> = _lastSeenAchievements.asStateFlow()

    // Maintenance Mode (e.g., "2026-W42")
    private val _maintenanceEndWeek = MutableStateFlow(prefs.getString("gamification_maintenance_end_week", null))
    val maintenanceEndWeek: StateFlow<String?> = _maintenanceEndWeek.asStateFlow()

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

    fun setGamificationLastSeenLevel(level: Int) {
        prefs.edit().putInt("gamification_last_seen_level", level).apply()
        _lastSeenLevel.value = level
    }

    fun addGamificationSeenAchievements(achievements: Set<String>) {
        val updated = _lastSeenAchievements.value + achievements
        prefs.edit().putStringSet("gamification_last_seen_achievements", updated).apply()
        _lastSeenAchievements.value = updated
    }

    fun setGamificationMaintenanceEndWeek(isoWeek: String?) {
        if (isoWeek == null) {
            prefs.edit().remove("gamification_maintenance_end_week").apply()
        } else {
            prefs.edit().putString("gamification_maintenance_end_week", isoWeek).apply()
        }
        _maintenanceEndWeek.value = isoWeek
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
