package com.tripper.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
private val TextPrimary = Color(0xFFE8E8E8)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TripperAppScreen(
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    bleService: TripperBleService?,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("tripper_prefs", 0)

    var selectedModelIndex by remember {
        mutableIntStateOf(prefs.getInt("model_index", 0))
    }
    var selectedColorIndex by remember {
        mutableIntStateOf(prefs.getInt("color_index", 0))
    }

    val models = royalEnfieldModels
    val currentModel = models.getOrNull(selectedModelIndex) ?: models.first()
    val currentColor = currentModel.colors.getOrNull(selectedColorIndex) ?: currentModel.colors.first()

    var showEditSheet by remember { mutableStateOf(false) }

    // Live data
    val navData by com.tripper.app.live.LiveDataRepository.nav.collectAsState()
    val musicData by com.tripper.app.live.LiveDataRepository.music.collectAsState()
    val callData by com.tripper.app.live.LiveDataRepository.call.collectAsState()

    // Start call tracker
    LaunchedEffect(Unit) {
        com.tripper.app.live.CallTracker(context).start()
    }

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

            // ── Hero Bike Image ──
            BikeHeroImage(
                color = currentColor.color,
                modelName = currentModel.name,
                colorName = currentColor.name,
                onLongPress = { showEditSheet = true },
            )
            Spacer(Modifier.height(24.dp))

            // ── Connection ──
            ConnectionSection(
                bleService = bleService,
                onScan = onScan,
                onDisconnect = onDisconnect,
            )
            Spacer(Modifier.height(24.dp))

            // ── Tripper Live Preview ──
            Text("Tripper Display Preview", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            TripperPreview(navData = navData, musicData = musicData, callData = callData)
            Spacer(Modifier.height(24.dp))

            // ── Settings ──
            OutlinedButton(
                onClick = onOpenSettings,
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Notification Access", fontSize = 13.sp) }
        }
    }

    // ── Edit Bottom Sheet ──
    if (showEditSheet) {
        EditBikeSheet(
            models = models,
            selectedModelIndex = selectedModelIndex,
            selectedColorIndex = selectedColorIndex,
            onSelectModel = { i ->
                selectedModelIndex = i
                selectedColorIndex = 0
                prefs.edit().putInt("model_index", i).putInt("color_index", 0).apply()
            },
            onSelectColor = { i ->
                selectedColorIndex = i
                prefs.edit().putInt("color_index", i).apply()
            },
            onDismiss = { showEditSheet = false },
        )
    }
}

// ─── Hero Bike Image ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BikeHeroImage(
    color: Color,
    modelName: String,
    colorName: String,
    onLongPress: () -> Unit,
) {
    var showTooltip by remember { mutableStateOf(false) }

    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            kotlinx.coroutines.delay(1200)
            showTooltip = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    showTooltip = true
                    onLongPress()
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Bike drawing with glass-like card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, SurfaceLight),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.default_bike),
                    contentDescription = modelName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )

                if (showTooltip) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Accent.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Edit", color = Background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Model name
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(modelName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
        }
        Text(colorName, color = Accent, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Long press to change model & color",
            color = TextSecondary.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
    }
}

// ─── Bike Drawing ──────────────────────────────────────────────────────────

