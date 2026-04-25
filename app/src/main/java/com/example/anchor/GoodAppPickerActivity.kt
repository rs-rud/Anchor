package com.example.anchor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class GoodAppPickerActivity : AppCompatActivity() {

    private lateinit var adapter: GoodAppPickerAdapter
    private var allApps = listOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_good_app_picker)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.goodAppPickerRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val rv = findViewById<RecyclerView>(R.id.rvGoodApps)
        val progressBar = findViewById<ProgressBar>(R.id.goodAppProgressBar)
        val emptyState = findViewById<View>(R.id.goodAppEmptyState)
        val etSearch = findViewById<TextInputEditText>(R.id.etGoodAppSearch)

        adapter = GoodAppPickerAdapter { app ->
            getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AnchorPrefs.KEY_GOOD_APP_PACKAGE, app.packageName)
                .apply()

            TelemetryTracker.logEvent(
                eventType = "good_app_selected",
                metadata = mapOf("package" to app.packageName)
            )
            finish()
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        progressBar.visibility = View.VISIBLE

        Thread {
            val apps = loadInstalledApps()
            allApps = apps
            rv.post {
                progressBar.visibility = View.GONE
                emptyState.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(apps)
            }
        }.start()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString().orEmpty(), emptyState)
            }
        })
    }

    private fun filterApps(query: String, emptyState: View) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            val lower = query.lowercase(Locale.getDefault())
            allApps.filter { it.name.lowercase(Locale.getDefault()).contains(lower) }
        }

        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(filtered)
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val blockedApps = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            .getStringSet(AnchorPrefs.KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

        return pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .filterNot { blockedApps.contains(it.activityInfo.packageName) }
            .map { ri ->
                AppInfo(
                    name = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = ri.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }
}
