package com.myth.app.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager

class ScreenSecurityManager(private val activity: Activity) {

    private var screenCaptureCallback: Activity.ScreenCaptureCallback? = null

    /**
     * Enables FLAG_SECURE on the window. Prevents screen recording, screenshots,
     * and hides the app preview in the Android recent apps / task-switcher screen.
     */
    fun enableScreenSecurity() {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    /**
     * Registers for screenshot detection events on Android 14+ (API 34+).
     * If detected, the provided lambda is invoked to broadcast a screenshot alert.
     */
    fun registerScreenshotDetection(onScreenshotDetected: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = Activity.ScreenCaptureCallback {
                onScreenshotDetected()
            }
            screenCaptureCallback = callback
            activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        }
    }

    fun unregisterScreenshotDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCaptureCallback?.let {
                activity.unregisterScreenCaptureCallback(it)
                screenCaptureCallback = null
            }
        }
    }
}