@Composable
fun BikeDrawing(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2 + 12f
        val paint = color
        val line = Stroke(width = 3f)

        // ── Wheels ──
        drawCircle(paint, radius = 36f, center = Offset(cx - 110f, cy), style = line)
        drawCircle(paint.copy(alpha = 0.25f), radius = 36f, center = Offset(cx - 110f, cy))
        // Spokes hint
        drawCircle(paint.copy(alpha = 0.15f), radius = 10f, center = Offset(cx - 110f, cy))

        drawCircle(paint, radius = 36f, center = Offset(cx + 110f, cy), style = line)
        drawCircle(paint.copy(alpha = 0.25f), radius = 36f, center = Offset(cx + 110f, cy))
        drawCircle(paint.copy(alpha = 0.15f), radius = 10f, center = Offset(cx + 110f, cy))

        // ── Main body (cruiser profile) ──
        val body = Path().apply {
            // Seat
            moveTo(cx - 70f, cy - 42f)
            cubicTo(cx - 50f, cy - 52f, cx - 10f, cy - 52f, cx + 10f, cy - 42f)
            // Tail cowl
            cubicTo(cx + 30f, cy - 36f, cx + 45f, cy - 25f, cx + 50f, cy - 15f)
            lineTo(cx + 52f, cy - 5f)
            // Bottom of tail
            lineTo(cx + 45f, cy - 3f)
            // Under seat
            lineTo(cx + 10f, cy - 3f)
            // Engine area bottom
            cubicTo(cx - 5f, cy + 5f, cx - 20f, cy + 5f, cx - 35f, cy)
            // Down tube to front
            lineTo(cx - 55f, cy + 5f)
            lineTo(cx - 75f, cy - 5f)
            // Front fork
            lineTo(cx - 75f, cy - 52f)
            // Headlight area
            lineTo(cx - 78f, cy - 60f)
            lineTo(cx - 85f, cy - 62f)
            // Handlebar
            lineTo(cx - 95f, cy - 68f)
            lineTo(cx - 100f, cy - 62f)
            // Front fork down
            lineTo(cx - 90f, cy - 50f)
            lineTo(cx - 90f, cy - 15f)
            // Back to engine
            lineTo(cx - 75f, cy - 5f)
            // Up to tank
            cubicTo(cx - 60f, cy - 20f, cx - 45f, cy - 30f, cx - 35f, cy - 42f)
            // Tank top
            cubicTo(cx - 25f, cy - 50f, cx - 15f, cy - 52f, cx - 5f, cy - 50f)
            close()
        }
        drawPath(body, color = paint)
        drawPath(body, color = paint.copy(alpha = 0.4f), style = line)

        // ── Front fender ──
        val frontFender = Path().apply {
            moveTo(cx + 72f, cy - 32f)
            cubicTo(cx + 82f, cy - 28f, cx + 92f, cy - 28f, cx + 100f, cy - 32f)
            lineTo(cx + 102f, cy - 28f)
            cubicTo(cx + 92f, cy - 23f, cx + 82f, cy - 23f, cx + 72f, cy - 28f)
            close()
        }
        drawPath(frontFender, color = paint)

        // ── Rear fender ──
        val rearFender = Path().apply {
            moveTo(cx - 75f, cy - 28f)
            cubicTo(cx - 88f, cy - 24f, cx - 98f, cy - 24f, cx - 108f, cy - 28f)
            lineTo(cx - 110f, cy - 24f)
            cubicTo(cx - 98f, cy - 19f, cx - 88f, cy - 19f, cx - 75f, cy - 24f)
            close()
        }
        drawPath(rearFender, color = paint)

        // ── Exhaust ──
        val exhaust = Path().apply {
            moveTo(cx - 30f, cy + 5f)
            lineTo(cx - 50f, cy + 18f)
            lineTo(cx - 55f, cy + 22f)
            lineTo(cx - 65f, cy + 22f)
            cubicTo(cx - 75f, cy + 22f, cx - 85f, cy + 18f, cx - 90f, cy + 15f)
        }
        drawPath(exhaust, color = paint.copy(alpha = 0.6f), style = Stroke(width = 2.5f))

        // ── Engine highlight ──
        drawCircle(paint.copy(alpha = 0.12f), radius = 18f, center = Offset(cx - 35f, cy - 3f))
        drawCircle(paint.copy(alpha = 0.08f), radius = 14f, center = Offset(cx - 35f, cy - 3f))

        // ── Headlight ──
        drawCircle(paint, radius = 6f, center = Offset(cx - 86f, cy - 60f), style = line)
        drawCircle(paint.copy(alpha = 0.3f), radius = 4f, center = Offset(cx - 86f, cy - 60f))
    }
}

