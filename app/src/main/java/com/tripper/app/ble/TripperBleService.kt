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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tripper.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class TripperBleService : Service() {

    companion object {
        const val SERVICE_UUID = "01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0"
        const val CHAR_UUID = "01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0"
        const val CCCD_UUID = "00002901-0000-1000-8000-00805f9b34fb"
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

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != null && device.name.uppercase().contains("TRIPPER")) {
                bluetoothLeScanner?.stopScan(this)
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.Connected(gatt.device)
                _deviceName.value = gatt.device.name ?: "Tripper"
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.Disconnected
                cleanup()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(UUID.fromString(SERVICE_UUID))
            val char = service?.getCharacteristic(UUID.fromString(CHAR_UUID)) ?: return
            characteristic = char

            // Enable notifications
            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(UUID.fromString(CCCD_UUID))
            cccd?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(it)
                }
            }

            // Send PIN command to start handshake
            sendPacket(PacketBuilder.buildShowPin(paired = false))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Check if this was a PIN command and send time if so
                val value = characteristic.value
                if (value?.getOrNull(0) == PacketBuilder.CMD_SHOW_PIN) {
                    scope.launch { sendTimeSync() }
                }
                dequeueAndSendNext()
            } else {
                // Write failed, retry or clear queue
                sendQueue.clear()
                isSending = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_STICKY
    }

    inner class LocalBinder : Binder() {
        fun getService(): TripperBleService = this@TripperBleService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    fun scan() {
        if (!hasBlePermissions()) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        if (bluetoothLeScanner == null) return

        _connectionState.value = ConnectionState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)

        // Timeout after 15 seconds
        scope.launch {
            delay(15000)
            if (_connectionState.value is ConnectionState.Scanning) {
                bluetoothLeScanner?.stopScan(scanCallback)
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        cleanup()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun cleanup() {
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

        // Use WRITE_TYPE_DEFAULT so onCharacteristicWrite fires reliably
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
        when (data[0]) {
            0x20.toByte() -> {
                // PIN auth response: byte[1] == 1 means success
                if (data.size > 1) {
                    if (data[1] == 0x01.toByte()) {
                        sendTimeSync()
                    }
                    // PIN response also often triggers sending the time
                    sendTimeSync()
                }
            }
        }
    }

    private fun sendTimeSync() {
        val now = Calendar.getInstance()
        val packet = PacketBuilder.buildTimeSync(
            hours = now.get(Calendar.HOUR_OF_DAY),
            minutes = now.get(Calendar.MINUTE),
            is24HourFormat = true
        )
        sendPacket(packet)
    }

    private fun hasBlePermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
