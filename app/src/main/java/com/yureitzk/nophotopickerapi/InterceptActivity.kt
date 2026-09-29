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
            ?: callingActivity?.packageName

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
                // The system_server hook code is boot-time stale and cannot add
                // these; inject them here (app-side code is live) so the
                // bridged picker can grant the result URI to every hop.
                target.putStringArrayListExtra(
                    HookCore.EXTRA_GRANT_TARGETS,
                    ArrayList(listOfNotNull(callingPackage, packageName))
                )
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
        // Belt and braces: the framework's flag propagation can drop the grant
        // when the result crosses several apps (bridged pickers), so grant the
        // URIs we hold to the original caller explicitly.
        if (resultCode == RESULT_OK && data != null) {
            grantResultUrisToCaller(data)
        }
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
        try {
            setResult(resultCode, data)
            finish()
        } catch (t: Throwable) {
            // The system can refuse the URI grant when the chain crosses apps
            // it cannot verify. The provider owner granted the caller directly
            // (see HookCore grant targets), so retry with the grant flags
            // stripped — the caller still has access.
            Log.w(HookCore.TAG, "granted delivery failed, retrying plain: $t")
            try {
                val plain = Intent().apply {
                    this.data = data?.data
                    this.clipData = data?.clipData
                }
                setResult(resultCode, plain)
                finish()
            } catch (t2: Throwable) {
                Log.e(HookCore.TAG, "finish failed: $t2")
                try {
                    setResult(RESULT_CANCELED)
                    finish()
                } catch (t3: Throwable) {
                    Log.e(HookCore.TAG, "cancel finish failed: $t3")
                }
            }
        }
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** Grants every result URI to the original caller, if we hold it. */
    private fun grantResultUrisToCaller(data: Intent) {
        val target = callingPackage ?: return
        val uris = ArrayList<android.net.Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        for (uri in uris) {
            try {
                grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                Log.w(HookCore.TAG, "grant to $target failed for $uri: $t")
            }
        }
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
