package com.example

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface

class EncoderManager {
    companion object {
        private const val TAG = "EncoderManager"
        private const val WIDTH = 848
        private const val HEIGHT = 480
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var handlerThread: HandlerThread? = null
    private var codecCallback: MediaCodec.Callback? = null
    private var isRunning = false

    // Configuration buffers for SPS/PPS/VPS prepend
    private var codecConfigBuffer: ByteArray? = null

    fun start(
        codecType: String,
        fps: Int,
        bitrateMbps: Int,
        streamer: UdpStreamer,
        width: Int,
        height: Int,
        onSurfaceCreated: (Surface) -> Unit
    ) {
        if (isRunning) stop()
        isRunning = true

        val mimeType = if (codecType.equals("H265", ignoreCase = true)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

        handlerThread = HandlerThread("EncoderCallbackThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        val handler = Handler(handlerThread!!.looper)

        handler.post {
            // Set native thread priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                val encoder = MediaCodec.createEncoderByType(mimeType)
                val format = MediaFormat.createVideoFormat(mimeType, width, height)

                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1000 * 1000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s keyframes for fast recovery
                
                // Low latency and high priority parameters
                format.setInteger("latency", 0)
                format.setInteger("priority", 0) // Real-time priority

                // Set callback on our background thread handler
                codecCallback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        // Using COLOR_FormatSurface, so input is handled via Surface automatically
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        if (!isRunning) return

                        val buffer = codec.getOutputBuffer(index) ?: return
                        val bufferSize = info.size

                        if (bufferSize > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + bufferSize)

                            val outData = ByteArray(bufferSize)
                            buffer.get(outData)

                            // Check buffer flags
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // Save configuration frame (SPS / PPS / VPS)
                                codecConfigBuffer = outData
                                Log.d(TAG, "Codec configuration headers saved (size: ${outData.size})")
                                
                                // Send config immediately at stream start
                                streamer.sendFrame(outData)
                            } else {
                                val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                if (isKeyFrame && codecConfigBuffer != null) {
                                    // Prepend saved SPS/PPS/VPS configuration before the keyframe
                                    val config = codecConfigBuffer!!
                                    val combined = ByteArray(config.size + outData.size)
                                    System.arraycopy(config, 0, combined, 0, config.size)
                                    System.arraycopy(outData, 0, combined, config.size, outData.size)
                                    
                                    streamer.sendFrame(combined)
                                } else {
                                    // Normal frame (P-frame / B-frame)
                                    streamer.sendFrame(outData)
                                }
                            }
                        }

                        codec.releaseOutputBuffer(index, false)
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(TAG, "MediaCodec Error", e)
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        Log.d(TAG, "MediaCodec Output Format Changed: $format")
                    }
                }

                encoder.setCallback(codecCallback, handler)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                
                val surface = encoder.createInputSurface()
                inputSurface = surface
                mediaCodec = encoder

                encoder.start()
                Log.d(TAG, "MediaCodec encoder started successfully with format: $format")

                // Return input surface to camera controller
                onSurfaceCreated(surface)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize and start encoder", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        mediaCodec?.let { encoder ->
            try {
                encoder.stop()
                encoder.release()
            } catch (e: Exception) {
                // Safe cleanup
            }
        }
        mediaCodec = null
        inputSurface?.release()
        inputSurface = null
        handlerThread?.quitSafely()
        handlerThread = null
        codecCallback = null
        codecConfigBuffer = null
        Log.d(TAG, "Encoder stopped")
    }
}
