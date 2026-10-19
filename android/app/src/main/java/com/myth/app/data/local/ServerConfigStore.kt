package com.myth.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

class ServerConfigStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("myth_server_prefs", Context.MODE_PRIVATE)

    var onUrlChanged: ((baseUrl: String, wsUrl: String) -> Unit)? = null

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, defaultUrl()) ?: defaultUrl()
    }

    fun getWsUrl(): String {
        return toWsUrl(getServerUrl())
    }

    fun setServerUrl(inputUrl: String) {
        var normalized = inputUrl.trim().trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        onUrlChanged?.invoke(normalized, toWsUrl(normalized))
    }

    private fun toWsUrl(httpUrl: String): String {
        val wsBase = if (httpUrl.startsWith("https://")) {
            httpUrl.replaceFirst("https://", "wss://")
        } else {
            httpUrl.replaceFirst("http://", "ws://")
        }
        return "$wsBase/ws/v1/messaging"
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"

        // Public cloud backend URL for physical devices everywhere
        const val PRODUCTION_SERVER_URL = "https://myth-server.onrender.com"
        const val EMULATOR_SERVER_URL = "http://10.0.2.2:8080"

        fun isEmulator(): Boolean {
            return (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || Build.HARDWARE.contains("goldfish")
                    || Build.HARDWARE.contains("ranchu")
                    || Build.PRODUCT.contains("sdk_gphone"))
        }

        fun defaultUrl(): String {
            return if (isEmulator()) EMULATOR_SERVER_URL else PRODUCTION_SERVER_URL
        }
    }
}
