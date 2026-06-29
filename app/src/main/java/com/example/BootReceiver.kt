package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d(TAG, "Boot completed event received! Auto-starting FPV Air Unit Foreground Service.")
            
            try {
                val serviceIntent = Intent(context, StreamingService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "StreamingService started successfully from boot.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start StreamingService on boot", e)
            }
        }
    }
}
