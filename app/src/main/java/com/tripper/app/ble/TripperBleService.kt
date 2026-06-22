package com.tripper.app.ble

import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.os.Binder
import android.os.IBinder
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tripper.app.MainActivity
import com.tripper.app.ble.PacketBuilder.Icons
import com.tripper.app.live.LiveDataRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class TripperBleService : Service() {

    companion object {
        const val SERVICE_UUID = "01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0"
        const val CHAR_UUID = "01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0"
        const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        const val CHANNEL_ID = "tripper_ble"
        const val NOTIFICATION_ID = 1001
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Scanning : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val device: BluetoothDevice) : ConnectionState()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    private val sendQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isSending = false
    private var sendJob: Job? = null
    private var lastSentCmd: Byte = -1
    private var heartbeatJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _bondState = MutableStateFlow(BluetoothDevice.BOND_NONE)
    val bondState: StateFlow<Int> = _bondState

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent.action) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                android.util.Log.i("TripperBle", "Bond state changed: $prevState -> $bondState (${device?.address})")
                _bondState.value = bondState
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name
            val address = device.address
            android.util.Log.i("TripperBle", "onScanResult: '${name ?: "(unnamed)"}' [$address] rssi=${result.rssi}")

            if (name == null) {
                val hasUuid = result.scanRecord?.serviceUuids?.any {
                    it.uuid.toString().uppercase().contains("01FF0100")
                } == true
                if (hasUuid) {
                    android.util.Log.i("TripperBle", ">>> UUID MATCH on unnamed device [$address]")
                    bluetoothLeScanner?.stopScan(this)
                    connect(device)
                }
                return
            }

            // Also match known Tripper MAC addresses
            val knownTripperMacs = listOf("00:60:37:AB:26:82")

            if (name.equals("tripperfilter", ignoreCase = true) ||
                name.contains("tripper", ignoreCase = true) ||
                name.contains("re tripper", ignoreCase = true) ||
                name.contains("re_disp", ignoreCase = true) ||
                knownTripperMacs.any { it.equals(address, ignoreCase = true) }
            ) {
                android.util.Log.i("TripperBle", ">>> MATCH: $name [$address]")
                bluetoothLeScanner?.stopScan(this)
                connect(device)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            android.util.Log.i("TripperBle", "onBatchScanResults: ${results.size} devices")
            for (result in results) {
                onScanResult(0, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e("TripperBle", "Scan failed errorCode=$errorCode")
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            android.util.Log.i("TripperBle", "Connection state: newState=$newState status=$status (${gatt.device.name})")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.Connected(gatt.device)
                _deviceName.value = gatt.device.name ?: "Tripper"
                // Request larger MTU for faster transfers
                gatt.requestMtu(512)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                android.util.Log.e("TripperBle", "Disconnected status=$status")
                _connectionState.value = ConnectionState.Disconnected
                cleanup()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            android.util.Log.i("TripperBle", "MTU changed: mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            android.util.Log.i("TripperBle", "Services discovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Log ALL services and their characteristics
            for (svc in gatt.services) {
                android.util.Log.i("TripperBle", "  Service: ${svc.uuid}")
                for (ch in svc.characteristics) {
                    android.util.Log.i("TripperBle", "    Char: ${ch.uuid} props=${ch.properties}")
                }
            }

            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            if (service == null) {
                android.util.Log.e("TripperBle", "Service $SERVICE_UUID not found on device!")
                return
            }
            val char = service.getCharacteristic(UUID.fromString(CHAR_UUID))
            if (char == null) {
                android.util.Log.e("TripperBle", "Characteristic $CHAR_UUID not found in service $SERVICE_UUID!")
                return
            }
            characteristic = char
            android.util.Log.i("TripperBle", "Found characteristic ${char.uuid}")

            // Try to enable notifications if the characteristic supports it
            val supportsNotify = (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val supportsIndicate = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            if (supportsNotify || supportsIndicate) {
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(UUID.fromString(CCCD_UUID))
                if (cccd != null) {
                    android.util.Log.i("TripperBle", "Writing CCCD to enable notifications...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
            } else {
                android.util.Log.i("TripperBle", "Characteristic props=${char.properties} — no NOTIFY/INDICATE support, skipping CCCD")
            }

            // Start heartbeat immediately — it sends nav every 2.5s to keep connection alive
            android.util.Log.i("TripperBle", "Service found, starting heartbeat...")
            startHeartbeat()

            // Time sync removed — it switches Tripper to clock mode, overriding nav mode
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val cmdStr = "%02x".format(lastSentCmd)
            android.util.Log.i("TripperBle", "onCharacteristicWrite: cmd=0x$cmdStr status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dequeueAndSendNext()
            } else {
                android.util.Log.w("TripperBle", "Write failed, clearing queue. status=$status")
                sendQueue.clear()
                isSending = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("TripperBle", "Service onCreate")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        android.util.Log.i("TripperBle", "Service created: adapter=${bluetoothAdapter?.address} scanner=$bluetoothLeScanner")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("TripperBle", "Service onStartCommand flags=$flags startId=$startId")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tripper Bridge")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        startForeground(NOTIFICATION_ID, notification)
        android.util.Log.i("TripperBle", "Foreground notification shown")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        android.util.Log.i("TripperBle", "Service onBind")
        return LocalBinder()
    }

    inner class LocalBinder : Binder() {
        fun getService(): TripperBleService = this@TripperBleService
    }

    fun scan() {
        if (!hasBlePermissions()) {
            android.util.Log.e("TripperBle", "Missing BLE permissions")
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            android.util.Log.e("TripperBle", "Bluetooth is not enabled")
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        // Re-acquire scanner each scan to ensure fresh instance
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            android.util.Log.e("TripperBle", "bluetoothLeScanner is null - BT might be off")
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        bluetoothLeScanner = scanner

        android.util.Log.i("TripperBle", "BT adapter: ${bluetoothAdapter?.address} isEnabled=${bluetoothAdapter?.isEnabled} scanner=$scanner")
        _connectionState.value = ConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        android.util.Log.i("TripperBle", "Starting BLE scan... (SDK=${Build.VERSION.SDK_INT})")
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            } catch (e: Exception) {
                android.util.Log.e("TripperBle", "startScan(emptyList,settings,callback) failed: $e")
                try {
                    scanner.startScan(scanCallback)
                } catch (e2: Exception) {
                    android.util.Log.e("TripperBle", "startScan(callback) also failed: $e2")
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }

        // No automatic timeout - scan continuously until device found or user cancels
        scope.launch {
            delay(60000)
            if (_connectionState.value is ConnectionState.Scanning) {
                android.util.Log.i("TripperBle", "Scan still running after 60s, continuing...")
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        android.util.Log.i("TripperBle", "Connecting to ${device.name} (${device.address})...")
        _connectionState.value = ConnectionState.Connecting
        // Close any previous GATT before starting new connection
        try { gatt?.close() } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_AUTO)
    }

    fun disconnect() {
        bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanup() {
        stopHeartbeat()
        gatt?.close()
        gatt = null
        characteristic = null
        sendQueue.clear()
        isSending = false
    }

    fun sendPacket(data: ByteArray) {
        sendQueue.offer(data)
        if (!isSending) {
            sendQueuePackets()
        }
    }

    private fun sendQueuePackets() {
        isSending = true
        val packet = sendQueue.poll() ?: run {
            isSending = false
            return
        }
        writeCharacteristic(packet)
    }

    private fun dequeueAndSendNext() {
        val packet = sendQueue.poll()
        if (packet != null) {
            writeCharacteristic(packet)
        } else {
            isSending = false
        }
    }

    private fun writeCharacteristic(data: ByteArray) {
        val char = characteristic ?: return

        if (data.isNotEmpty()) lastSentCmd = data[0]

        val charProps = char.properties
        val useDefault = (charProps and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        if (useDefault) {
            android.util.Log.i("TripperBle", "Using WRITE_TYPE_DEFAULT (props support WRITE)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(char)
            }
        } else {
            android.util.Log.i("TripperBle", "Using WRITE_TYPE_NO_RESPONSE (props=${charProps})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                char.value = data
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(char)
            }
        }

        // Fallback timeout in case write callback never fires
        sendJob?.cancel()
        sendJob = scope.launch {
            delay(3000)
            if (isSending) {
                dequeueAndSendNext()
            }
        }
    }

    private fun handleIncoming(data: ByteArray) {
        android.util.Log.i("TripperBle", "Incoming data: ${data.joinToString { "%02x".format(it) }}")
        // Reserved for parsing incoming Tripper data (e.g. button presses or status)
    }

    private var lastMode = -1
    private var lastMusicTitle = ""
    private var lastMusicArtist = ""

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastMode = -1
        lastMusicTitle = ""
        lastMusicArtist = ""
        heartbeatJob = scope.launch {
            while (isActive && gatt != null && _connectionState.value is ConnectionState.Connected) {
                val nav = LiveDataRepository.nav.value
                val music = LiveDataRepository.music.value
                val call = LiveDataRepository.call.value

                val mode = when { call.isRinging -> 3; music.isPlaying -> 2; else -> 1 }

                when {
                    call.isRinging -> {
                        if (mode != lastMode) sendPacket(PacketBuilder.buildStateChange(3))
                        lastMode = 3
                        sendPacket(PacketBuilder.buildNavData(
                            primaryIcon = Icons.MOBILE_DATA, primaryDistance = 0,
                            secondaryIcon = -1, secondaryDistance = 0, mode = 0
                        ))
                    }
                    music.isPlaying -> {
                        if (mode != lastMode) sendPacket(PacketBuilder.buildStateChange(2))
                        lastMode = 2
                        if (music.title != lastMusicTitle && music.title.isNotBlank()) {
                            lastMusicTitle = music.title
                            sendPacket(PacketBuilder.buildMusicTitle(music.title))
                        }
                        if (music.artist != lastMusicArtist && music.artist.isNotBlank()) {
                            lastMusicArtist = music.artist
                            sendPacket(PacketBuilder.buildMusicArtist(music.artist))
                        }
                    }
                    else -> {
                        lastMode = 1
                        val etaFormat = if (nav.etaFormat != 0) nav.etaFormat
                            else if (nav.etaHours > 0 || nav.etaMinutes > 0 || nav.etaSeconds > 0) 10
                            else 0
                        sendPacket(PacketBuilder.buildNavData(
                            primaryIcon = nav.iconId.coerceAtLeast(Icons.STRAIGHT),
                            primaryDistance = nav.distanceMeters,
                            secondaryIcon = PacketBuilder.Icons.DESTINATION,
                            secondaryDistance = 0,
                            etaHours = nav.etaHours,
                            etaMinutes = nav.etaMinutes,
                            etaSeconds = nav.etaSeconds,
                            etaFormat = etaFormat,
                            mode = 0
                        ))
                    }
                }
                delay(2500)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun hasBlePermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
