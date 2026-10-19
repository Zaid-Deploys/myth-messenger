package com.myth.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

class ServerConfigStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("myth_server_prefs", Context.MODE_PRIVATE)

    var onUrlChanged: ((baseUrl: String, wsUrl: String) -> Unit)? = null

    fun getServerUrl(): String {
        val saved = prefs.getString(KEY_SERVER_URL, null)
        if (saved.isNullOrBlank() || saved.contains("10.0.2.2") || saved.contains("localhost") || saved.contains("192.168.")) {
            return PRODUCTION_SERVER_URL
        }
        return saved
    }

    fun getWsUrl(): String {
        return toWsUrl(getServerUrl())
    }

    fun setServerUrl(inputUrl: String) {
        var normalized = inputUrl.trim().trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
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

        // Live Render Cloud Backend URL - accessible anywhere in the world
        const val PRODUCTION_SERVER_URL = "https://myth-messenger.onrender.com"

        fun defaultUrl(): String = PRODUCTION_SERVER_URL
    }
}
