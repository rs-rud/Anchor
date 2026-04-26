package com.example.anchor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class RitualFragment : Fragment() {

    private lateinit var optionsContainer: View
    private lateinit var cardBreathe: MaterialCardView
    private lateinit var cardGoodApp: MaterialCardView
    private lateinit var cardShame: MaterialCardView
    private lateinit var cardMetrics: MaterialCardView
    private lateinit var rbBreathe: RadioButton
    private lateinit var rbGoodApp: RadioButton
    private lateinit var rbShame: RadioButton
    private lateinit var rbMetrics: RadioButton
    private lateinit var goodAppCard: View
    private lateinit var selectedGoodAppRow: View
    private lateinit var emptyGoodAppState: TextView
    private lateinit var selectedGoodAppIcon: ImageView
    private lateinit var selectedGoodAppName: TextView
    private lateinit var changeButton: MaterialButton
    private var suppressToggleEvents = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_ritual, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        optionsContainer = view.findViewById(R.id.ritualOptionsContainer)
        cardBreathe = view.findViewById(R.id.cardRitualBreathe)
        cardGoodApp = view.findViewById(R.id.cardRitualGoodApp)
        cardShame = view.findViewById(R.id.cardRitualShame)
        cardMetrics = view.findViewById(R.id.cardRitualMetrics)
        rbBreathe = view.findViewById(R.id.rbRitualBreathe)
        rbGoodApp = view.findViewById(R.id.rbRitualGoodApp)
        rbShame = view.findViewById(R.id.rbRitualShame)
        rbMetrics = view.findViewById(R.id.rbRitualMetrics)
        goodAppCard = view.findViewById(R.id.goodAppCard)
        selectedGoodAppRow = view.findViewById(R.id.selectedGoodAppRow)
        emptyGoodAppState = view.findViewById(R.id.tvGoodAppEmpty)
        selectedGoodAppIcon = view.findViewById(R.id.ivSelectedGoodAppIcon)
        selectedGoodAppName = view.findViewById(R.id.tvSelectedGoodAppName)
        changeButton = view.findViewById(R.id.btnChangeGoodApp)

        changeButton.setOnClickListener {
            startActivity(Intent(requireContext(), GoodAppPickerActivity::class.java))
        }

        cardBreathe.setOnClickListener { selectRitual(AnchorPrefs.RITUAL_BREATHING) }
        cardGoodApp.setOnClickListener { selectRitual(AnchorPrefs.RITUAL_GOOD_APP) }
        cardShame.setOnClickListener { selectRitual(AnchorPrefs.RITUAL_SHAME) }
        cardMetrics.setOnClickListener { selectRitual(AnchorPrefs.RITUAL_METRICS) }
    }

    private fun selectRitual(ritualType: String) {
        if (!suppressToggleEvents) {
            requireContext()
                .getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AnchorPrefs.KEY_RITUAL_TYPE, ritualType)
                .apply()

            TelemetryTracker.logEvent("ritual_changed", mapOf("ritual_type" to ritualType))
        }
        applySelection(ritualType)
    }

    private fun applySelection(ritualType: String) {
        rbBreathe.isChecked = ritualType == AnchorPrefs.RITUAL_BREATHING
        rbGoodApp.isChecked = ritualType == AnchorPrefs.RITUAL_GOOD_APP
        rbShame.isChecked = ritualType == AnchorPrefs.RITUAL_SHAME
        rbMetrics.isChecked = ritualType == AnchorPrefs.RITUAL_METRICS
        updateGoodAppCard(ritualType)
    }

    // We do the Paywall check in onResume so that if they buy it and close the paywall,
    // the fragment instantly refreshes and unlocks!
    override fun onResume() {
        super.onResume()

        val prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val isPro = prefs.getBoolean("is_pro_unlocked", false)

        if (!isPro) {
            // 1. Hide everything so they can't click anything behind the paywall
            optionsContainer.visibility = View.GONE
            goodAppCard.visibility = View.GONE

            // 2. Launch the Paywall instantly
            startActivity(Intent(requireContext(), PaywallActivity::class.java))

            // 3. Log that the ritual paywall was hit
            TelemetryTracker.logEvent("paywall_viewed", mapOf("source" to "ritual_tab"))
            return // Stop executing the rest of the function!
        }

        // --- THEY ARE PRO: SHOW THE UI ---
        optionsContainer.visibility = View.VISIBLE

        val ritualType = prefs.getString(AnchorPrefs.KEY_RITUAL_TYPE, AnchorPrefs.RITUAL_BREATHING)
            ?: AnchorPrefs.RITUAL_BREATHING

        suppressToggleEvents = true
        applySelection(ritualType)
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
