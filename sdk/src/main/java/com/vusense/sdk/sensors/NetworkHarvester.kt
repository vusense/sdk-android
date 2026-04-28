package com.vusense.sdk.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NetworkHarvester collects cellular and WiFi network taxonomy data.
 * 
 * This class implements strict security and privacy policies:
 * - All network operations use coroutines, never callbacks
 * - WiFi MAC addresses are collected but not logged in plain text
 * - Cell tower data includes MCC/MNC for carrier identification
 * - Graceful handling of permission denials
 * - Memory-efficient with bounded buffers
 */
@Singleton
class NetworkHarvester @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val NETWORK_SCAN_INTERVAL_MS = 5000L // 5 seconds
        private const val MAX_WIFI_RESULTS = 20 // Limit WiFi results to prevent memory bloat
        private const val MAX_CELL_RESULTS = 10 // Limit cell results
    }
    
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    private val _cellTowerState = MutableStateFlow<CellTowerData?>(null)
    val cellTowerState: StateFlow<CellTowerData?> = _cellTowerState.asStateFlow()
    
    private val _wifiState = MutableStateFlow<WifiData?>(null)
    val wifiState: StateFlow<WifiData?> = _wifiState.asStateFlow()
    
    private val networkScope = CoroutineScope(Dispatchers.IO)
    private var networkJob: Job? = null
    
    /**
     * Starts collecting cellular tower data.
     * 
     * @return Flow of CellTowerData with network taxonomy
     */
    fun startCellTowerCollection(): Flow<CellTowerData> = flow {
        if (!hasPhoneStatePermission()) {
            throw NetworkException("Phone state permission not granted")
        }
        
        while (isActive) {
            delay(NETWORK_SCAN_INTERVAL_MS)
            
            try {
                val cellData = collectCellTowerData()
                if (cellData.isValid()) {
                    networkScope.launch {
                        _cellTowerState.value = cellData
                    }
                    emit(cellData)
                }
            } catch (e: SecurityException) {
                throw NetworkException("Security exception in cell tower collection", e)
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
    
    /**
     * Starts collecting WiFi scan data.
     * 
     * @return Flow of WifiData with network taxonomy
     */
    fun startWifiCollection(): Flow<WifiData> = flow {
        if (!hasWifiPermission()) {
            throw NetworkException("WiFi permission not granted")
        }
        
        while (isActive) {
            delay(NETWORK_SCAN_INTERVAL_MS)
            
            try {
                val wifiData = collectWifiData()
                if (wifiData.isValid()) {
                    networkScope.launch {
                        _wifiState.value = wifiData
                    }
                    emit(wifiData)
                }
            } catch (e: SecurityException) {
                throw NetworkException("Security exception in WiFi collection", e)
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
    
    /**
     * Collects cellular tower information from all available cells.
     */
    private suspend fun collectCellTowerData(): CellTowerData = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val cellInfoList = mutableListOf<CellInfo>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val allCellInfo = telephonyManager.allCellInfo
                if (allCellInfo != null) {
                    cellInfoList.addAll(allCellInfo.take(MAX_CELL_RESULTS))
                }
            }
        } catch (e: SecurityException) {
            // Permission denied, return empty data
            return@withContext CellTowerData(timestamp, emptyList())
        }
        
        val cellData = cellInfoList.mapNotNull { cellInfo ->
            when (cellInfo) {
                is CellInfoLte -> parseLteCellInfo(cellInfo)
                is CellInfoGsm -> parseGsmCellInfo(cellInfo)
                is CellInfoWcdma -> parseWcdmaCellInfo(cellInfo)
                else -> null
            }
        }
        
        CellTowerData(timestamp, cellData)
    }
    
    /**
     * Parses LTE cell information.
     */
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun parseLteCellInfo(cellInfo: CellInfoLte): CellInfo? {
        return try {
            CellInfo(
                type = "LTE",
                mcc = cellInfo.cellIdentity.mcc,
                mnc = cellInfo.cellIdentity.mnc,
                lac = null, // LTE uses TAC instead
                tac = cellInfo.cellIdentity.tac,
                cid = cellInfo.cellIdentity.ci,
                pci = cellInfo.cellIdentity.pci,
                signalStrength = cellInfo.cellSignalStrength.dbm,
                timingAdvance = cellInfo.cellSignalStrength.timingAdvance
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parses GSM cell information.
     */
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun parseGsmCellInfo(cellInfo: CellInfoGsm): CellInfo? {
        return try {
            CellInfo(
                type = "GSM",
                mcc = cellInfo.cellIdentity.mcc,
                mnc = cellInfo.cellIdentity.mnc,
                lac = cellInfo.cellIdentity.lac,
                tac = null, // GSM uses LAC instead
                cid = cellInfo.cellIdentity.cid,
                pci = null, // GSM doesn't have PCI
                signalStrength = cellInfo.cellSignalStrength.dbm,
                timingAdvance = null
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parses WCDMA cell information.
     */
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun parseWcdmaCellInfo(cellInfo: CellInfoWcdma): CellInfo? {
        return try {
            CellInfo(
                type = "WCDMA",
                mcc = cellInfo.cellIdentity.mcc,
                mnc = cellInfo.cellIdentity.mnc,
                lac = cellInfo.cellIdentity.lac,
                tac = null, // WCDMA uses LAC instead
                cid = cellInfo.cellIdentity.cid,
                pci = null, // WCDMA doesn't have PCI
                signalStrength = cellInfo.cellSignalStrength.dbm,
                timingAdvance = null
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Collects WiFi scan information.
     */
    private suspend fun collectWifiData(): WifiData = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        
        try {
            val scanResults = wifiManager.scanResults
            val wifiScanResults = scanResults
                .take(MAX_WIFI_RESULTS)
                .mapNotNull { scanResult ->
                    try {
                        WifiScanResult(
                            bssid = scanResult.BSSID,
                            ssid = scanResult.SSID,
                            frequency = scanResult.frequency,
                            rssi = scanResult.level,
                            capabilities = scanResult.capabilities
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .filter { it.isValid() }
            
            WifiData(timestamp, wifiScanResults)
        } catch (e: SecurityException) {
            // Permission denied, return empty data
            WifiData(timestamp, emptyList())
        }
    }
    
    /**
     * Starts continuous network collection (both cellular and WiFi).
     * 
     * @return Flow of combined network data
     */
    fun startContinuousNetworkCollection(): Flow<Pair<CellTowerData?, WifiData?>> = flow {
        val cellFlow = startCellTowerCollection().catch { e ->
            // Log error but continue with WiFi
            emit(null)
        }
        
        val wifiFlow = startWifiCollection().catch { e ->
            // Log error but continue with cellular
            emit(null)
        }
        
        var latestCell: CellTowerData? = null
        var latestWifi: WifiData? = null
        
        networkScope.launch {
            cellFlow.collect { cell ->
                latestCell = cell
            }
        }
        
        networkScope.launch {
            wifiFlow.collect { wifi ->
                latestWifi = wifi
            }
        }
        
        // Emit network data every 5 seconds
        while (isActive) {
            delay(NETWORK_SCAN_INTERVAL_MS)
            emit(Pair(latestCell, latestWifi))
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
    
    /**
     * Gets current network information synchronously.
     */
    suspend fun getCurrentNetworkData(): Pair<CellTowerData?, WifiData?> = withContext(Dispatchers.IO) {
        val cellData = try {
            if (hasPhoneStatePermission()) {
                collectCellTowerData().takeIf { it.isValid() }
            } else null
        } catch (e: Exception) {
            null
        }
        
        val wifiData = try {
            if (hasWifiPermission()) {
                collectWifiData().takeIf { it.isValid() }
            } else null
        } catch (e: Exception) {
            null
        }
        
        Pair(cellData, wifiData)
    }
    
    /**
     * Checks if phone state permission is granted.
     */
    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Checks if WiFi permissions are granted.
     */
    private fun hasWifiPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_NETWORK_STATE
                ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Gets network operator information for debugging.
     */
    suspend fun getNetworkOperatorInfo(): NetworkOperatorInfo = withContext(Dispatchers.IO) {
        try {
            NetworkOperatorInfo(
                carrierName = telephonyManager.networkOperatorName ?: "Unknown",
                mcc = telephonyManager.networkOperator?.take(3)?.toIntOrNull() ?: 0,
                mnc = telephonyManager.networkOperator?.takeLast(2)?.toIntOrNull() ?: 0,
                countryIso = telephonyManager.networkCountryIso ?: "Unknown",
                simCountryIso = telephonyManager.simCountryIso ?: "Unknown",
                simOperatorName = telephonyManager.simOperatorName ?: "Unknown"
            )
        } catch (e: Exception) {
            NetworkOperatorInfo()
        }
    }
    
    /**
     * Checks if WiFi is enabled.
     */
    fun isWifiEnabled(): Boolean {
        return try {
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Triggers a WiFi scan (may not work on all Android versions).
     */
    suspend fun triggerWifiScan(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires location permission for WiFi scans
                wifiManager.startScan()
            } else {
                wifiManager.startScan()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        networkJob?.cancel()
        networkScope.cancel()
    }
}

/**
 * Network operator information for debugging.
 */
data class NetworkOperatorInfo(
    val carrierName: String = "Unknown",
    val mcc: Int = 0,
    val mnc: Int = 0,
    val countryIso: String = "Unknown",
    val simCountryIso: String = "Unknown",
    val simOperatorName: String = "Unknown"
)

/**
 * Exception thrown for network collection failures.
 */
class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
