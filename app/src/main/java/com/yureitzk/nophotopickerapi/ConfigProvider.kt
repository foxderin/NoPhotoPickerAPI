package com.yureitzk.nophotopickerapi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exposes [AppConfig] to hook-side processes (e.g. system_server), which cannot
 * read this app's SharedPreferences directly. Read-only; config content is just
 * package-name lists, so no permission guard is needed.
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.config"
        const val METHOD_GET_CONFIG = "get_config"
        const val EXTRA_BLOCKED = "blocked"
        const val EXTRA_COMPAT = "compat"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_CONFIG) return null
        val ctx = context ?: return null
        return Bundle().apply {
            putStringArrayList(EXTRA_BLOCKED, ArrayList(AppConfig.getBlocked(ctx)))
            putStringArrayList(EXTRA_COMPAT, ArrayList(AppConfig.getCompat(ctx)))
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
