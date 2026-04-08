package com.example.anchor

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
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

        if (savedInstanceState != null) {
            secondsRemaining = savedInstanceState.getInt(KEY_SECONDS_LEFT, BREATHING_DURATION_SEC)
            val focusVisible = savedInstanceState.getBoolean(KEY_FOCUS_VISIBLE, false)
            if (focusVisible) {
                showFocusPhaseImmediate()
            } else {
                startBreathingPhaseFromCountdown()
            }
        } else {
            startBreathingPhase()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SECONDS_LEFT, secondsRemaining)
        outState.putBoolean(KEY_FOCUS_VISIBLE, findViewById<View>(R.id.focusPhase).visibility == View.VISIBLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        breathingAnimator?.cancel()
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun startBreathingPhase() {
        val breathingPhase = findViewById<View>(R.id.breathingPhase)
        val focusPhase = findViewById<View>(R.id.focusPhase)
        breathingPhase.visibility = View.VISIBLE
        focusPhase.visibility = View.GONE

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
        breathingPhase.visibility = View.VISIBLE
        focusPhase.visibility = View.GONE

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
        findViewById<View>(R.id.focusPhase).alpha = 1f
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
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
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
        private const val BREATH_SCALE_MIN = 0.72f
        private const val BREATH_SCALE_MAX = 1.12f
        private const val BREATH_ALPHA_MIN = 0.35f
        private const val BREATH_ALPHA_MAX = 0.78f
        private const val KEY_SECONDS_LEFT = "seconds_remaining"
        private const val KEY_FOCUS_VISIBLE = "focus_visible"
    }
}
