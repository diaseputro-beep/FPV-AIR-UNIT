package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

class CameraController(private val context: Context) {
    companion object {
        private const val TAG = "CameraController"
        private const val WIDTH = 848
        private const val HEIGHT = 480
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    fun start(encoderSurface: Surface, previewSurface: Surface?) {
        stop()

        handlerThread = HandlerThread("CameraBackground").apply {
            start()
        }
        backgroundHandler = Handler(handlerThread!!.looper)

        backgroundHandler?.post {
            openCameraAndStartSession(encoderSurface, previewSurface)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndStartSession(encoderSurface: Surface, previewSurface: Surface?) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findBackCameraId(cameraManager) ?: return

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, encoderSurface, previewSurface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    stop()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun findBackCameraId(cameraManager: CameraManager): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // Fallback to first camera if no back camera found
            if (cameraManager.cameraIdList.isNotEmpty()) {
                return cameraManager.cameraIdList[0]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera ID", e)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(
        camera: CameraDevice,
        encoderSurface: Surface,
        previewSurface: Surface?
    ) {
        try {
            val targets = mutableListOf<Surface>()
            targets.add(encoderSurface)
            if (previewSurface != null) {
                targets.add(previewSurface)
            }

            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session

                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(encoderSurface)
                        if (previewSurface != null) {
                            builder.addTarget(previewSurface)
                        }

                        // Optimize camera parameters for low-latency FPV streaming
                        builder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        builder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                        )
                        
                        // Enforce high frame rate parameters if supported
                        builder.set(
                            CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO
                        )

                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.d(TAG, "Camera capture session configured and streaming started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera repeating capture request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera capture session", e)
        }
    }

    fun stop() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            // Safe close
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            // Safe close
        }
        cameraDevice = null

        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
        Log.d(TAG, "CameraController stopped")
    }
}
