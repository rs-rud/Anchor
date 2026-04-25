package com.example.anchor

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

class BlockActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var breathingAnimator: Animator? = null
    private var countdownRunnable: Runnable? = null
    private var redirectRunnable: Runnable? = null
    private var currentRedirectPackage: String? = null
    private var currentRedirectLaunchAtMillis = 0L

    private var secondsRemaining = BREATHING_DURATION_SEC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_block)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.blockRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    goHome()
                }
            }
        )

        findViewById<MaterialButton>(R.id.btnGoHome).setOnClickListener { goHome() }
        findViewById<MaterialButton>(R.id.btnJailbreak).setOnClickListener { openBlockedAppAnyway() }

        val savedRedirectPackage = savedInstanceState?.getString(KEY_REDIRECT_PACKAGE)
        if (!savedRedirectPackage.isNullOrBlank()) {
            val savedLaunchAtMillis = savedInstanceState?.getLong(KEY_REDIRECT_LAUNCH_AT, 0L) ?: 0L
            showRedirectPhase(savedRedirectPackage, savedLaunchAtMillis)
        } else if (savedInstanceState != null) {
            secondsRemaining = savedInstanceState.getInt(KEY_SECONDS_LEFT, BREATHING_DURATION_SEC)
            val focusVisible = savedInstanceState.getBoolean(KEY_FOCUS_VISIBLE, false)
            if (focusVisible) {
                showFocusPhaseImmediate()
            } else {
                startBreathingPhaseFromCountdown()
            }
        } else if (shouldLaunchGoodAppRitual()) {
            showRedirectPhase(getGoodAppPackage().orEmpty())
        } else {
            startBreathingPhase()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SECONDS_LEFT, secondsRemaining)
        outState.putBoolean(KEY_FOCUS_VISIBLE, findViewById<View>(R.id.focusPhase).visibility == View.VISIBLE)
        if (findViewById<View>(R.id.redirectPhase).visibility == View.VISIBLE) {
            outState.putString(KEY_REDIRECT_PACKAGE, currentRedirectPackage)
            outState.putLong(KEY_REDIRECT_LAUNCH_AT, currentRedirectLaunchAtMillis)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        breathingAnimator?.cancel()
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        redirectRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun startBreathingPhase() {
        val breathingPhase = findViewById<View>(R.id.breathingPhase)
        val focusPhase = findViewById<View>(R.id.focusPhase)
        val redirectPhase = findViewById<View>(R.id.redirectPhase)
        breathingPhase.visibility = View.VISIBLE
        focusPhase.visibility = View.GONE
        redirectPhase.visibility = View.GONE
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L

        secondsRemaining = BREATHING_DURATION_SEC
        findViewById<TextView>(R.id.tvBreathingCountdown).text = secondsRemaining.toString()

        val circle = findViewById<View>(R.id.breathingCircle)
        circle.post {
            circle.pivotX = circle.width / 2f
            circle.pivotY = circle.height / 2f

            breathingAnimator?.cancel()
            breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(
                circle,
                PropertyValuesHolder.ofFloat(View.SCALE_X, BREATH_SCALE_MIN, BREATH_SCALE_MAX),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, BREATH_SCALE_MIN, BREATH_SCALE_MAX),
                PropertyValuesHolder.ofFloat(View.ALPHA, BREATH_ALPHA_MIN, BREATH_ALPHA_MAX)
            ).apply {
                duration = BREATH_CYCLE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        scheduleCountdownTicks()
    }

    private fun startBreathingPhaseFromCountdown() {
        val breathingPhase = findViewById<View>(R.id.breathingPhase)
        val focusPhase = findViewById<View>(R.id.focusPhase)
        val redirectPhase = findViewById<View>(R.id.redirectPhase)
        breathingPhase.visibility = View.VISIBLE
        focusPhase.visibility = View.GONE
        redirectPhase.visibility = View.GONE
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L

        findViewById<TextView>(R.id.tvBreathingCountdown).text = secondsRemaining.toString()

        val circle = findViewById<View>(R.id.breathingCircle)
        circle.post {
            circle.pivotX = circle.width / 2f
            circle.pivotY = circle.height / 2f
            breathingAnimator?.cancel()
            breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(
                circle,
                PropertyValuesHolder.ofFloat(View.SCALE_X, BREATH_SCALE_MIN, BREATH_SCALE_MAX),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, BREATH_SCALE_MIN, BREATH_SCALE_MAX),
                PropertyValuesHolder.ofFloat(View.ALPHA, BREATH_ALPHA_MIN, BREATH_ALPHA_MAX)
            ).apply {
                duration = BREATH_CYCLE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        scheduleCountdownTicks()
    }

    private fun scheduleCountdownTicks() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                secondsRemaining--
                if (secondsRemaining <= 0) {
                    breathingAnimator?.cancel()
                    val circle = findViewById<View>(R.id.breathingCircle)
                    circle.scaleX = 1f
                    circle.scaleY = 1f
                    showFocusPhaseAnimated()
                } else {
                    findViewById<TextView>(R.id.tvBreathingCountdown).text = secondsRemaining.toString()
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        countdownRunnable = tick
        mainHandler.postDelayed(tick, 1000L)
    }

    private fun showFocusPhaseAnimated() {
        val breathingPhase = findViewById<View>(R.id.breathingPhase)
        val focusPhase = findViewById<View>(R.id.focusPhase)
        val redirectPhase = findViewById<View>(R.id.redirectPhase)
        redirectPhase.visibility = View.GONE
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L
        breathingPhase.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction {
                breathingPhase.visibility = View.GONE
                breathingPhase.alpha = 1f
                focusPhase.alpha = 0f
                focusPhase.visibility = View.VISIBLE
                focusPhase.animate().alpha(1f).setDuration(300L).start()
            }
            .start()
    }

    private fun showFocusPhaseImmediate() {
        findViewById<View>(R.id.breathingPhase).visibility = View.GONE
        findViewById<View>(R.id.focusPhase).visibility = View.VISIBLE
        findViewById<View>(R.id.redirectPhase).visibility = View.GONE
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L
        findViewById<View>(R.id.focusPhase).alpha = 1f
    }

    private fun shouldLaunchGoodAppRitual(): Boolean {
        val prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val ritual = prefs.getString(AnchorPrefs.KEY_RITUAL_TYPE, AnchorPrefs.RITUAL_BREATHING)
        val goodPackage = getGoodAppPackage()
        val blockedApps = prefs.getStringSet(AnchorPrefs.KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

        return ritual == AnchorPrefs.RITUAL_GOOD_APP &&
            !goodPackage.isNullOrBlank() &&
            !blockedApps.contains(goodPackage) &&
            packageManager.getLaunchIntentForPackage(goodPackage) != null
    }

    private fun getGoodAppPackage(): String? {
        return getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            .getString(AnchorPrefs.KEY_GOOD_APP_PACKAGE, null)
    }

    private fun showRedirectPhase(
        packageName: String,
        launchAtMillis: Long = System.currentTimeMillis() + REDIRECT_DELAY_MS
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (packageName.isBlank() || launchIntent == null) {
            startBreathingPhase()
            return
        }

        breathingAnimator?.cancel()
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        redirectRunnable?.let { mainHandler.removeCallbacks(it) }

        val breathingPhase = findViewById<View>(R.id.breathingPhase)
        val focusPhase = findViewById<View>(R.id.focusPhase)
        val redirectPhase = findViewById<View>(R.id.redirectPhase)
        breathingPhase.visibility = View.GONE
        focusPhase.visibility = View.GONE
        redirectPhase.visibility = View.VISIBLE
        currentRedirectPackage = packageName
        currentRedirectLaunchAtMillis = if (launchAtMillis > 0L) {
            launchAtMillis
        } else {
            System.currentTimeMillis() + REDIRECT_DELAY_MS
        }

        val resolveInfo = packageManager.resolveActivity(launchIntent, 0)
        val appName = resolveInfo?.loadLabel(packageManager)?.toString().orEmpty()
        if (resolveInfo != null) {
            findViewById<ImageView>(R.id.ivRedirectGoodAppIcon).setImageDrawable(resolveInfo.loadIcon(packageManager))
        }
        findViewById<TextView>(R.id.tvRedirectGoodAppName).text = appName.ifBlank { packageName }
        findViewById<TextView>(R.id.tvRedirectSubtitle).text =
            getString(R.string.redirect_subtitle, appName.ifBlank { packageName })

        val task = Runnable { launchGoodApp(packageName) }
        redirectRunnable = task
        val remainingDelay = (currentRedirectLaunchAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(task, remainingDelay)
    }

    private fun launchGoodApp(packageName: String) {
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            startBreathingPhase()
            return
        }

        TelemetryTracker.logEvent(
            eventType = "block_redirected_to_good_app",
            metadata = mapOf(
                "blocked_package" to blockedPackage,
                "good_package" to packageName
            )
        )

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
        finish()
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun openBlockedAppAnyway() {
        val pkg = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        if (pkg.isNullOrBlank()) {
            goHome()
            return
        }

        // Track the specific "Proceed Anyway" / jailbreak action
        TelemetryTracker.logEvent(
            eventType = "block_bypassed",
            metadata = mapOf(
                "package" to pkg,
                "reason" to "user_override"
            )
        )

        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            val until = System.currentTimeMillis() + AnchorPrefs.JAILBREAK_DURATION_MS
            val prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            val key = AnchorPrefs.jailbreakUntilKey(pkg)

            AnchorDebugLog.log(
                hypothesisId = "H2",
                location = "BlockActivity.kt:openBlockedAppAnyway",
                message = "before_jailbreak_commit",
                data = mapOf(
                    "pkg" to pkg,
                    "until" to until,
                    "usedCommit" to true
                )
            )

            val committed = prefs.edit().putLong(key, until).commit()

            AnchorDebugLog.log(
                hypothesisId = "H2",
                location = "BlockActivity.kt:openBlockedAppAnyway",
                message = "after_jailbreak_commit",
                data = mapOf("pkg" to pkg, "committed" to committed, "until" to until)
            )

            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
            finish()
        } else {
            Toast.makeText(this, R.string.jailbreak_launch_failed, Toast.LENGTH_SHORT).show()
            goHome()
        }
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
        private const val BREATHING_DURATION_SEC = 10
        private const val BREATH_CYCLE_MS = 4000L
        private const val REDIRECT_DELAY_MS = 1500L
        private const val BREATH_SCALE_MIN = 0.72f
        private const val BREATH_SCALE_MAX = 1.12f
        private const val BREATH_ALPHA_MIN = 0.35f
        private const val BREATH_ALPHA_MAX = 0.78f
        private const val KEY_SECONDS_LEFT = "seconds_remaining"
        private const val KEY_FOCUS_VISIBLE = "focus_visible"
        private const val KEY_REDIRECT_PACKAGE = "redirect_package"
        private const val KEY_REDIRECT_LAUNCH_AT = "redirect_launch_at"
    }
}