package com.yureitzk.nophotopickerapi

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Compatibility mode: copies picked content into our cache and returns
 * FileProvider URIs, for callers that cannot consume SAF content URIs
 * (e.g. apps expecting file paths or MediaStore entries).
 */
object CompatCache {

    private const val FILEPROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** Drops cached copies older than 24h. Call on activity start. */
    fun cleanup(context: Context) {
        val dir = File(context.cacheDir, "compat")
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff && !file.delete()) {
                Log.w(HookCore.TAG, "Failed to delete stale cache ${file.name}")
            }
        }
    }

    /**
     * Returns a result intent equivalent to [data] but with every content URI
     * replaced by a cached FileProvider copy, or null if the copy failed or a
     * source exceeds [MAX_COPY_BYTES] (then the original URI is returned).
     */
    fun materialize(context: Context, data: Intent): Intent? {
        val uris = collectUris(data)
        if (uris.isEmpty()) return null
        val mapped = uris.mapNotNull { copyToCache(context, it) }
        if (mapped.size != uris.size) return null

        val result = Intent()
        if (data.data != null && mapped.size == 1) {
            result.data = mapped[0]
        } else {
            val clip = ClipData.newUri(context.contentResolver, "picked", mapped[0])
            for (i in 1 until mapped.size) clip.addItem(ClipData.Item(mapped[i]))
            result.clipData = clip
        }
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return result
    }

    /** Refuse to duplicate huge media (videos) into the cache. */
    private const val MAX_COPY_BYTES = 100L * 1024 * 1024

    private fun collectUris(data: Intent): List<Uri> {
        data.data?.let { return listOf(it) }
        val clip = data.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun copyToCache(context: Context, uri: Uri): Uri? {
        return try {
            val resolver = context.contentResolver
            val size = querySize(resolver, uri)
            val mime = resolver.getType(uri)
            val isImage = mime?.startsWith("image/") == true
            if (size != null && size > MAX_COPY_BYTES) {
                Log.w(HookCore.TAG, "Compat copy skipped (${size}B > cap) for $uri")
                return null
            }
            if (size == null && !isImage) {
                // Unknown size and not a still image: don't risk a huge copy.
                Log.w(HookCore.TAG, "Compat copy skipped (unknown size) for $uri")
                return null
            }
            val dir = File(context.cacheDir, "compat").apply { mkdirs() }
            val name = queryDisplayName(resolver, uri)
                ?: "picked_${System.currentTimeMillis()}"
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
            FileProvider.getUriForFile(context, FILEPROVIDER_AUTHORITY, target)
        } catch (t: Throwable) {
            Log.w(HookCore.TAG, "Compat copy failed for $uri: $t")
            null
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.substringAfterLast('/') else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}
