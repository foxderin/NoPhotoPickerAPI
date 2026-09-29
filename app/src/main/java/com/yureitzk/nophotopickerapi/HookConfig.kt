package com.yureitzk.nophotopickerapi

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Hook-side reader for [AppConfig], fetched from [ConfigProvider] with a short
 * TTL cache. On any failure (provider down, module app force-stopped) the
 * snapshot is empty, which yields the default behavior: intercept everything.
 */
object HookConfig {

    private const val TTL_MS = 30_000L

    data class Snapshot(val blocked: Set<String>, val compat: Set<String>)

    @Volatile
    private var cached: Snapshot? = null
    @Volatile
    private var cachedAt = 0L

    @Synchronized
    fun get(context: Context?): Snapshot {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < TTL_MS) return it }
        val snapshot = query(contextOrSystem(context))
        cached = snapshot
        cachedAt = now
        return snapshot
    }

    @Volatile
    private var systemContext: Context? = null

    /**
     * In system_server the hooked startActivity args carry no Context; resolve
     * the system context reflectively so the config provider stays reachable.
     */
    private fun contextOrSystem(context: Context?): Context? {
        context?.let { return it }
        systemContext?.let { return it }
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            (activityThread.getMethod("getSystemContext").invoke(thread) as? Context)
                ?.also { systemContext = it }
        } catch (t: Throwable) {
            Log.w(HookCore.TAG, "System context unavailable: $t")
            null
        }
    }

    private fun query(context: Context?): Snapshot {
        if (context == null) return Snapshot(emptySet(), emptySet())
        return try {
            val result = context.contentResolver.call(
                ConfigProvider.AUTHORITY, ConfigProvider.METHOD_GET_CONFIG, null, null
            )
            Snapshot(
                result?.getStringArrayList(ConfigProvider.EXTRA_BLOCKED)?.toSet() ?: emptySet(),
                result?.getStringArrayList(ConfigProvider.EXTRA_COMPAT)?.toSet() ?: emptySet(),
            )
        } catch (t: Throwable) {
            Log.w(HookCore.TAG, "Config query failed: $t")
            Snapshot(emptySet(), emptySet())
        }
    }
}
