package com.bitchat.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

class MeshScanner(context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner = adapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())

    private var scanning = false
    private var enabled = false
    private var useExtended = true
    private var failures = 0
    private var resultListener: ((ScanResult) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null

    val isScanning: Boolean get() = scanning

    fun start(onResult: (ScanResult) -> Unit, onError: (String) -> Unit) {
        stop()
        enabled = true
        failures = 0
        resultListener = onResult
        errorListener = onError
        startScanInternal()
    }

    fun stop() {
        enabled = false
        handler.removeCallbacks(restartRunnable)
        if (scanning) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
        }
        scanning = false
    }

    private fun startScanInternal() {
        val leScanner = scanner ?: run {
            errorListener?.invoke("BLE scanning is not supported on this device")
            return
        }
        if (scanning) return
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        if (useExtended) {
            builder.setLegacy(false)
            builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshConstants.DISCOVERY_UUID))
            .build()
        try {
            leScanner.startScan(listOf(filter), builder.build(), scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            errorListener?.invoke("Missing permission to scan")
        } catch (e: Exception) {
            errorListener?.invoke(e.message ?: "Failed to start scanning")
        }
    }

    private val restartRunnable = Runnable {
        if (enabled) startScanInternal()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            resultListener?.invoke(result)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (!enabled) return
            failures++
            if (failures > 2) useExtended = false
            errorListener?.invoke("Scan failed with code $errorCode, retrying")
            handler.postDelayed(restartRunnable, 4_000)
        }
    }
}
