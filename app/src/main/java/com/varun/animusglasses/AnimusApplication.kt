package com.varun.animusglasses

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class AnimusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Meta Wearables SDK — must be called before any SDK API use
        Wearables.initialize(this)
    }
}
