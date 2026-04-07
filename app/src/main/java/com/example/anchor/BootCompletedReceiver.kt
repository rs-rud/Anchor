package com.example.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed — re-registering geofences")
        GeofenceManager(context).reRegisterFromPrefs()
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
