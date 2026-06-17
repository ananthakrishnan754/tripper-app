package com.tripper.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Binder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tripper.app.ble.TripperBleService

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
            MaterialTheme {
                MainScreen(
                    onScan = { bleService?.scan() },
                    onDisconnect = { bleService?.disconnect() },
                    onOpenSettings = { openNotificationAccessSettings() },
                    bleService = bleService
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    bleService: TripperBleService?
) {
    val connectionState = bleService?.connectionState?.collectAsStateWithLifecycle()?.value
        ?: TripperBleService.ConnectionState.Disconnected
    val deviceName = bleService?.deviceName?.collectAsStateWithLifecycle()?.value ?: ""

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tripper Bridge") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Connection", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            val (statusText, statusColor) = when (connectionState) {
                is TripperBleService.ConnectionState.Disconnected -> "Disconnected" to MaterialTheme.colorScheme.error
                is TripperBleService.ConnectionState.Scanning -> "Scanning..." to MaterialTheme.colorScheme.tertiary
                is TripperBleService.ConnectionState.Connecting -> "Connecting..." to MaterialTheme.colorScheme.tertiary
                is TripperBleService.ConnectionState.Connected -> "Connected to $deviceName" to MaterialTheme.colorScheme.primary
            }

            Text(statusText, color = statusColor, fontSize = 16.sp)

            when (connectionState) {
                is TripperBleService.ConnectionState.Disconnected -> {
                    Button(onClick = onScan, enabled = bleService != null) {
                        Text("Scan & Connect")
                    }
                }
                is TripperBleService.ConnectionState.Scanning -> {
                    CircularProgressIndicator()
                    Text("Looking for Tripper...")
                }
                is TripperBleService.ConnectionState.Connecting -> {
                    CircularProgressIndicator()
                    Text("Connecting...")
                }
                is TripperBleService.ConnectionState.Connected -> {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Disconnect") }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Google Maps Integration", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Grant notification access below to intercept Google Maps navigation data.", fontSize = 14.sp)
            OutlinedButton(onClick = onOpenSettings) { Text("Open Notification Access") }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("How it works", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("1. Connect to Tripper via BLE", fontSize = 13.sp)
                    Text("2. Enable Notification Access for this app", fontSize = 13.sp)
                    Text("3. Navigate with Google Maps as usual", fontSize = 13.sp)
                    Text("4. Turn data is relayed to your Tripper display", fontSize = 13.sp)
                }
            }
        }
    }
}
