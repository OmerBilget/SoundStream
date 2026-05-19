package com.example.relay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

/**
 * The main entry point for the SoundStream application.
 * Handles the UI, service lifecycle management, and network state monitoring.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure edge-to-edge display with transparent system bars
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        
        // Ensure the navigation bar is truly transparent on modern Android versions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        
        setContent {
            RelayTheme {
                RelayApp(
                    onStart = { port ->
                        // Launch the foreground audio service
                        val intent = Intent(this, AudioServiceOpus::class.java).apply {
                            putExtra(AudioServiceOpus.EXTRA_PORT, port)
                        }
                        startForegroundService(intent)
                    },
                    onStop = {
                        // Stop the audio service
                        val intent = Intent(this, AudioServiceOpus::class.java)
                        stopService(intent)
                    },
                    onQuit = {
                        // Stop service and completely exit the app
                        val intent = Intent(this, AudioServiceOpus::class.java)
                        stopService(intent)
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }
}

/**
 * Defines the application's color scheme and typography.
 */
@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF082F49),
        primaryContainer = Color(0xFF0C4A6E),
        onPrimaryContainer = Color(0xFFE0F2FE),
        secondary = Color(0xFF818CF8),
        onSecondary = Color(0xFF1E1B4B),
        background = Color(0xFF0F172A),
        surface = Color(0xFF1E293B),
        surfaceVariant = Color(0xFF334155),
        onSurface = Color(0xFFF1F5F9),
        onSurfaceVariant = Color(0xFF94A3B8),
        outline = Color(0xFF475569)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

/**
 * The main UI layout of the application.
 */
@Composable
fun RelayApp(onStart: (Int) -> Unit, onStop: () -> Unit, onQuit: () -> Unit) {
    // State collection from the AudioService
    val serviceRunning by AudioServiceOpus.isRunning.collectAsState()
    val audioLevel by AudioServiceOpus.amplitude.collectAsState()
    val isReceiving by AudioServiceOpus.isReceiving.collectAsState()
    
    // UI state
    var showSettings by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("5005") }
    var useUsbByPreference by remember { mutableStateOf(true) }
    
    // Network interface state
    var usbIp by remember { mutableStateOf<String?>(null) }
    var wifiIp by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Discovery operation state
    var isDiscovering by remember { mutableStateOf(false) }
    var discoverySuccess by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf(false) }
    
    // Monitor network changes and update IP addresses reactively
    LaunchedEffect(Unit) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        
        fun updateIps() {
            usbIp = NetworkUtils.getUsbIpAddress()
            wifiIp = NetworkUtils.getWifiIpAddress()
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { updateIps() }
            override fun onLost(network: Network) { updateIps() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateIps() }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
            
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to register network callback", e)
        }
        
        // Initial update and periodic polling fallback
        while (true) {
            updateIps()
            kotlinx.coroutines.delay(3000)
        }
    }

    // Determine the active IP address based on user preference and availability
    val currentIp = if (useUsbByPreference) (usbIp ?: wifiIp ?: "No Connection") else (wifiIp ?: usbIp ?: "No Connection")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Header: App Logo and Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_soundstream_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SoundStream",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            text = "Audio Receiver",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Settings Button
                    Surface(
                        onClick = { showSettings = true },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.padding(10.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Exit App Button
                    Surface(
                        onClick = onQuit,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                    ) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = "Quit",
                            modifier = Modifier.padding(10.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (showSettings) {
                SettingsDialog(
                    portText = portText,
                    onPortChange = { portText = it },
                    serviceRunning = serviceRunning,
                    onDismiss = { showSettings = false }
                )
            }

            // Connection Information Card
            ConnectionStatusCard(
                isRunning = serviceRunning,
                isReceiving = isReceiving,
                ipAddress = currentIp,
                isUsbAvailable = usbIp != null,
                useUsbByPreference = useUsbByPreference,
                onPreferenceChange = { useUsbByPreference = it }
            )

            // Auto-Discovery Button
            Button(
                onClick = {
                    scope.launch {
                        isDiscovering = true
                        val port = portText.toIntOrNull() ?: 5005
                        val pcIp = DiscoveryClient.discoverAndHandshake(context, currentIp, port)
                        isDiscovering = false
                        if (pcIp != null) {
                            discoverySuccess = true
                            onStart(port)
                            kotlinx.coroutines.delay(3000)
                            discoverySuccess = false
                        } else {
                            discoveryError = true
                            kotlinx.coroutines.delay(2000)
                            discoveryError = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        discoverySuccess -> Color(0xFF22C55E)
                        discoveryError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                    },
                    contentColor = when {
                        discoverySuccess || discoveryError -> Color.White
                        else -> MaterialTheme.colorScheme.secondary
                    }
                ),
                border = if (!discoverySuccess && !discoveryError) 
                    BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)) 
                    else null,
                enabled = !isDiscovering && !discoverySuccess && !discoveryError
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (discoverySuccess) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connected to PC", fontWeight = FontWeight.Bold)
                } else if (discoveryError) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("No Computer Found", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Discover PC", fontWeight = FontWeight.Bold)
                }
            }

            // Central Play/Stop Visualizer Button
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                RingVisualizerButton(
                    isRunning = serviceRunning,
                    isReceiving = isReceiving,
                    audioLevel = audioLevel,
                    onClick = {
                        if (serviceRunning) {
                            onStop()
                        } else {
                            val p = portText.toIntOrNull() ?: 5005
                            onStart(if (p in 1024..65535) p else 5005)
                        }
                    }
                )
            }
        }
    }
}

