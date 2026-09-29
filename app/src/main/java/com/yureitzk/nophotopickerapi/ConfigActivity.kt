package com.yureitzk.nophotopickerapi

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

/**
 * Launcher settings screen: per-caller policy for rewritten picker requests.
 * 不拦截 = leave that app's photo picker untouched; 兼容模式 = serve cached
 * file copies instead of SAF URIs.
 */
class ConfigActivity : AppCompatActivity() {

    private data class AppEntry(val packageName: String, val label: String, val iconRef: android.graphics.drawable.Drawable)

    private val entries = ArrayList<AppEntry>()
    private lateinit var blocked: MutableSet<String>
    private lateinit var compat: MutableSet<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        blocked = AppConfig.getBlocked(this).toMutableSet()
        compat = AppConfig.getCompat(this).toMutableSet()
        loadApps()

        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: layoutInflater.inflate(R.layout.row_app_config, parent, false)
                val entry = entries[position]
                row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(entry.iconRef)
                row.findViewById<TextView>(R.id.app_label).text = entry.label
                row.findViewById<TextView>(R.id.app_package).text = entry.packageName

                val blockBox = row.findViewById<CheckBox>(R.id.check_blocked)
                val compatBox = row.findViewById<CheckBox>(R.id.check_compat)
                // Detach listeners before recycling state.
                blockBox.setOnCheckedChangeListener(null)
                compatBox.setOnCheckedChangeListener(null)
                blockBox.isChecked = entry.packageName in blocked
                compatBox.isChecked = entry.packageName in compat
                blockBox.setOnCheckedChangeListener { _, checked ->
                    if (checked) blocked += entry.packageName else blocked -= entry.packageName
                    AppConfig.setBlocked(this@ConfigActivity, blocked)
                }
                compatBox.setOnCheckedChangeListener { _, checked ->
                    if (checked) compat += entry.packageName else compat -= entry.packageName
                    AppConfig.setCompat(this@ConfigActivity, compat)
                }
                return row
            }
        }
        findViewById<ListView>(R.id.app_list).adapter = adapter
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        entries.clear()
        entries.addAll(apps)
    }
}
