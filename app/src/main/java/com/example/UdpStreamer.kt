package com.example

import android.os.Process
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class UdpStreamer {
    companion object {
        private const val TAG = "UdpStreamer"
        private const val MAX_UDP_PAYLOAD_SIZE = 1300 // Standard MTU is 1500, keeping it safe
        private const val SOCKET_SEND_BUFFER_SIZE = 1024 * 1024 // 1 MB buffer
    }

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 5600
    private var isStreaming = false

    // Queue and executor for high-performance non-blocking network transmission
    private var sendExecutor: ThreadPoolExecutor? = null
    private val sendQueue = LinkedBlockingQueue<ByteArray>(20) // Limit queue to prevent latency buildup

    // Pre-allocated packet and buffer for reuse
    private var datagramPacket: DatagramPacket? = null
    private val chunkBuffer = ByteArray(MAX_UDP_PAYLOAD_SIZE)

    fun start(ip: String, port: Int) {
        if (isStreaming) stop()
        isStreaming = true
        targetPort = port

        // Run networking setup on executor
        sendExecutor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { runnable ->
            Thread(runnable, "UdpSendThread").apply {
                priority = Thread.MAX_PRIORITY // Set high Java thread priority
            }
        }

        sendExecutor?.execute {
            try {
                // Set native thread priority
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                
                targetAddress = InetAddress.getByName(ip)
                val ds = DatagramSocket()
                ds.sendBufferSize = SOCKET_SEND_BUFFER_SIZE
                socket = ds
                
                // Initialize reusable packet pointing to chunkBuffer
                datagramPacket = DatagramPacket(chunkBuffer, MAX_UDP_PAYLOAD_SIZE, targetAddress, targetPort)
                Log.d(TAG, "UDP Streamer initialized for $ip:$port")

                // Start transmission loop
                transmissionLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing UDP socket", e)
            }
        }
    }

    fun stop() {
        isStreaming = false
        sendQueue.clear()
        sendExecutor?.shutdownNow()
        sendExecutor = null
        try {
            socket?.close()
        } catch (e: Exception) {
            // Safe close
        }
        socket = null
        targetAddress = null
        Log.d(TAG, "UDP Streamer stopped")
    }

    /**
     * Enqueues a full encoded frame to be chunked and streamed immediately.
     */
    fun sendFrame(data: ByteArray) {
        if (!isStreaming) return
        
        // If the send queue is full, drop the oldest frame to preserve lowest latency
        if (sendQueue.size >= 15) {
            sendQueue.poll() // Drop oldest
        }
        sendQueue.offer(data)
    }

    /**
     * Internal loop executing on the high-priority UDP thread.
     * Consumes frames from the queue, chunks them, and transmits them instantly.
     */
    private fun transmissionLoop() {
        while (isStreaming) {
            try {
                val frameData = sendQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val ds = socket ?: continue
                val packet = datagramPacket ?: continue

                var offset = 0
                val totalLength = frameData.size

                while (offset < totalLength && isStreaming) {
                    val chunkSize = kotlin.math.min(MAX_UDP_PAYLOAD_SIZE, totalLength - offset)
                    
                    // Copy into pre-allocated chunkBuffer to avoid object allocation in loop
                    System.arraycopy(frameData, offset, chunkBuffer, 0, chunkSize)
                    
                    // Configure reused packet details
                    packet.setData(chunkBuffer, 0, chunkSize)
                    ds.send(packet)
                    
                    offset += chunkSize
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP packet", e)
            }
        }
    }
}
