package com.airpods.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemePreference {
    SYSTEM, LIGHT, DARK;

    fun next(): ThemePreference = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}

object ThemePrefs {
    private const val PREFS_NAME = "airpods_prefs"
    private const val KEY_THEME = "theme_pref"

    private val _flow = MutableStateFlow(ThemePreference.SYSTEM)
    val flow: StateFlow<ThemePreference> = _flow.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = sp.getString(KEY_THEME, null)
        _flow.value = runCatching { ThemePreference.valueOf(saved!!) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    fun set(context: Context, pref: ThemePreference) {
        _flow.value = pref
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, pref.name)
            .apply()
    }
}
