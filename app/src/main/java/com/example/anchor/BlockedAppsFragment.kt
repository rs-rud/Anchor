package com.example.anchor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class BlockedAppsFragment : Fragment() {

    private lateinit var adapter: AppListAdapter
    private val blockedApps = mutableSetOf<String>()
    private var allApps = listOf<AppInfo>()
    private var tvBlockedCount: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_blocked_apps, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvApps)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val emptyState = view.findViewById<View>(R.id.emptyState)
        val etSearch = view.findViewById<TextInputEditText>(R.id.etSearch)
        tvBlockedCount = view.findViewById(R.id.tvBlockedCount)

        val prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        blockedApps.addAll(prefs.getStringSet(AnchorPrefs.KEY_BLOCKED_APPS, emptySet()) ?: emptySet())
        updateBlockedCount()

        adapter = AppListAdapter(blockedApps) { packageName, isBlocked ->
            if (isBlocked) blockedApps.add(packageName) else blockedApps.remove(packageName)
            prefs.edit().putStringSet(AnchorPrefs.KEY_BLOCKED_APPS, blockedApps.toSet()).apply()
            updateBlockedCount()
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        progressBar.visibility = View.VISIBLE

        Thread {
            val apps = loadInstalledApps()
            allApps = apps
            rv.post {
                progressBar.visibility = View.GONE
                if (apps.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                } else {
                    emptyState.visibility = View.GONE
                    adapter.submitList(apps)
                }
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

    private fun updateBlockedCount() {
        tvBlockedCount?.text = blockedApps.size.toString()
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
        val pm = requireContext().packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val myPackage = requireContext().packageName

        return resolveInfos
            .filter { it.activityInfo.packageName != myPackage }
            .map { ri ->
                AppInfo(
                    name = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = ri.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<AppInfo> { blockedApps.contains(it.packageName) }.thenBy { it.name })
    }
}
