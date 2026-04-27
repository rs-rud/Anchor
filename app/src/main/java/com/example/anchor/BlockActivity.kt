package com.example.anchor

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var breathingAnimator: Animator? = null
    private var countdownRunnable: Runnable? = null
    private var redirectRunnable: Runnable? = null
    private var metricsAnimator: ValueAnimator? = null
    private var metricsCompleteRunnable: Runnable? = null
    private var currentRedirectPackage: String? = null
    private var currentRedirectLaunchAtMillis = 0L
    private var shameTargetSentence: String? = null
    private var shameTextWatcher: TextWatcher? = null
    private var shameCompleted = false
    private var metricsCompleted = false

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
        findViewById<MaterialButton>(R.id.btnShameGoHome).setOnClickListener { goHome() }
        findViewById<MaterialButton>(R.id.btnShameConfirm).setOnClickListener { onShameConfirmed() }

        val savedRedirectPackage = savedInstanceState?.getString(KEY_REDIRECT_PACKAGE)
        val savedShameVisible = savedInstanceState?.getBoolean(KEY_SHAME_VISIBLE, false) == true
        val savedMetricsVisible = savedInstanceState?.getBoolean(KEY_METRICS_VISIBLE, false) == true

        if (!savedRedirectPackage.isNullOrBlank()) {
            val savedLaunchAtMillis = savedInstanceState?.getLong(KEY_REDIRECT_LAUNCH_AT, 0L) ?: 0L
            showRedirectPhase(savedRedirectPackage, savedLaunchAtMillis)
        } else if (savedShameVisible) {
            val target = savedInstanceState?.getString(KEY_SHAME_TARGET).orEmpty()
            val input = savedInstanceState?.getString(KEY_SHAME_INPUT).orEmpty()
            startShamePhase(restoredTarget = target.ifBlank { null }, restoredInput = input)
        } else if (savedMetricsVisible) {
            val remaining = savedInstanceState?.getLong(KEY_METRICS_REMAINING_MS, METRICS_DURATION_MS) ?: METRICS_DURATION_MS
            startMetricsPhase(restoredRemainingMs = remaining)
        } else if (savedInstanceState != null) {
            secondsRemaining = savedInstanceState.getInt(KEY_SECONDS_LEFT, BREATHING_DURATION_SEC)
            val focusVisible = savedInstanceState.getBoolean(KEY_FOCUS_VISIBLE, false)
            if (focusVisible) {
                showFocusPhaseImmediate()
            } else {
                startBreathingPhaseFromCountdown()
            }
        } else {
            routeToInitialPhase()
        }
    }

    private fun routeToInitialPhase() {
        val prefs = getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        val ritual = prefs.getString(AnchorPrefs.KEY_RITUAL_TYPE, AnchorPrefs.RITUAL_BREATHING)

        when {
            shouldLaunchGoodAppRitual() -> showRedirectPhase(getGoodAppPackage().orEmpty())
            ritual == AnchorPrefs.RITUAL_SHAME -> startShamePhase()
            ritual == AnchorPrefs.RITUAL_METRICS -> startMetricsPhase()
            else -> startBreathingPhase()
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
        val shamePhase = findViewById<View>(R.id.shamePhase)
        if (shamePhase.visibility == View.VISIBLE) {
            outState.putBoolean(KEY_SHAME_VISIBLE, true)
            outState.putString(KEY_SHAME_TARGET, shameTargetSentence)
            val input = findViewById<TextInputEditText>(R.id.etShameInput).text?.toString().orEmpty()
            outState.putString(KEY_SHAME_INPUT, input)
        }
        val metricsPhase = findViewById<View>(R.id.metricsPhase)
        if (metricsPhase.visibility == View.VISIBLE) {
            outState.putBoolean(KEY_METRICS_VISIBLE, true)
            val pb = findViewById<LinearProgressIndicator>(R.id.pbMetrics)
            val remaining = (METRICS_DURATION_MS * (1f - pb.progress / pb.max.toFloat())).toLong()
            outState.putLong(KEY_METRICS_REMAINING_MS, remaining.coerceIn(0L, METRICS_DURATION_MS))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        breathingAnimator?.cancel()
        metricsAnimator?.cancel()
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        redirectRunnable?.let { mainHandler.removeCallbacks(it) }
        metricsCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        shameTextWatcher?.let { findViewById<TextInputEditText>(R.id.etShameInput).removeTextChangedListener(it) }
    }

    private fun startBreathingPhase() {
        showOnly(R.id.breathingPhase)
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L

        secondsRemaining = BREATHING_DURATION_SEC
        findViewById<TextView>(R.id.tvBreathingCountdown).text = secondsRemaining.toString()

        startBreathingAnimation()
        scheduleCountdownTicks()
    }

    private fun startBreathingPhaseFromCountdown() {
        showOnly(R.id.breathingPhase)
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L

        findViewById<TextView>(R.id.tvBreathingCountdown).text = secondsRemaining.toString()

        startBreathingAnimation()
        scheduleCountdownTicks()
    }

    private fun startBreathingAnimation() {
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
                    val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
                    TelemetryTracker.logEvent(
                        eventType = "breathing_completed",
                        metadata = mapOf(
                            "package" to blockedPackage,
                            "duration_sec" to BREATHING_DURATION_SEC.toString(),
                            "ritual_type" to prefsRitualType()
                        )
                    )
                    transitionToFocusPhase()
                } else {
                    findViewById<TextView>(R.id.tvBreathingCountdown).text = secondsRemaining.toString()
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        countdownRunnable = tick
        mainHandler.postDelayed(tick, 1000L)
    }

    private fun startShamePhase(restoredTarget: String? = null, restoredInput: String = "") {
        showOnly(R.id.shamePhase)
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L
        shameCompleted = false

        val sentences = resources.getStringArray(R.array.shame_sentences)
        val target = restoredTarget?.takeIf { it.isNotBlank() }
            ?: sentences.randomOrNull()
            ?: getString(R.string.shame_input_hint)
        shameTargetSentence = target

        findViewById<TextView>(R.id.tvShameTarget).text = target
        val errorView = findViewById<TextView>(R.id.tvShameError)
        val confirmButton = findViewById<MaterialButton>(R.id.btnShameConfirm)
        val input = findViewById<TextInputEditText>(R.id.etShameInput)

        // Detach any existing watcher (rotation re-entry) before binding fresh state.
        shameTextWatcher?.let { input.removeTextChangedListener(it) }

        if (input.text?.toString() != restoredInput) {
            input.setText(restoredInput)
            input.setSelection(restoredInput.length)
        }
        updateShameMatchUi(restoredInput, target, confirmButton, errorView)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateShameMatchUi(s?.toString().orEmpty(), target, confirmButton, errorView)
            }
        }
        shameTextWatcher = watcher
        input.addTextChangedListener(watcher)
    }

    private fun updateShameMatchUi(
        currentInput: String,
        target: String,
        confirmButton: MaterialButton,
        errorView: TextView
    ) {
        val matches = currentInput == target
        confirmButton.isEnabled = matches
        if (currentInput.isEmpty() || matches) {
            errorView.text = ""
            errorView.visibility = View.INVISIBLE
            return
        }
        val mismatches = countMismatches(currentInput, target)
        errorView.text = getString(R.string.shame_error_format, mismatches)
        errorView.visibility = View.VISIBLE
    }

    private fun countMismatches(input: String, target: String): Int {
        val maxLen = maxOf(input.length, target.length)
        var diff = 0
        for (i in 0 until maxLen) {
            val a = input.getOrNull(i)
            val b = target.getOrNull(i)
            if (a != b) diff++
        }
        return diff
    }

    private fun prefsRitualType(): String =
        getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
            .getString(AnchorPrefs.KEY_RITUAL_TYPE, AnchorPrefs.RITUAL_BREATHING)
            ?: AnchorPrefs.RITUAL_BREATHING

    private fun onShameConfirmed() {
        val target = shameTargetSentence ?: return
        val input = findViewById<TextInputEditText>(R.id.etShameInput).text?.toString().orEmpty()
        if (input != target) return
        if (shameCompleted) return
        shameCompleted = true

        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
        TelemetryTracker.logEvent(
            eventType = "shame_completed",
            metadata = mapOf(
                "package" to blockedPackage,
                "sentence_length" to target.length.toString(),
                "ritual_type" to prefsRitualType()
            )
        )

        transitionToFocusPhase()
    }

    private fun startMetricsPhase(restoredRemainingMs: Long = METRICS_DURATION_MS) {
        showOnly(R.id.metricsPhase)
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L
        metricsCompleted = false

        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
        val attempts = readAttemptsToday(blockedPackage)
        val appLabel = loadAppLabel(blockedPackage).ifBlank { blockedPackage }

        findViewById<TextView>(R.id.tvMetricsAppName).text = appLabel
        findViewById<TextView>(R.id.tvMetricsCount).text = String.format(Locale.US, "%d", attempts)
        findViewById<TextView>(R.id.tvMetricsSubtitle).text = getString(R.string.metrics_subtitle_format, appLabel)
        findViewById<ImageView>(R.id.ivMetricsAppIcon).setImageDrawable(loadAppIcon(blockedPackage))

        TelemetryTracker.logEvent(
            eventType = "metrics_shown",
            metadata = mapOf(
                "package" to blockedPackage,
                "attempts_today" to attempts.toString(),
                "ritual_type" to prefsRitualType()
            )
        )

        val pb = findViewById<LinearProgressIndicator>(R.id.pbMetrics)
        val totalMs = METRICS_DURATION_MS
        val remainingMs = restoredRemainingMs.coerceIn(0L, totalMs)
        val elapsedAtStart = totalMs - remainingMs

        pb.max = 1000
        pb.progress = (elapsedAtStart * 1000L / totalMs).toInt()

        metricsAnimator?.cancel()
        metricsAnimator = ValueAnimator.ofInt(pb.progress, 1000).apply {
            duration = remainingMs
            interpolator = null
            addUpdateListener { pb.progress = it.animatedValue as Int }
            start()
        }

        metricsCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        val finishTask = Runnable {
            if (metricsCompleted) return@Runnable
            metricsCompleted = true
            transitionToFocusPhase()
        }
        metricsCompleteRunnable = finishTask
        mainHandler.postDelayed(finishTask, remainingMs)
    }

    private fun readAttemptsToday(packageName: String): Int {
        if (packageName.isBlank()) return 0
        val today = DAY_BUCKET_FORMAT.format(Date())
        val key = AnchorPrefs.attemptsKey(packageName, today)
        return getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE).getInt(key, 0)
    }

    private fun loadAppLabel(packageName: String): String {
        if (packageName.isBlank()) return ""
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    private fun loadAppIcon(packageName: String): android.graphics.drawable.Drawable? {
        if (packageName.isBlank()) return null
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun transitionToFocusPhase() {
        val breathing = findViewById<View>(R.id.breathingPhase)
        val shame = findViewById<View>(R.id.shamePhase)
        val metrics = findViewById<View>(R.id.metricsPhase)
        val redirect = findViewById<View>(R.id.redirectPhase)
        val focus = findViewById<View>(R.id.focusPhase)

        redirect.visibility = View.GONE
        currentRedirectPackage = null
        currentRedirectLaunchAtMillis = 0L

        val outgoing = listOf(breathing, shame, metrics).firstOrNull { it.visibility == View.VISIBLE }
        if (outgoing == null) {
            showFocusPhaseImmediate()
            return
        }

        outgoing.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction {
                outgoing.visibility = View.GONE
                outgoing.alpha = 1f
                focus.alpha = 0f
                focus.visibility = View.VISIBLE
                focus.animate().alpha(1f).setDuration(300L).start()
            }
            .start()
    }

    private fun showFocusPhaseImmediate() {
        showOnly(R.id.focusPhase)
        findViewById<View>(R.id.focusPhase).alpha = 1f
    }

    private fun showOnly(visibleId: Int) {
        val ids = listOf(
            R.id.breathingPhase,
            R.id.focusPhase,
            R.id.redirectPhase,
            R.id.shamePhase,
            R.id.metricsPhase
        )
        for (id in ids) {
            val v = findViewById<View>(id)
            v.visibility = if (id == visibleId) View.VISIBLE else View.GONE
        }
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

        showOnly(R.id.redirectPhase)
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
                "good_package" to packageName,
                "ritual_type" to prefsRitualType()
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
            prefs.edit().putLong(key, until).commit()

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
        private const val METRICS_DURATION_MS = 5000L
        private const val BREATH_SCALE_MIN = 0.72f
        private const val BREATH_SCALE_MAX = 1.12f
        private const val BREATH_ALPHA_MIN = 0.35f
        private const val BREATH_ALPHA_MAX = 0.78f
        private const val KEY_SECONDS_LEFT = "seconds_remaining"
        private const val KEY_FOCUS_VISIBLE = "focus_visible"
        private const val KEY_REDIRECT_PACKAGE = "redirect_package"
        private const val KEY_REDIRECT_LAUNCH_AT = "redirect_launch_at"
        private const val KEY_SHAME_VISIBLE = "shame_visible"
        private const val KEY_SHAME_TARGET = "shame_target"
        private const val KEY_SHAME_INPUT = "shame_input"
        private const val KEY_METRICS_VISIBLE = "metrics_visible"
        private const val KEY_METRICS_REMAINING_MS = "metrics_remaining_ms"
        private val DAY_BUCKET_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
