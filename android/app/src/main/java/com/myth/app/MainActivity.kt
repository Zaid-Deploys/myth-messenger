package com.myth.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.myth.app.security.ScreenSecurityManager
import com.myth.app.ui.navigation.MythNavGraph
import com.myth.app.ui.theme.MythTheme

class MainActivity : ComponentActivity() {

    private lateinit var screenSecurityManager: ScreenSecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as MythApplication

        // 1. Enforce FLAG_SECURE on window (blocks screenshots and recent-task previews)
        screenSecurityManager = ScreenSecurityManager(this)
        screenSecurityManager.enableScreenSecurity()

        // 2. Register Android 14+ screenshot detection callback
        screenSecurityManager.registerScreenshotDetection {
            Toast.makeText(this, "Screenshot detected. Security notice sent to conversation.", Toast.LENGTH_SHORT).show()
        }

        // 3. Request RECORD_AUDIO runtime permission for encrypted voice calls
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }

        setContent {
            MythTheme {
                val navController = rememberNavController()
                MythNavGraph(
                    navController = navController,
                    apiClient = app.apiClient,
                    chatManager = app.chatManager,
                    callManager = app.callManager
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenSecurityManager.unregisterScreenshotDetection()
    }
}