// ─── Edit Bike Bottom Sheet ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBikeSheet(
    models: List<BikeModel>,
    selectedModelIndex: Int,
    selectedColorIndex: Int,
    onSelectModel: (Int) -> Unit,
    onSelectColor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Accent.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Select Model", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            val listState = rememberLazyListState()
            LaunchedEffect(selectedModelIndex) {
                listState.animateScrollToItem(selectedModelIndex)
            }
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(models) { i, model ->
                    val selected = i == selectedModelIndex
                    Card(
                        onClick = { onSelectModel(i) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Accent.copy(alpha = 0.15f) else SurfaceLight
                        ),
                        border = if (selected) BorderStroke(1.5.dp, Accent) else null,
                        modifier = Modifier.width(130.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                model.name,
                                color = if (selected) Accent else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                model.colors.take(5).forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(c.color)
                                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                    )
                                }
                                if (model.colors.size > 5) {
                                    Text("+${model.colors.size - 5}", color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            val currentModel = models.getOrNull(selectedModelIndex) ?: models.first()
            Text(
                "Color: ${currentModel.colors.getOrNull(selectedColorIndex)?.name ?: ""}",
                color = Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(currentModel.colors) { i, option ->
                    val selected = i == selectedColorIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(option.color)
                                .then(
                                    if (selected) Modifier.border(3.dp, Accent, CircleShape)
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                                )
                                .clickable { onSelectColor(i) }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            option.name,
                            color = if (selected) Accent else TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Done", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
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

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("tripper_prefs", 0)
    val lastDevice = remember { prefs.getString("last_device", null) }

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
                    val hint = if (lastDevice != null) "Previously: $lastDevice" else "Tap to connect to Tripper"
                    Text(hint, color = TextSecondary, fontSize = 13.sp)
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
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(
                            if (lastDevice != null) "Reconnect" else "Scan & Connect",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
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
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { Text("Disconnect", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─── Premium Tripper Live Preview ─────────────────────────────────────────

private val glassWhite = Color.White.copy(alpha = 0.06f)
private val glassBorder = Color.White.copy(alpha = 0.08f)

@Composable
fun TripperPreview(
    navData: com.tripper.app.live.NavInfo,
    musicData: com.tripper.app.live.MusicInfo,
    callData: com.tripper.app.live.CallInfo,
) {
    var mode by remember { mutableIntStateOf(0) }

    val hasNav = navData.turnLabel.isNotBlank()
    val hasMusic = musicData.isPlaying
    val hasCall = callData.isRinging

    // Auto-switch to the mode with live data, fallback 0
    LaunchedEffect(hasNav, hasMusic, hasCall) {
        when {
            hasCall -> mode = 2
            hasMusic -> mode = 1
            hasNav -> mode = 0
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, glassBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Premium selector pill
            Box(
                modifier = Modifier
                    .background(glassWhite, RoundedCornerShape(100.dp))
                    .border(0.5.dp, glassBorder, RoundedCornerShape(100.dp))
                    .padding(4.dp),
            ) {
                Row {
                    listOf("NAV", "MUSIC", "CALL").forEachIndexed { i, label ->
                        val selected = i == mode
                        val hasDot = when (i) {
                            0 -> hasNav; 1 -> hasMusic; 2 -> hasCall
                            else -> false
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (selected) Accent else Color.Transparent)
                                .clickable { mode = i }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    label,
                                    color = if (selected) Background else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    letterSpacing = 2.sp,
                                )
                                if (hasDot) {
                                    Spacer(Modifier.width(4.dp))
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(Accent))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Premium round display
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(Color(0xFF111111), Color(0xFF050505)),
                            radius = 220f,
                        )
                    )
                    .border(1.5.dp, glassBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Outer glow ring
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(CircleShape)
                        .border(1.dp, Accent.copy(alpha = 0.08f), CircleShape)
                )
                // Inner subtle ring
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .clip(CircleShape)
                        .border(0.5.dp, Accent.copy(alpha = 0.04f), CircleShape)
                )

                // Content with crossfade
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                    },
                    label = "preview",
                ) { m ->
                    when (m) {
                        0 -> NavPreviewContent(navData)
                        1 -> MusicPreviewContent(musicData)
                        2 -> CallPreviewContent(callData)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // Status text
            val status = when (mode) {
                0 -> if (hasNav) navData.turnLabel.replaceFirstChar { it.uppercase() } else "No navigation"
                1 -> if (hasMusic) "${musicData.title}" else "No music"
                2 -> if (hasCall) "Incoming call" else "No calls"
                else -> ""
            }
            Text(
                status.ifBlank { "Standby" },
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun NavPreviewContent(data: com.tripper.app.live.NavInfo) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            data.turnIcon,
            fontSize = 44.sp,
        )
        Spacer(Modifier.height(4.dp))
        if (data.distance.isNotBlank()) {
            Text(
                data.distance,
                color = Accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            data.turnLabel.replaceFirstChar { it.uppercase() },
            color = TextPrimary.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
        if (data.eta.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "ETA ${data.eta}",
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun MusicPreviewContent(data: com.tripper.app.live.MusicInfo) {
    if (!data.isPlaying || data.title.isBlank()) {
        Text("♫", fontSize = 40.sp)
        Spacer(Modifier.height(4.dp))
        Text("No music", color = TextSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("♫", fontSize = 32.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            data.title,
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            data.artist,
            color = TextPrimary.copy(alpha = 0.5f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(12, 18, 8, 22, 14, 20, 10).forEach { h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (h > 15) Accent else Accent.copy(alpha = 0.4f))
                )
            }
        }
    }
}

@Composable
private fun CallPreviewContent(data: com.tripper.app.live.CallInfo) {
    if (!data.isRinging) {
        Text("📞", fontSize = 40.sp)
        Spacer(Modifier.height(4.dp))
        Text("No calls", color = TextSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📞", fontSize = 34.sp)
        Spacer(Modifier.height(6.dp))
        Text("Incoming", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            data.callerName,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Decline
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF331111))
                    .border(1.dp, Color(0xFFFF4444).copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color(0xFFFF4444), fontSize = 16.sp) }
            // Accept
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF113311))
                    .border(1.dp, Accent.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = Accent, fontSize = 16.sp) }
        }
    }
}

// ─── Collapsible Section ───────────────────────────────────────────────────

@Composable
fun CollapsibleSection(emoji: String, title: String, description: String) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        ) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "▲" else "▼",
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, SurfaceLight),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(description, color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }
    }
}
