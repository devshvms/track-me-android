package `in`.shvms.trackme.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferencesManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)

    // Theme mode: 0 = System Default, 1 = Light, 2 = Dark
    private val _themeMode = MutableStateFlow(prefs.getInt("theme_mode", 0))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    // App language code: "en", "es", "fr", "de", "hi", "ja", "zh"
    private val _appLanguage = MutableStateFlow(prefs.getString("app_language", "en") ?: "en")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    init {
        updateSystemLocale(_appLanguage.value)
    }

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    fun setAppLanguage(lang: String) {
        prefs.edit().putString("app_language", lang).apply()
        _appLanguage.value = lang
        updateSystemLocale(lang)
    }

    private fun updateSystemLocale(lang: String) {
        try {
            val locale = java.util.Locale(lang)
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
