package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.app.NotificationCompat

class StreamingService : Service() {

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "fpv_streaming_channel"

        // Singleton instance reference for easy communication from MainActivity
        @Volatile
        var activeInstance: StreamingService? = null
            private set
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var settingsManager: SettingsManager? = null
    private var udpStreamer: UdpStreamer? = null
    private var encoderManager: EncoderManager? = null
    private var cameraController: CameraController? = null

    // State trackers
    var isStreaming = false
        private set

    var activeWidth = 848
        private set
    var activeHeight = 480
        private set

    private var currentEncoderSurface: Surface? = null
    private var currentPreviewSurface: Surface? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        settingsManager = SettingsManager(this)
        udpStreamer = UdpStreamer()
        encoderManager = EncoderManager()
        cameraController = CameraController(this)

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand invoked")
        
        // Ensure service stays in foreground
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isStreaming) {
            startStreamingFlow()
        }

        return START_STICKY
    }

    private fun getBest480pSize(): Pair<Int, Int> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var backCameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    backCameraId = id
                    break
                }
            }
            if (backCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                backCameraId = cameraManager.cameraIdList[0]
            }
            if (backCameraId != null) {
                val chars = cameraManager.getCameraCharacteristics(backCameraId)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    val sizes = map.getOutputSizes(android.media.MediaCodec::class.java)
                        ?: map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    if (sizes != null && sizes.isNotEmpty()) {
                        Log.d(TAG, "Supported camera output sizes: ${sizes.joinToString { "${it.width}x${it.height}" }}")
                        
                        // Filter sizes with height = 480
                        val sizesAt480 = sizes.filter { it.height == 480 }
                        if (sizesAt480.isNotEmpty()) {
                            // Sort by width descending to get the widest (preferring 854, 848, 800, 720 etc.)
                            val bestWide = sizesAt480.sortedByDescending { it.width }.first()
                            Log.d(TAG, "Selected exact 480p wide size: ${bestWide.width}x${bestWide.height}")
                            return Pair(bestWide.width, bestWide.height)
                        }
                        
                        // Fallback to size closest to 480p height
                        val reasonableSizes = sizes.filter { it.height in 360..540 }
                        if (reasonableSizes.isNotEmpty()) {
                            val best = reasonableSizes.minByOrNull { kotlin.math.abs(it.height - 480) }
                            if (best != null) {
                                Log.d(TAG, "Selected fallback size near 480p: ${best.width}x${best.height}")
                                return Pair(best.width, best.height)
                            }
                        }
                        
                        // Fallback to standard 640x480 if supported
                        if (sizes.any { it.width == 640 && it.height == 480 }) {
                            Log.d(TAG, "Selected standard 640x480 fallback")
                            return Pair(640, 480)
                        }
                        
                        // First supported size
                        Log.d(TAG, "No 480p-like sizes found, selecting first supported: ${sizes[0].width}x${sizes[0].height}")
                        return Pair(sizes[0].width, sizes[0].height)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding best camera resolution", e)
        }
        return Pair(848, 480)
    }

    private fun startStreamingFlow() {
        val settings = settingsManager ?: return
        val streamer = udpStreamer ?: return
        val encoder = encoderManager ?: return

        isStreaming = true
        Log.d(TAG, "Starting FPV stream: ${settings.ipAddress}:${settings.port} Codec: ${settings.codec}")

        // 1. Resolve actual wide 480p camera supported size
        val (width, height) = getBest480pSize()
        activeWidth = width
        activeHeight = height

        // 2. Start UDP Streamer
        streamer.start(settings.ipAddress, settings.port)

        // 3. Start Video Encoder with actual resolved size
        encoder.start(
            codecType = settings.codec,
            fps = settings.fps,
            bitrateMbps = settings.bitrate,
            streamer = streamer,
            width = activeWidth,
            height = activeHeight
        ) { encoderSurface ->
            // 4. Start Camera2 capture when encoder surface is fully ready
            currentEncoderSurface = encoderSurface
            cameraController?.start(encoderSurface, currentPreviewSurface)
        }
    }

    /**
     * Dynamically updates the preview surface from MainActivity (e.g. when open/closed)
     * without breaking the live encoding and UDP streaming loop.
     */
    fun updatePreviewSurface(surface: Surface?) {
        currentPreviewSurface = surface
        val encoderSurface = currentEncoderSurface
        if (isStreaming && encoderSurface != null) {
            Log.d(TAG, "Updating UI preview surface dynamically: ${surface != null}")
            cameraController?.start(encoderSurface, surface)
        }
    }

    private fun stopStreamingFlow() {
        isStreaming = false
        cameraController?.stop()
        encoderManager?.stop()
        udpStreamer?.stop()
        currentEncoderSurface = null
        currentPreviewSurface = null
        Log.d(TAG, "FPV stream stopped completely")
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FPV:StreamingWakeLock").apply {
            acquire(10 * 60 * 1000L /*10 minutes fallback*/)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Safe release
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "FPV Video Transmitter Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val settings = settingsManager
        val infoText = if (settings != null) {
            "Streaming to ${settings.ipAddress}:${settings.port} (${settings.codec})"
        } else {
            "Streaming video to Ground Station"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FPV Air Unit Transmitter Active")
            .setContentText(infoText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        stopStreamingFlow()
        releaseWakeLock()
        activeInstance = null
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
