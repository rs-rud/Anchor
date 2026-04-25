package com.example.anchor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class RitualFragment : Fragment() {

    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var goodAppCard: View
    private lateinit var selectedGoodAppRow: View
    private lateinit var emptyGoodAppState: TextView
    private lateinit var selectedGoodAppIcon: ImageView
    private lateinit var selectedGoodAppName: TextView
    private var suppressToggleEvents = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_ritual, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toggleGroup = view.findViewById(R.id.ritualToggleGroup)
        goodAppCard = view.findViewById(R.id.goodAppCard)
        selectedGoodAppRow = view.findViewById(R.id.selectedGoodAppRow)
        emptyGoodAppState = view.findViewById(R.id.tvGoodAppEmpty)
        selectedGoodAppIcon = view.findViewById(R.id.ivSelectedGoodAppIcon)
        selectedGoodAppName = view.findViewById(R.id.tvSelectedGoodAppName)

        val changeButton = view.findViewById<MaterialButton>(R.id.btnChangeGoodApp)
        changeButton.setOnClickListener {
            startActivity(Intent(requireContext(), GoodAppPickerActivity::class.java))
        }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val ritualType = when (checkedId) {
                R.id.btnRitualGoodApp -> AnchorPrefs.RITUAL_GOOD_APP
                else -> AnchorPrefs.RITUAL_BREATHING
            }
            if (!suppressToggleEvents) {
                requireContext()
                    .getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(AnchorPrefs.KEY_RITUAL_TYPE, ritualType)
                    .apply()

                TelemetryTracker.logEvent("ritual_changed", mapOf("ritual_type" to ritualType))
            }
            updateGoodAppCard(ritualType)
        }
    }

    override fun onResume() {
        super.onResume()

        val prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val ritualType = prefs.getString(AnchorPrefs.KEY_RITUAL_TYPE, AnchorPrefs.RITUAL_BREATHING)
            ?: AnchorPrefs.RITUAL_BREATHING
        val checkedId = if (ritualType == AnchorPrefs.RITUAL_GOOD_APP) {
            R.id.btnRitualGoodApp
        } else {
            R.id.btnRitualBreathe
        }

        suppressToggleEvents = true
        if (toggleGroup.checkedButtonId != checkedId) {
            toggleGroup.check(checkedId)
        } else {
            updateGoodAppCard(ritualType)
        }
        suppressToggleEvents = false
    }

    private fun updateGoodAppCard(ritualType: String) {
        goodAppCard.visibility = if (ritualType == AnchorPrefs.RITUAL_GOOD_APP) View.VISIBLE else View.GONE
        if (ritualType != AnchorPrefs.RITUAL_GOOD_APP) return

        val prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val packageName = prefs.getString(AnchorPrefs.KEY_GOOD_APP_PACKAGE, null)
        val appInfo = packageName?.takeUnless { isBlockedApp(it) }?.let { loadGoodAppInfo(it) }

        if (appInfo == null) {
            if (!packageName.isNullOrBlank()) {
                prefs.edit().remove(AnchorPrefs.KEY_GOOD_APP_PACKAGE).apply()
            }
            selectedGoodAppRow.visibility = View.GONE
            emptyGoodAppState.visibility = View.VISIBLE
            return
        }

        selectedGoodAppIcon.setImageDrawable(appInfo.icon)
        selectedGoodAppName.text = appInfo.name
        selectedGoodAppRow.visibility = View.VISIBLE
        emptyGoodAppState.visibility = View.GONE
    }

    private fun loadGoodAppInfo(packageName: String): AppInfo? {
        val pm = requireContext().packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return null
        val resolveInfo = pm.resolveActivity(launchIntent, 0) ?: return null

        return AppInfo(
            name = resolveInfo.loadLabel(pm).toString(),
            packageName = packageName,
            icon = resolveInfo.loadIcon(pm)
        )
    }

    private fun isBlockedApp(packageName: String): Boolean {
        val prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val blockedApps = prefs.getStringSet(AnchorPrefs.KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        return blockedApps.contains(packageName)
    }
}
