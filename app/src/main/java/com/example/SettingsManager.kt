package com.example

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "fpv_settings"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PORT = "port"
        private const val KEY_CODEC = "codec"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate_mbps"

        const val DEFAULT_IP = "192.168.1.10"
        const val DEFAULT_PORT = 5600
        const val DEFAULT_CODEC = "H264"
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE = 4 // Mbps
    }

    var ipAddress: String
        get() = prefs.getString(KEY_IP_ADDRESS, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit().putString(KEY_IP_ADDRESS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var codec: String
        get() = prefs.getString(KEY_CODEC, DEFAULT_CODEC) ?: DEFAULT_CODEC
        set(value) = prefs.edit().putString(KEY_CODEC, value).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, DEFAULT_FPS)
        set(value) = prefs.edit().putInt(KEY_FPS, value).apply()

    var bitrate: Int
        get() = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE)
        set(value) = prefs.edit().putInt(KEY_BITRATE, value).apply()
}
