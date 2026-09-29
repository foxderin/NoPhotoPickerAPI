package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log

/**
 * Shared hook logic, independent of the Xposed API flavor (libxposed 102 or
 * legacy api:82). Both entry points ([MainHook] and [LegacyMainHook]) delegate here.
 */
object HookCore {

    const val TAG = "NoPhotoPicker"
    const val FLAG = "x_handled_by_nophoto"

    // AndroidX ActivityResultContracts.PickVisualMedia system-fallback action.
    // Constant exists in androidx.activity but is not part of the Android SDK.
    private const val ACTION_SYSTEM_FALLBACK_PICK_IMAGES =
        "androidx.activity.result.contract.action.PICK_IMAGES"

    // Legacy/unofficial AndroidX action string kept for broad compatibility.
    private const val ACTION_ANDROIDX_PICK_VISUAL_MEDIA =
        "androidx.activity.result.contract.action.PickVisualMedia"

    // Google Play services (GMS) photo picker backport action.
    private const val ACTION_GMS_PICK_IMAGES =
        "com.google.android.gms.provider.action.PICK_IMAGES"

    // Max-items extras used by the AndroidX system-fallback picker and the GMS picker.
    private const val EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_MAX =
        "androidx.activity.result.contract.extra.PICK_IMAGES_MAX"
    private const val EXTRA_GMS_PICK_IMAGES_MAX =
        "com.google.android.gms.provider.extra.PICK_IMAGES_MAX"

    val SERVICE_CLASSES = listOf(
        "com.android.server.wm.ActivityTaskManagerService",
        "com.android.server.am.ActivityManagerService",
        // Android 10+ (incl. Android 16); pre-10 it lived in com.android.server.am
        "com.android.server.wm.ActivityStarter",
        "com.android.server.am.ActivityStarter"
    )

    private const val MODULE_PACKAGE = "com.yureitzk.nophotopickerapi"

    /** Module-owned activity that lets the user pick which SAF handler to use. */
    const val INTERCEPT_ACTIVITY = "com.yureitzk.nophotopickerapi.InterceptActivity"
    const val EXTRA_DOC_INTENT = "npp_doc_intent"
    const val EXTRA_CALLING_PACKAGE = "npp_calling_package"

    /** Packages the provider owner should grant the picked URI to directly. */
    const val EXTRA_GRANT_TARGETS = "npp_grant_targets"

    /**
     * Best-effort caller package for a hooked startActivity-style call.
     * The system_server hook runs on the caller's binder thread, so
     * Binder.getCallingUid() is authoritative there; app-side hooks fall back
     * to the hooked context's own package, and the args heuristic (…, String
     * callingPackage, String? callingFeatureId, Intent, …) covers the rest.
     */
    private fun getCallingPackage(args: Array<Any?>, intentIndex: Int, context: Context?): String? {
        try {
            val uid = android.os.Binder.getCallingUid()
            if (uid > 0 && uid != android.os.Process.myUid() && uid != 1000) {
                val pm = context?.packageManager ?: systemPackageManager()
                val packages = pm?.getPackagesForUid(uid)
                packages?.firstOrNull()?.let { return it }
            }
        } catch (t: Throwable) {
            // Fall through to the heuristics below.
        }
        if (intentIndex >= 2 && args[intentIndex - 2] is String) {
            return args[intentIndex - 2] as String
        }
        return context?.packageName?.takeIf { it != "android" }
    }

