package com.tripper.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tripper.app.ble.TripperBleService
import com.tripper.app.model.BikeColorOption
import com.tripper.app.model.BikeModel
import com.tripper.app.model.royalEnfieldModels

private val Accent = Color(0xFFC6FC03)
private val AccentDim = Color(0xFF8AB502)
private val Background = Color(0xFF000000)
private val Surface = Color(0xFF0A0A0A)
private val SurfaceLight = Color(0xFF1A1A1A)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF808080)

class MainActivity : ComponentActivity() {

    private val _bleServiceState = mutableStateOf<TripperBleService?>(null)
    private var bleService: TripperBleService?
        get() = _bleServiceState.value
        set(value) { _bleServiceState.value = value }
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bleService = (service as? TripperBleService.LocalBinder)?.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packet = intent?.getByteArrayExtra("packet") ?: return
            bleService?.sendPacket(packet)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestPermissions()

        val filter = IntentFilter("com.tripper.app.SEND_NAV")
        registerReceiver(navReceiver, filter, RECEIVER_NOT_EXPORTED)

        Intent(this, TripperBleService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Background,
                    surface = Surface,
                    onPrimary = Background,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary,
                )
            ) {
                TripperAppScreen(
                    onScan = { bleService?.scan() },
                    onDisconnect = { bleService?.disconnect() },
                    onOpenSettings = { openNotificationAccessSettings() },
                    bleService = bleService,
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(navReceiver)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, TripperBleService::class.java))
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TripperBleService.CHANNEL_ID,
            "Tripper BLE",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

// ─── Main Screen ───────────────────────────────────────────────────────────

@Composable
fun TripperAppScreen(
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    bleService: TripperBleService?,
) {
    val prefs = LocalContext.current.getSharedPreferences("tripper_prefs", 0)

    var selectedModelIndex by remember {
        mutableIntStateOf(prefs.getInt("model_index", 0))
    }
    var selectedColorIndex by remember {
        mutableIntStateOf(prefs.getInt("color_index", 0))
    }

    val models = royalEnfieldModels
    val currentModel = models.getOrNull(selectedModelIndex) ?: models.first()
    val currentColor = currentModel.colors.getOrNull(selectedColorIndex) ?: currentModel.colors.first()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Header ──
            Text("Tripper Bridge", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("for Royal Enfield", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))

            // ── Bike Selector ──
            BikeModelCarousel(
                models = models,
                selectedIndex = selectedModelIndex,
                onSelect = { i ->
                    selectedModelIndex = i
                    selectedColorIndex = 0
                    prefs.edit().putInt("model_index", i).putInt("color_index", 0).apply()
                }
            )
            Spacer(Modifier.height(16.dp))

            ColorSelector(
                colors = currentModel.colors,
                selectedIndex = selectedColorIndex,
                onSelect = { i ->
                    selectedColorIndex = i
                    prefs.edit().putInt("color_index", i).apply()
                }
            )
            Spacer(Modifier.height(20.dp))

            // ── Bike Silhouette ──
            BikeSilhouette(
                color = currentColor.color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                currentModel.name, color = TextPrimary, fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                currentColor.name, color = Accent, fontSize = 13.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(24.dp))

            // ── Connection ──
            ConnectionSection(
                bleService = bleService,
                onScan = onScan,
                onDisconnect = onDisconnect,
            )
            Spacer(Modifier.height(24.dp))

            // ── Divider ──
            HorizontalDivider(color = Accent.copy(alpha = 0.3f), thickness = 1.dp)
            Spacer(Modifier.height(16.dp))

            // ── Music ──
            MusicSection()
            Spacer(Modifier.height(16.dp))

            // ── Caller ──
            CallerSection()
            Spacer(Modifier.height(24.dp))

            // ── Settings ──
            OutlinedButton(
                onClick = onOpenSettings,
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
            ) { Text("Notification Access Settings", fontSize = 13.sp) }
        }
    }
}

// ─── Bike Model Carousel ───────────────────────────────────────────────────

