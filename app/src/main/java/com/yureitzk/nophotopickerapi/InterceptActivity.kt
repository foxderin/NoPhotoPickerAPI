package com.yureitzk.nophotopickerapi

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shown instead of the Photo Picker. Lists every activity that can handle the
 * rewritten SAF intent (plus "system default") and forwards the user's choice,
 * returning the picker result to the original caller.
 */
class InterceptActivity : AppCompatActivity() {

    private companion object {
        const val REQ_PICK = 0x9E01
    }

    private data class HandlerEntry(val info: ActivityInfo?, val label: String, val icon: Drawable)

    /** Original caller of the pick request, forwarded by HookCore. */
    private var callingPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        CompatCache.cleanup(this)

        callingPackage = intent.getStringExtra(HookCore.EXTRA_CALLING_PACKAGE)

        @Suppress("DEPRECATION")
        val docIntent = intent.getParcelableExtra(HookCore.EXTRA_DOC_INTENT) as? Intent
        if (docIntent == null) {
            Log.w(HookCore.TAG, "Missing doc intent, cancelling")
            finishCancelled()
            return
        }

        val handlers = queryHandlers(docIntent)
        val entries = ArrayList<HandlerEntry>(handlers.size + 1)
        entries += HandlerEntry(
            null, getString(R.string.system_default), packageManager.defaultActivityIcon
        )
        handlers.forEach { (info, label) ->
            entries += HandlerEntry(info, label, info.loadIcon(packageManager))
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: layoutInflater.inflate(R.layout.dialog_handler_item, parent, false)
                row.findViewById<ImageView>(R.id.handler_icon).setImageDrawable(entries[position].icon)
                row.findViewById<TextView>(R.id.handler_label).text = entries[position].label
                return row
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_picker)
            .setAdapter(adapter) { _, which ->
                val target = Intent(docIntent)
                val info = entries[which].info
                if (info != null) {
                    target.component = ComponentName(info.packageName, info.name)
                    Log.d(HookCore.TAG, "User chose ${info.packageName}/${info.name}")
                } else {
                    Log.d(HookCore.TAG, "User chose system default")
                }
                startActivityForResult(target, REQ_PICK)
            }
            .setOnCancelListener { finishCancelled() }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK) return
        super.onActivityResult(requestCode, resultCode, data)
        // Re-flag so the URI grant (incl. persistable) propagates to the caller.
        data?.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (resultCode == RESULT_OK && data != null &&
            callingPackage != null && callingPackage in AppConfig.getCompat(this)
        ) {
            // Compat mode: replace SAF URIs with cached FileProvider copies.
            // Copying streams can be slow, so do it off the UI thread and
            // deliver the result when done.
            Thread {
                val rewritten = CompatCache.materialize(this, data)
                runOnUiThread {
                    if (rewritten != null) {
                        Log.d(HookCore.TAG, "Compat mode: served cached copy to $callingPackage")
                        setResult(resultCode, rewritten)
                    } else {
                        Log.w(HookCore.TAG, "Compat copy failed, returning original result")
                        setResult(resultCode, data)
                    }
                    finish()
                }
            }.start()
            return
        }
        setResult(resultCode, data)
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** All distinct apps handling the SAF intent, in system precedence order. */
    private fun queryHandlers(docIntent: Intent): List<Pair<ActivityInfo, String>> {
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
