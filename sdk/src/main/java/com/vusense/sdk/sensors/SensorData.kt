package com.vusense.sdk.sensors

/**
 * Data classes representing sensor readings for the ProofMode payload.
 * All coordinates are intentionally obfuscated in logging to prevent PII leakage.
 */

/**
 * GPS location data with accuracy and timestamp.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float, // meters
    val altitude: Double?,
    val bearing: Float?,
    val speed: Float?,
    val timestamp: Long, // epoch millis
    val provider: String // "gps", "network", "passive"
) {
    /**
     * Returns a safe string representation for logging (obfuscated coordinates).
     */
    fun toLogString(): String {
        return "Location(lat=${latitude.obfuscate()}, lon=${longitude.obfuscate()}, " +
                "accuracy=${accuracy}m, provider=$provider, ts=$timestamp)"
    }
    
    /**
     * Validates if location meets minimum quality thresholds.
     */
    fun isValid(): Boolean {
        return accuracy <= 100f && // Within 100 meters
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0 &&
                timestamp > 0
    }
}

/**
 * IMU sensor data for device orientation and movement.
 */
data class ImuData(
    val timestamp: Long, // epoch millis
    val accelerometer: SensorReading?, // m/s²
    val gyroscope: SensorReading?, // rad/s
    val magnetometer: SensorReading?, // μT
    val lightSensor: Float? // lux
) {
    /**
     * Validates if IMU data has sufficient readings.
     */
    fun isValid(): Boolean {
        return timestamp > 0 &&
                (accelerometer != null || gyroscope != null || magnetometer != null)
    }
}

/**
 * Individual sensor reading with 3-axis values.
 */
data class SensorReading(
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int, // SensorManager.SENSOR_STATUS_*
    val timestamp: Long // nanoseconds
) {
    /**
     * Validates if sensor reading is within reasonable bounds.
     */
    fun isValid(): Boolean {
        return timestamp > 0 &&
                !x.isNaN() && !y.isNaN() && !z.isNaN() &&
                !x.isInfinite() && !y.isInfinite() && !z.isInfinite()
    }
}

/**
 * Network tower information for cell taxonomy.
 */
data class CellTowerData(
    val timestamp: Long, // epoch millis
    val cellInfo: List<CellInfo>
) {
    /**
     * Validates if cell data is available.
     */
    fun isValid(): Boolean {
        return timestamp > 0 && cellInfo.isNotEmpty()
    }
}

/**
 * Individual cell tower information.
 */
data class CellInfo(
    val type: String, // "LTE", "GSM", "WCDMA", "CDMA"
    val mcc: Int, // Mobile Country Code
    val mnc: Int, // Mobile Network Code
    val lac: Int?, // Location Area Code (GSM/WCDMA)
    val tac: Int?, // Tracking Area Code (LTE)
    val cid: Int?, // Cell Identity
    val pci: Int?, // Physical Cell ID (LTE)
    val signalStrength: Int?, // dBm or ASU
    val timingAdvance: Int? // LTE timing advance
) {
    /**
     * Validates if cell info is complete.
     */
    fun isValid(): Boolean {
        return mcc in 0..999 && mnc in 0..999 &&
                type.isNotEmpty() &&
                (cid != null || pci != null)
    }
}

/**
 * WiFi network information for WiFi taxonomy.
 */
data class WifiData(
    val timestamp: Long, // epoch millis
    val scanResults: List<WifiScanResult>
) {
    /**
     * Validates if WiFi data is available.
     */
    fun isValid(): Boolean {
        return timestamp > 0 && scanResults.isNotEmpty()
    }
}

/**
 * Individual WiFi scan result.
 */
data class WifiScanResult(
    val bssid: String, // MAC address
    val ssid: String?, // Network name (may be null for hidden networks)
    val frequency: Int, // MHz
    val rssi: Int, // dBm signal strength
    val capabilities: String // Security capabilities
) {
    /**
     * Validates if WiFi scan result is complete.
     */
    fun isValid(): Boolean {
        return bssid.isNotEmpty() &&
                bssid.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")) &&
                frequency > 0 &&
                rssi <= 0 // RSSI should be negative or zero
    }
}

/**
 * Combined sensor data payload for ProofMode integration.
 */
data class SensorPayload(
    val location: LocationData?,
    val imu: ImuData?,
    val cellTower: CellTowerData?,
    val wifi: WifiData?,
    val timestamp: Long // epoch millis when payload was created
) {
    /**
     * Validates if payload has sufficient data for attestation.
     */
    fun isValid(): Boolean {
        return timestamp > 0 &&
                (location?.isValid() == true) &&
                (imu?.isValid() == true) &&
                (cellTower?.isValid() == true || wifi?.isValid() == true)
    }
    
    /**
     * Returns a safe string representation for logging.
     */
    fun toLogString(): String {
        return "SensorPayload(location=${location?.toLogString()}, " +
                "imu=${imu?.isValid()}, " +
                "cells=${cellTower?.cellInfo?.size}, " +
                "wifi=${wifi?.scanResults?.size}, " +
                "ts=$timestamp)"
    }
}

/**
 * Extension function to obfuscate coordinate values for logging.
 */
private fun Double.obfuscate(precision: Int = 2): String {
    return String.format("%.${precision}f", this).take(3) + "****"
}

/**
 * Extension function to obfuscate MAC addresses for logging.
 */
private fun String.obfuscateMac(): String {
    return if (length >= 8) take(8) + "****" else "****"
}
