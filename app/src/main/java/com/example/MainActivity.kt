package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startStreamingService()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required for FPV Video Streaming!",
                Toast.LENGTH_LONG
            ).apply { show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request camera permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startStreamingService()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    FpvMainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onApplySettings = { restartStreamingService() }
                    )
                }
            }
        }
    }

    private fun startStreamingService() {
        try {
            val intent = Intent(this, StreamingService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartStreamingService() {
        try {
            val intent = Intent(this, StreamingService::class.java)
            stopService(intent)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Settings Applied & Stream Restarted!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-bind texture view surface if service is already running
        StreamingService.activeInstance?.let {
            // Surface texture will re-trigger surface availability on view creation
        }
    }

    override fun onPause() {
        // Disassociate preview surface when Activity is not visible
        StreamingService.activeInstance?.updatePreviewSurface(null)
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpvMainScreen(
    modifier: Modifier = Modifier,
    onApplySettings: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }

    // Settings State
    var ipVal by remember { mutableStateOf(settingsManager.ipAddress) }
    var portVal by remember { mutableStateOf(settingsManager.port.toString()) }
    var codecVal by remember { mutableStateOf(settingsManager.codec) }
    var fpsVal by remember { mutableStateOf(settingsManager.fps) }
    var bitrateVal by remember { mutableStateOf(settingsManager.bitrate) }

    // Streaming state (fetched reactively from service)
    var isServiceActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isServiceActive = StreamingService.activeInstance?.isStreaming == true
            delay(1000)
        }
    }

    // Dynamic system time logic for simulated clock in header
    var timeText by remember { mutableStateOf("10:42 AM") }
    LaunchedEffect(Unit) {
        while (true) {
            val cal = java.util.Calendar.getInstance()
            val hour = cal.get(java.util.Calendar.HOUR)
            val min = cal.get(java.util.Calendar.MINUTE)
            val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
            timeText = String.format("%d:%02d %s", if (hour == 0) 12 else hour, min, amPm)
            delay(10000)
        }
    }

    // Blinking dot animation for active transmission
    val infiniteTransition = rememberInfiniteTransition(label = "blinking")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)) // bg-neutral-950
    ) {
        // 1. Simulated Status Bar & Premium Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Indicator (Pulse)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isServiceActive) Color(0xFF22D3EE).copy(alpha = dotAlpha)
                            else Color(0xFF737373)
                        )
                )
                Text(
                    text = if (isServiceActive) "BROADCASTING" else "STANDBY",
                    color = if (isServiceActive) Color(0xFF22D3EE) else Color(0xFF737373),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            // Tabular Time Text
            Text(
                text = timeText,
                color = Color(0xFF737373),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }

        // App Title Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        ) {
            Text(
                text = "AirUnit FPV",
                color = Color(0xFFF5F5F5), // text-neutral-100
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "OPENHD COMPATIBLE • V1.0.4",
                color = Color(0xFFA3A3A3), // text-neutral-400
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Split Layout: Preview Frame + Configuration Menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Side: Live Video Area + Integrated Stats
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Live View Box (Locked 4:3)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFF262626), RoundedCornerShape(24.dp))
                        .background(Color(0xFF171717)),
                    contentAlignment = Alignment.Center
                ) {
                    // Embedded Camera2 TextureView
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int
                                    ) {
                                        val surface = Surface(texture)
                                        StreamingService.activeInstance?.updatePreviewSurface(surface)
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int
                                    ) {}

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        StreamingService.activeInstance?.updatePreviewSurface(null)
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("camera_preview")
                    )

                    // Viewfinder Guideline Overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .border(1.dp, Color(0xFFF5F5F5).copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    )

                    // Dynamic streaming resolution
                    val activeWidth = StreamingService.activeInstance?.activeWidth ?: 848
                    val activeHeight = StreamingService.activeInstance?.activeHeight ?: 480
                    val isServiceActive = StreamingService.activeInstance?.isStreaming == true

                    // Top Left Viewfinder overlay text
                    Text(
                        text = if (isServiceActive) "${activeWidth}x${activeHeight} @ ${fpsVal}FPS" else "848x480 READY",
                        color = Color(0xFF22D3EE).copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(28.dp)
                    )

                    // Bottom Right Viewfinder overlay text
                    Text(
                        text = "UDP: $ipVal",
                        color = Color(0xFF22D3EE).copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(28.dp)
                    )

                    // Foreground Indicator Watermark when not active
                    if (!isServiceActive) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Ready",
                                tint = Color(0xFF262626),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "HARDWARE ENCODER READY",
                                color = Color(0xFF737373),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Telemetry Stats Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF262626), RoundedCornerShape(16.dp))
                        .background(Color(0xFF0A0A0A)),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Latency Card
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "LATENCY",
                            color = Color(0xFF737373),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (isServiceActive) "14" else "--",
                                color = Color(0xFFF5F5F5),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "ms",
                                color = Color(0xFF737373),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(Color(0xFF262626))
                    )

                    // Bitrate Card
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "BITRATE",
                            color = Color(0xFF737373),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (isServiceActive) "$bitrateVal.0" else "--",
                                color = Color(0xFF22D3EE),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "Mbps",
                                color = Color(0xFF737373),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }
            }

            // Right Side: Settings Card
            Column(
                modifier = Modifier
                    .weight(1.0f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(24.dp))
                    .background(Color(0xFF171717)) // bg-neutral-900/50
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Header inside card
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Config Icon",
                            tint = Color(0xFF22D3EE),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TRANSMITTER CONFIG",
                            color = Color(0xFFF5F5F5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Destination Address Input
                    OutlinedTextField(
                        value = ipVal,
                        onValueChange = { ipVal = it },
                        label = { Text("Ground Station IP", color = Color(0xFF737373), fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF22D3EE),
                            unfocusedBorderColor = Color(0xFF262626),
                            focusedLabelColor = Color(0xFF22D3EE),
                            cursorColor = Color(0xFF22D3EE),
                            focusedContainerColor = Color(0xFF0A0A0A),
                            unfocusedContainerColor = Color(0xFF0A0A0A)
                        ),
                        textStyle = TextStyle(color = Color(0xFFF5F5F5), fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ip_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    // Destination Port Input
                    OutlinedTextField(
                        value = portVal,
                        onValueChange = { portVal = it },
                        label = { Text("UDP Target Port", color = Color(0xFF737373), fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF22D3EE),
                            unfocusedBorderColor = Color(0xFF262626),
                            focusedLabelColor = Color(0xFF22D3EE),
                            cursorColor = Color(0xFF22D3EE),
                            focusedContainerColor = Color(0xFF0A0A0A),
                            unfocusedContainerColor = Color(0xFF0A0A0A)
                        ),
                        textStyle = TextStyle(color = Color(0xFFF5F5F5), fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("port_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    // Codec Selector (H.264 / H.265)
                    Column {
                        Text(
                            text = "VIDEO CODEC TYPE",
                            color = Color(0xFF737373),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("H264", "H265").forEach { item ->
                                val isSelected = codecVal == item
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF22D3EE) else Color(0xFF0A0A0A))
                                        .clickable { codecVal = item }
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF22D3EE) else Color(0xFF262626),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (item == "H264") "H.264 (AVC)" else "H.265 (HEVC)",
                                        color = if (isSelected) Color.Black else Color(0xFFA3A3A3),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    // FPS Framerate Selector
                    Column {
                        Text(
                            text = "TARGET FRAME RATE",
                            color = Color(0xFF737373),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(30, 60).forEach { item ->
                                val isSelected = fpsVal == item
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF22D3EE) else Color(0xFF0A0A0A))
                                        .clickable { fpsVal = item }
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF22D3EE) else Color(0xFF262626),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$item FPS",
                                        color = if (isSelected) Color.Black else Color(0xFFA3A3A3),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    // Bitrate Selector
                    Column {
                        Text(
                            text = "BITRATE TRANSMISSION (Mbps)",
                            color = Color(0xFF737373),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(2, 4, 8, 12, 16).forEach { item ->
                                val isSelected = bitrateVal == item
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF22D3EE) else Color(0xFF0A0A0A))
                                        .clickable { bitrateVal = item }
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF22D3EE) else Color(0xFF262626),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${item}M",
                                        color = if (isSelected) Color.Black else Color(0xFFA3A3A3),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Apply / Start Action Button
                Button(
                    onClick = {
                        val portInt = portVal.toIntOrNull() ?: SettingsManager.DEFAULT_PORT
                        settingsManager.ipAddress = ipVal
                        settingsManager.port = portInt
                        settingsManager.codec = codecVal
                        settingsManager.fps = fpsVal
                        settingsManager.bitrate = bitrateVal

                        onApplySettings()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("apply_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF22D3EE),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Save and apply",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SAVE & TRANSMIT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