@Composable
fun BikeModelCarousel(
    models: List<BikeModel>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(models) { i, model ->
            val selected = i == selectedIndex
            Card(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Accent.copy(alpha = 0.15f) else SurfaceLight
                ),
                border = if (selected) BorderStroke(1.5.dp, Accent) else null,
                modifier = Modifier
                    .width(120.dp)
                    .padding(vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        model.name,
                        color = if (selected) Accent else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        model.colors.take(4).forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(c.color)
                                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            )
                        }
                        if (model.colors.size > 4) {
                            Text("+", color = TextSecondary, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Color Selector ────────────────────────────────────────────────────────

@Composable
fun ColorSelector(
    colors: List<BikeColorOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            itemsIndexed(colors) { i, option ->
                val selected = i == selectedIndex
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(option.color)
                            .then(
                                if (selected) Modifier.border(2.5.dp, Accent, CircleShape)
                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                            )
                            .clickable { onSelect(i) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        option.name,
                        color = if (selected) Accent else TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ─── Bike Silhouette ───────────────────────────────────────────────────────

@Composable
fun BikeSilhouette(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2 + 20f

        val stroke = Stroke(width = 3.5f)
        val fillColor = color

        // Rear wheel
        drawCircle(color = fillColor, radius = 28f, center = Offset(cx - 90f, cy), style = stroke)
        drawCircle(color = fillColor.copy(alpha = 0.3f), radius = 28f, center = Offset(cx - 90f, cy))

        // Front wheel
        drawCircle(color = fillColor, radius = 28f, center = Offset(cx + 90f, cy), style = stroke)
        drawCircle(color = fillColor.copy(alpha = 0.3f), radius = 28f, center = Offset(cx + 90f, cy))

        val path = Path().apply {
            // Seat
            moveTo(cx - 50f, cy - 65f)
            cubicTo(cx - 30f, cy - 75f, cx + 10f, cy - 75f, cx + 30f, cy - 65f)

            // Tail
            lineTo(cx + 40f, cy - 60f)
            lineTo(cx + 42f, cy - 50f)
            lineTo(cx + 38f, cy - 45f)

            // Fuel tank
            lineTo(cx + 10f, cy - 45f)
            cubicTo(cx - 5f, cy - 55f, cx - 20f, cy - 55f, cx - 40f, cy - 50f)

            // Down tube
            lineTo(cx - 65f, cy - 15f)
            lineTo(cx - 65f, cy + 10f)

            // Engine / bottom
            lineTo(cx - 20f, cy + 15f)
            lineTo(cx + 10f, cy + 10f)
            lineTo(cx + 40f, cy + 5f)
            lineTo(cx + 60f, cy + 10f)

            // Front fork
            lineTo(cx + 60f, cy - 30f)
            lineTo(cx + 70f, cy - 55f)
            lineTo(cx + 75f, cy - 60f)

            // Handlebar
            lineTo(cx + 50f, cy - 70f)

            close()
        }

                    drawPath(path, color = fillColor)
        drawPath(path, color = fillColor.copy(alpha = 0.8f), style = stroke)

        // Fender lines
        drawLine(fillColor, Offset(cx - 115f, cy - 25f), Offset(cx - 65f, cy - 25f), strokeWidth = 2.5f)
        drawLine(fillColor, Offset(cx + 65f, cy - 25f), Offset(cx + 115f, cy - 25f), strokeWidth = 2.5f)
    }
}

// ─── Connection Section ────────────────────────────────────────────────────

@Composable
fun ConnectionSection(
    bleService: TripperBleService?,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connectionState = bleService?.connectionState?.collectAsStateWithLifecycle()?.value
        ?: TripperBleService.ConnectionState.Disconnected
    val deviceName = bleService?.deviceName?.collectAsStateWithLifecycle()?.value ?: ""

    // Remember last connected device in preferences
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("tripper_prefs", 0)
    val lastDevice by remember {
        derivedStateOf { prefs.getString("last_device", null) }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, SurfaceLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connection", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            when (connectionState) {
                is TripperBleService.ConnectionState.Disconnected -> {
                    val indicator = if (lastDevice != null) "Last: $lastDevice" else "Tap to connect"
                    Text(indicator, color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onScan,
                        enabled = bleService != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Background,
                            disabledContainerColor = AccentDim.copy(alpha = 0.3f),
                            disabledContentColor = TextSecondary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        if (lastDevice != null) Text("Reconnect", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        else Text("Scan & Connect", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
                is TripperBleService.ConnectionState.Scanning -> {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Scanning for Tripper...", color = TextSecondary, fontSize = 13.sp)
                }
                is TripperBleService.ConnectionState.Connecting -> {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Connecting...", color = TextSecondary, fontSize = 13.sp)
                }
                is TripperBleService.ConnectionState.Connected -> {
                    // Remember this device
                    LaunchedEffect(deviceName) {
                        if (deviceName.isNotBlank()) {
                            prefs.edit().putString("last_device", deviceName).apply()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Connected", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(deviceName.ifBlank { "Tripper" }, color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF331111),
                            contentColor = Color(0xFFFF4444),
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) { Text("Disconnect", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─── Music Section ─────────────────────────────────────────────────────────

@Composable
fun MusicSection() {
    val sectionVisible = remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { sectionVisible.value = !sectionVisible.value }
                .padding(vertical = 4.dp),
        ) {
            Text("🎵", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("Music", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (sectionVisible.value) "▲" else "▼",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        AnimatedVisibility(visible = sectionVisible.value) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, SurfaceLight),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No music playing", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Music info from Spotify, YouTube Music & others will appear here.",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ─── Caller Section ─────────────────────────────────────────────────────────

@Composable
fun CallerSection() {
    val sectionVisible = remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { sectionVisible.value = !sectionVisible.value }
                .padding(vertical = 4.dp),
        ) {
            Text("📞", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text("Caller ID", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (sectionVisible.value) "▲" else "▼",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
        AnimatedVisibility(visible = sectionVisible.value) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, SurfaceLight),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No incoming call", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Incoming caller information will be shown here and relayed to Tripper.",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
