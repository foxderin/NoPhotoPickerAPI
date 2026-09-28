package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Shown instead of the Photo Picker. Lists every activity that can handle the
 * rewritten SAF intent (plus "system default") and forwards the user's choice,
 * returning the picker result to the original caller.
 */
class InterceptActivity : Activity() {

    private companion object {
        const val REQ_PICK = 0x9E01
    }

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val docIntent = intent.getParcelableExtra(HookCore.EXTRA_DOC_INTENT) as? Intent
        if (docIntent == null) {
            Log.w(HookCore.TAG, "Missing doc intent, cancelling")
            finishCancelled()
            return
        }

        val handlers = queryHandlers(docIntent)
        val labels = ArrayList<String>(handlers.size + 1)
        labels += getString(R.string.system_default)
        handlers.forEach { labels += it.second }

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_picker)
            .setItems(labels.toTypedArray()) { _, which ->
                val target = Intent(docIntent)
                if (which > 0) {
                    val info = handlers[which - 1].first
                    target.component = ComponentName(info.packageName, info.name)
                    Log.d(HookCore.TAG, "User chose ${info.packageName}/${info.name}")
                } else {
                    Log.d(HookCore.TAG, "User chose system default")
                }
                launched = true
                startActivityForResult(target, REQ_PICK)
            }
            .setOnCancelListener { finishCancelled() }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK) return
        super.onActivityResult(requestCode, resultCode, data)
        // Re-flag so the URI grant propagates to the original caller.
        data?.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(resultCode, data)
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** All distinct apps handling the SAF intent, in system precedence order. */
    private fun queryHandlers(docIntent: Intent): List<Pair<android.content.pm.ActivityInfo, String>> {
        return try {
            packageManager.queryIntentActivities(docIntent, 0)
                .asSequence()
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { it.activityInfo to it.loadLabel(packageManager).toString() }
                .toList()
        } catch (t: Throwable) {
            Log.w(HookCore.TAG, "Handler query failed: $t")
            emptyList()
        }
    }
}
