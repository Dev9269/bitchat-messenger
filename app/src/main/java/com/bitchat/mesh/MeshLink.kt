package com.bitchat.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MeshLink(
    private val context: Context,
    val nodeId: String,
    private val deviceMac: String,
    private val onPacket: (MeshLink, ByteArray) -> Unit,
    private val onClosed: (MeshLink) -> Unit,
) : BluetoothGattCallback() {

    enum class State { CONNECTING, READY, CLOSED }

    var state: State = State.CONNECTING
        private set

    val ready = CompletableDeferred<Unit>()

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var closed = false
    private val writeQueue = ArrayDeque<Pair<ByteArray, (Boolean) -> Unit>>()
    private var writing = false
    private var idleJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun connect() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: run {
            failClosed()
            return
        }
        try {
            gatt = adapter.getRemoteDevice(deviceMac)
                .connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            failClosed()
        } catch (_: Exception) {
            failClosed()
        }
    }

    fun write(bytes: ByteArray, onComplete: (Boolean) -> Unit) {
        if (state != State.READY || closed) {
            onComplete(false)
            return
        }
        writeQueue.addLast(bytes to onComplete)
        pump()
    }

    suspend fun writeAwait(bytes: ByteArray): Boolean = suspendCancellableCoroutine { cont ->
        if (state != State.READY || closed) {
            cont.resume(false)
        } else {
            write(bytes) { ok ->
                if (cont.isActive) cont.resume(ok)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        state = State.CLOSED
        idleJob?.cancel()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        if (!ready.isCompleted) {
            ready.completeExceptionally(RuntimeException("link closed"))
        }
        onClosed(this)
    }

    private fun failClosed() {
        if (closed) return
        close()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                gatt?.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                failClosed()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failClosed()
            return
        }
        try {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            gatt?.requestMtu(517)
        } catch (_: Exception) {
            failClosed()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return
        val g = gatt ?: return
        val service = g.getService(GattConstants.MESH_SERVICE_UUID) ?: run {
            failClosed()
            return
        }
        txCharacteristic = service.getCharacteristic(GattConstants.CHAR_TX_UUID)
        val rx = service.getCharacteristic(GattConstants.CHAR_RX_UUID) ?: run {
            failClosed()
            return
        }
        val cccd = rx.getDescriptor(GattConstants.CLIENT_CHARACTERISTIC_CONFIG) ?: run {
            failClosed()
            return
        }
        g.setCharacteristicNotification(rx, true)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (_: Exception) {
            failClosed()
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS &&
            descriptor?.uuid == GattConstants.CLIENT_CHARACTERISTIC_CONFIG
        ) {
            state = State.READY
            if (!ready.isCompleted) ready.complete(Unit)
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
        touch()
        characteristic?.value?.let { value ->
            if (value.isNotEmpty()) onPacket(this, value)
        }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
        touch()
        writing = false
        val entry = writeQueue.removeFirstOrNull()
        entry?.second?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        pump()
    }

    private fun pump() {
        if (writing || closed) return
        val entry = writeQueue.firstOrNull() ?: return
        val g = gatt ?: return
        val tx = txCharacteristic ?: return
        writing = true
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeCharacteristic(tx, entry.first, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (result != BluetoothStatusCodes.SUCCESS) {
                    writing = false
                    writeQueue.removeFirst().second(false)
                    pump()
                }
            } else {
                @Suppress("DEPRECATION")
                tx.value = entry.first
                @Suppress("DEPRECATION")
                g.writeCharacteristic(tx)
            }
        } catch (_: Exception) {
            writing = false
            writeQueue.removeFirstOrNull()?.second?.invoke(false)
        }
    }

    private fun touch() {
        if (closed) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            if (isActive) close()
        }
    }

    companion object {
        private const val IDLE_CLOSE_MS = 10_000L
    }
}
