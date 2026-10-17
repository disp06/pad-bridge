package com.padbridge.wear

import android.app.Application
import com.padbridge.wear.input.InputHub

class PadBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InputHub.init(this)
    }
}
