package com.example.anchor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith

class PaywallActivity : AppCompatActivity() {

    private var packageToBuy: Package? = null
    private lateinit var btnSubscribe: MaterialButton
    private lateinit var btnClose: View
    private var secretTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_paywall)

        TelemetryTracker.logEvent("paywall_viewed")

        btnSubscribe = findViewById(R.id.btnSubscribe)
        btnClose = findViewById(R.id.btnClosePaywall)
        val btnRestore = findViewById<TextView>(R.id.tvRestorePurchases)

        // Find the title for the secret bypass
        val secretBypassView = findViewById<View>(R.id.paywallRoot)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.paywallRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ==========================================
        // FIX 1: THE INFINITE LOOP FIX
        // ==========================================
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                escapeToHomeTab()
            }
        })
        btnClose.setOnClickListener { escapeToHomeTab() }

        // ==========================================
        // FIX 2: THE SECRET DEV BYPASS
        // ==========================================
// Change tvTitle.setOnClickListener to:
        secretBypassView.setOnClickListener {
            secretTapCount++
            if (secretTapCount == 5) {
                Toast.makeText(this, "DEV OVERRIDE: Pro Unlocked", Toast.LENGTH_SHORT).show()
                TelemetryTracker.logEvent("purchase_successful", mapOf("method" to "dev_override"))

                val prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_pro_unlocked", true).apply()
                finish()
            }
        }

        // ==========================================
        // REAL REVENUECAT LOGIC
        // ==========================================
        btnRestore.setOnClickListener { restorePurchases() }

        btnSubscribe.setOnClickListener {
            packageToBuy?.let { makePurchase(it) } ?: run {
                Toast.makeText(this, "Loading packages, please wait...", Toast.LENGTH_SHORT).show()
            }
        }

        fetchOfferings()
    }

    private fun escapeToHomeTab() {
        // This explicitly routes them back to MainActivity and clears the stack,
        // preventing RitualFragment from looping the paywall!
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun fetchOfferings() {
        btnSubscribe.text = getString(R.string.loading)
        btnSubscribe.isEnabled = false

        Purchases.sharedInstance.getOfferingsWith(
            onError = { error ->
                Log.e("Paywall", "Error fetching offerings: ${error.message}")
                btnSubscribe.text = getString(R.string.error_loading)
            },
            onSuccess = { offerings ->
                val offering = offerings.current
                if (offering != null && offering.availablePackages.isNotEmpty()) {
                    packageToBuy = offering.availablePackages.first()
                    val price = packageToBuy?.product?.price?.formatted
                    btnSubscribe.text = "Unlock Pro - $price"
                    btnSubscribe.isEnabled = true
                } else {
                    btnSubscribe.text = "No packages available"
                }
            }
        )
    }

    private fun makePurchase(pkg: Package) {
        TelemetryTracker.logEvent("purchase_attempted")
        btnSubscribe.isEnabled = false
        btnSubscribe.text = "Processing..."

        val params = PurchaseParams.Builder(this, pkg).build()

        Purchases.sharedInstance.purchaseWith(
            params,
            onError = { error, userCancelled ->
                btnSubscribe.isEnabled = true
                btnSubscribe.text = "Try Again"
                if (!userCancelled) {
                    Toast.makeText(this, "Purchase Error: ${error.message}", Toast.LENGTH_LONG).show()
                    TelemetryTracker.logEvent("purchase_failed", mapOf("error" to error.message))
                }
            },
            onSuccess = { _, customerInfo ->
                if (customerInfo.entitlements["pro_access"]?.isActive == true) {
                    grantProAccess()
                }
            }
        )
    }

    private fun restorePurchases() {
        Toast.makeText(this, "Restoring...", Toast.LENGTH_SHORT).show()
        Purchases.sharedInstance.restorePurchasesWith(
            onError = { error ->
                Toast.makeText(this, "Restore failed: ${error.message}", Toast.LENGTH_SHORT).show()
            },
            onSuccess = { customerInfo ->
                if (customerInfo.entitlements["pro_access"]?.isActive == true) {
                    grantProAccess()
                } else {
                    Toast.makeText(this, "No active subscriptions found.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun grantProAccess() {
        TelemetryTracker.logEvent("purchase_successful")
        val prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_pro_unlocked", true).apply()
        Toast.makeText(this, "Welcome to Anchor Pro!", Toast.LENGTH_SHORT).show()
        finish()
    }
}