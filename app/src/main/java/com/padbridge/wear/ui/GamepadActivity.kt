package com.padbridge.wear.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.padbridge.wear.input.InputHub
import com.padbridge.wear.service.BridgeService
import com.padbridge.wear.service.BridgeStatus
import com.padbridge.wear.util.Prefs

/**
 * Base for every screen. Android delivers gamepad events only to the focused window, so each
 * of our activities forwards them to [InputHub] before the view hierarchy sees them. That also
 * stops the pad's B / Back from closing the screen.
 *
 * While the bridge runs it can keep the screen on (and dimmed), because a screen-off watch
 * pauses the activity and gamepad input stops.
 */
abstract class GamepadActivity : Activity() {
    protected val prefs by lazy { Prefs(this) }

    private val statusListener: () -> Unit = { applyScreenMode() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        InputHub.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        InputHub.onMotionEvent(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onResume() {
        super.onResume()
        BridgeStatus.listeners.add(statusListener)
        applyScreenMode()
    }

    override fun onPause() {
        BridgeStatus.listeners.remove(statusListener)
        super.onPause()
    }

    protected fun applyScreenMode() {
        val bridging = BridgeService.running
        if (bridging && prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        val want = if (bridging && prefs.keepScreenOn && prefs.dimScreen) 0.05f
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want
            window.attributes = lp
        }
    }
}
