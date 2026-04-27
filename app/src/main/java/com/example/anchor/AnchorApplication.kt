package com.example.anchor

import android.app.Application
import com.google.android.libraries.places.api.Places

class AnchorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val key = BuildConfig.PLACES_API_KEY
        if (key.isNotBlank() && !Places.isInitialized()) {
            Places.initialize(applicationContext, key)
        }
    }
}
