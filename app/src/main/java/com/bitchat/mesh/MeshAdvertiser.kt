package com.bitchat.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid

@SuppressLint("MissingPermission")
class MeshAdvertiser(context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var active = false
    private var listener: ((Result) -> Unit)? = null

    val isActive: Boolean get() = active

    fun start(nodeId: String, name: String, onResult: (Result) -> Unit) {
        stop()
        listener = onResult
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            onResult(Result.Failed("BLE advertising is not supported on this device"))
            return
        }
        leAdvertiser = advertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(
                ParcelUuid(MeshConstants.DISCOVERY_UUID),
                AdvertisePayload.encode(nodeId, name)
            )
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            onResult(Result.Failed("Missing permission to advertise"))
        } catch (e: Exception) {
            onResult(Result.Failed(e.message ?: "Failed to start advertising"))
        }
    }

    fun stop() {
        if (active) {
            try {
                leAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
        }
        active = false
        leAdvertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            active = true
            listener?.invoke(Result.Started)
        }

        override fun onStartFailure(errorCode: Int) {
            active = false
            listener?.invoke(Result.Failed(describeError(errorCode)))
        }
    }

    sealed class Result {
        data object Started : Result()
        data class Failed(val reason: String) : Result()
    }

    private fun describeError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement data too large"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising not supported"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal advertising error"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers active"
        else -> "Advertising failed with code $code"
    }
}
