package `in`.shvms.trackme.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

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
