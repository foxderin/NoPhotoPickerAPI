package com.yureitzk.nophotopickerapi

import android.content.Context

/**
 * Per-caller configuration, stored in the module app's SharedPreferences.
 * Hook-side processes read it through [ConfigProvider] (see [HookConfig]).
 */
object AppConfig {

    const val PREFS_NAME = "config"
    const val KEY_BLOCKED = "blocked_packages"
    const val KEY_COMPAT = "compat_packages"

    fun getBlocked(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    fun getCompat(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_COMPAT, emptySet()) ?: emptySet()

    fun setBlocked(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_BLOCKED, packages).apply()
    }

    fun setCompat(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_COMPAT, packages).apply()
    }
}