    private fun systemPackageManager(): android.content.pm.PackageManager? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("currentActivityThread").invoke(null)
            val ctx = activityThread.getMethod("getSystemContext").invoke(thread)
                    as? android.content.Context
            ctx?.packageManager
        } catch (t: Throwable) {
            null
        }
    }
    /**
     * Scans [args] for a Photo Picker intent and rewrites it in place to a SAF
     * ACTION_OPEN_DOCUMENT intent. Returns true if a rewrite happened.
     * Used by both the libxposed Chain interceptor and the legacy XC_MethodHook.
     * [context] is any Context from the hooked call (Activity or Instrumentation
     * `who`); null in system_server, where it is resolved reflectively.
     */
    fun rewritePickerArgs(args: Array<Any?>, source: String, context: Context?): Boolean {
        for (i in args.indices) {
            val intent = args[i] as? Intent ?: continue
            if (isPhotoPickerIntent(intent)) {
                val caller = getCallingPackage(args, i, context)
                if (caller != null && caller in HookConfig.get(context).blocked) {
                    Log.d(TAG, "[$source] Caller $caller is excluded, leaving picker intact")
                    return false
                }
                logIntentDetails(intent, source)
                val newIntent = buildDocumentPickerIntent(intent, context, caller)
                args[i] = newIntent

                if (i + 1 < args.size && (args[i + 1] == null || args[i + 1] is String)) {
                    val newType = newIntent.type
                    args[i + 1] = newType
                    Log.d(TAG, "Updated resolvedType to $newType")
                }
                return true
            } else if (BuildConfig.DEBUG &&
                intent.action?.contains("PICK", ignoreCase = true) == true
            ) {
                // Debug builds only: surface pick-like actions we don't handle yet.
                Log.d(TAG, "[$source] Unrecognized pick-like action: ${intent.action}")
            }
        }
        return false
    }

    /**
     * Normalizes empty OK results from onActivityResult(requestCode, resultCode, data):
     * an OK result without any content is downgraded to RESULT_CANCELED with null data.
     * [args] is the raw (requestCode, resultCode, data) triple, mutated in place.
     */
    fun sanitizeActivityResultArgs(args: Array<Any?>) {
        val requestCode = args[0] as Int
        val resultCode = args[1] as Int
        val data = args[2] as? Intent

        // Skip if canceled or no data
        if (resultCode != Activity.RESULT_OK || data == null) return

        val hasContent = when {
            data.data != null -> true
            data.clipData?.let { clipData ->
                (0 until clipData.itemCount).any { clipData.getItemAt(it).uri != null }
            } ?: false -> true
            data.hasExtra(Intent.EXTRA_STREAM) -> true
            data.hasExtra(Intent.EXTRA_CONTENT_ANNOTATIONS) -> true
            else -> false
        }

        if (!hasContent) {
            Log.d(TAG, "Empty result detected for request $requestCode")
            args[1] = Activity.RESULT_CANCELED
            args[2] = null
        }
    }

    private fun getMaxItems(intent: Intent): Int {
        // All three constants are compile-time inlined strings, safe on any API level.
        val frameworkMax = intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)
        if (frameworkMax > 0) return frameworkMax
        val fallbackMax = intent.getIntExtra(EXTRA_SYSTEM_FALLBACK_PICK_IMAGES_MAX, -1)
        if (fallbackMax > 0) return fallbackMax
        return intent.getIntExtra(EXTRA_GMS_PICK_IMAGES_MAX, -1)
    }

    private fun isPhotoPickerIntent(intent: Intent): Boolean {
        if (intent.hasExtra(FLAG)) return false
        // No SDK_INT gating: AndroidX/GMS fallback actions must match on Android 16 too.
        return when (intent.action) {
            MediaStore.ACTION_PICK_IMAGES,
            ACTION_SYSTEM_FALLBACK_PICK_IMAGES,
            ACTION_ANDROIDX_PICK_VISUAL_MEDIA,
            ACTION_GMS_PICK_IMAGES -> true
            else -> false
        }
    }

    private fun logIntentDetails(intent: Intent, source: String) {
        Log.d(TAG, "[$source] Photo picker detected")
        Log.d(TAG, "  Original action: ${intent.action}")
        Log.d(TAG, "  Mime type: ${intent.type}")
        Log.d(TAG, "  Allow multiple: " +
                (intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) || getMaxItems(intent) > 1))
    }

    private fun buildDocumentPickerIntent(original: Intent, context: Context?, caller: String?): Intent {
        // ACTION_OPEN_DOCUMENT (SAF / DocumentsUI) instead of ACTION_GET_CONTENT:
        // Android 16 redirects image/video GET_CONTENT back to the system Photo Picker.
        val openDocument = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)

            // A fresh implicit Intent: the Photo Picker's explicit component/package
            // is intentionally not inherited.

            // Handle MIME types
            val sourceMimeTypes = original.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                ?: original.getStringArrayExtra("android.provider.extra.MIME_TYPES")
            val mimeTypes = sourceMimeTypes
                ?: original.type?.let { arrayOf(it) }
                // No type at all means "any visual media" (image + video).
                ?: arrayOf("image/*", "video/*")

            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1 || mimeTypes[0] != type) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }

            // Handle multi-select
            val allowMultiple = original.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            val maxItems = getMaxItems(original)

            if (allowMultiple || maxItems > 1) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                if (maxItems > 1) {
                    // Pass through for SAF providers that honor it; DocumentsUI ignores it.
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxItems)
                }
                Log.d(TAG, "Multi-select enabled (maxItems=$maxItems)")
            }

            putExtra(FLAG, true)
            // PERSISTABLE lets the final caller takePersistableUriPermission();
            // the grant chain only propagates it if every hop requests it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

            // The bridged picker (e.g. ColorOS file manager) is reached with
            // this inner intent; carry the packages that must end up holding
            // the result URI so the provider owner can grant them directly —
            // framework propagation is unreliable across several apps.
            val targets = ArrayList<String>()
            caller?.let { targets.add(it) }
            targets.add(MODULE_PACKAGE)
            putStringArrayListExtra(EXTRA_GRANT_TARGETS, targets)


        }
        // Route through our own interceptor activity so the user can pick which
        // SAF handler to use. OEMs (e.g. ColorOS) register their file manager as
        // the default OPEN_DOCUMENT handler and their resolver skips the chooser
        // dialog entirely, so neither implicit resolution nor createChooser gives
        // the user a choice. The intercept activity is part of this installed
        // module, hence always resolvable while the hook runs.
        Log.d(TAG, "Converted action: ${openDocument.action}, mime: ${openDocument.type}, " +
                "allowMultiple: ${openDocument.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)}")
        return Intent(original.action ?: MediaStore.ACTION_PICK_IMAGES).apply {
            setClassName(MODULE_PACKAGE, INTERCEPT_ACTIVITY)
            type = openDocument.type
            putExtra(EXTRA_DOC_INTENT, openDocument)
            if (caller != null) putExtra(EXTRA_CALLING_PACKAGE, caller)
            putExtra(FLAG, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            Log.d(TAG, "Targeting interceptor activity for user-side handler choice")
        }
    }
}
