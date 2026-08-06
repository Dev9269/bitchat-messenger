package com.bitchat.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID

object GattConstants {
    val MESH_SERVICE_UUID: UUID = UUID.fromString("f5a4c3e2-9b7d-4c8a-9d1e-2f3a4b5c6d7e")
    val CHAR_TX_UUID: UUID = UUID.fromString("f5a4c3e2-9b7d-4c8a-9d1e-2f3a4b5c6d8a")
    val CHAR_RX_UUID: UUID = UUID.fromString("f5a4c3e2-9b7d-4c8a-9d1e-2f3a4b5c6d8b")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

class MeshGattServer(
    private val context: Context,
    private val onPacket: (ByteArray, String) -> Unit,
) {

    private class Session(val device: BluetoothDevice) {
        var notifyEnabled = false
    }

    private var server: BluetoothGattServer? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val sessions = HashMap<String, Session>()

    fun open(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val gattServer = try {
            manager.openGattServer(context, callbacks)
        } catch (_: SecurityException) {
            return false
        } ?: return false
        server = gattServer

        val service = BluetoothGattService(
            GattConstants.MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val tx = BluetoothGattCharacteristic(
            GattConstants.CHAR_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val rx = BluetoothGattCharacteristic(
            GattConstants.CHAR_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        rx.addDescriptor(
            BluetoothGattDescriptor(
                GattConstants.CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        val added = gattServer.addService(service)
        rxCharacteristic = rx
        return added
    }

    fun close() {
        sessions.clear()
        try {
            server?.clearServices()
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        rxCharacteristic = null
    }

    fun sendTo(mac: String, bytes: ByteArray): Boolean {
        val srv = server ?: return false
        val session = sessions[mac] ?: return false
        if (!session.notifyEnabled) return false
        val rx = rxCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(session.device, rx, false, bytes) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(session.device, rx, false, bytes)
                true
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private val callbacks = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sessions[device.address] = Session(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sessions.remove(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (preparedWrite) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
            if (!preparedWrite && value.isNotEmpty()) {
                onPacket(value, device.address)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == GattConstants.CLIENT_CHARACTERISTIC_CONFIG && value.isNotEmpty()) {
                val enabled = (value[0].toInt() and 1) == 1
                sessions[device.address]?.notifyEnabled = enabled
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }
}