/**
 * A custom animated button that visualizes audio amplitude with expanding ripples.
 */
@Composable
fun RingVisualizerButton(
    isRunning: Boolean,
    isReceiving: Boolean,
    audioLevel: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring_visualizer")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by animateFloatAsState(
        targetValue = if (isRunning && isReceiving) 1f + (audioLevel * 0.45f) else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "pulse"
    )

    // Ripple animations for active streaming
    val waveCount = 3
    val waves = (0 until waveCount).map { i ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, delayMillis = i * 600, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_$i"
        )
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .size(320.dp)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        // Draw ripples when receiving audio
        if (isRunning && isReceiving) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val baseRadius = 90.dp.toPx()
                val maxExtraRadius = 110.dp.toPx() * (0.6f + audioLevel)
                
                waves.forEach { waveProgress ->
                    val radius = baseRadius + (maxExtraRadius * waveProgress.value)
                    val alpha = (1f - waveProgress.value) * 0.8f * audioLevel
                    
                    drawCircle(
                        color = primaryColor.copy(alpha = alpha.coerceIn(0f, 1f)),
                        radius = radius,
                        style = Stroke(width = (2.5.dp.toPx() * (1.2f + audioLevel)))
                    )
                }
            }
        }

        // Main Button Surface
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 10.dp.toPx()
                val radius = (size.minDimension / 2) - strokeWidth
                
                // Static background track
                drawCircle(
                    color = primaryColor.copy(alpha = 0.1f),
                    radius = radius,
                    style = Stroke(width = strokeWidth)
                )

                if (isRunning) {
                    // Rotating progress ring
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(primaryColor, secondaryColor, primaryColor),
                            center = center
                        ),
                        startAngle = rotation,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Glow effect during playback
                    if (isReceiving) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(primaryColor.copy(alpha = 0.2f * audioLevel), Color.Transparent),
                                center = center,
                                radius = radius + (30.dp.toPx() * audioLevel)
                            ),
                            radius = radius + (15.dp.toPx() * audioLevel)
                        )
                    }
                }
            }

            // Icon Container
            Surface(
                modifier = Modifier
                    .size(130.dp)
                    .padding(8.dp),
                shape = CircleShape,
                color = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 12.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                    )
                    
                    // Indefinite progress if service is starting but not yet receiving
                    if (isRunning && !isReceiving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(110.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for configuring service settings.
 */
@Composable
fun SettingsDialog(
    portText: String,
    onPortChange: (String) -> Unit,
    serviceRunning: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = { Text("Configuration", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { if (it.all { char -> char.isDigit() }) onPortChange(it) },
                    label = { Text("Incoming UDP Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !serviceRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Rounded.SettingsEthernet, contentDescription = null) },
                    shape = RoundedCornerShape(16.dp)
                )

                HorizontalDivider(thickness = 0.5.dp)

                val context = androidx.compose.ui.platform.LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Usb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("USB Tethering", style = MaterialTheme.typography.labelLarge)
                    }

                    TextButton(onClick = {
                        val intent = Intent().apply { action = "android.settings.TETHER_SETTINGS" }
                        context.startActivity(intent)
                    }) {
                        Text("Open Settings")
                    }
                }
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/**
 * Card displaying connection status, current IP, and network interface toggles.
 */
@Composable
fun ConnectionStatusCard(
    isRunning: Boolean,
    isReceiving: Boolean,
    ipAddress: String,
    isUsbAvailable: Boolean,
    useUsbByPreference: Boolean,
    onPreferenceChange: (Boolean) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val statusColor by animateColorAsState(
        if (isReceiving) MaterialTheme.colorScheme.primary 
        else if (isRunning) MaterialTheme.colorScheme.secondary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        label = "statusColor"
    )
    
    val containerColor by animateColorAsState(
        if (isReceiving) statusColor.copy(alpha = pulseAlpha)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        label = "containerColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isRunning) CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(statusColor.copy(alpha = 0.4f), Color.Transparent))
        ) else null
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Label
            Surface(
                color = statusColor.copy(alpha = 0.1f),
                shape = CircleShape,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRunning) {
                        val dotAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(if (isReceiving) 600 else 1200),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dotAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                                .alpha(dotAlpha)
                        )
                    }

                    Text(
                        text = when {
                            isReceiving -> "LIVE STREAMING"
                            isRunning -> "READY TO RECEIVE"
                            else -> "OFFLINE"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Current IP Address
            Text(
                text = ipAddress,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Interface Selection Chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = !useUsbByPreference,
                    onClick = { onPreferenceChange(false) },
                    label = { Text("Wi-Fi") },
                    leadingIcon = {
                        Icon(
                            if (!useUsbByPreference) Icons.Rounded.Check else Icons.Rounded.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )

                FilterChip(
                    selected = useUsbByPreference,
                    onClick = { onPreferenceChange(true) },
                    enabled = isUsbAvailable,
                    label = { Text("USB") },
                    leadingIcon = {
                        Icon(
                            if (useUsbByPreference) Icons.Rounded.Check else Icons.Rounded.Usb,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }

            // Connection Progress Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                if (isRunning) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = statusColor,
                        trackColor = statusColor.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}
