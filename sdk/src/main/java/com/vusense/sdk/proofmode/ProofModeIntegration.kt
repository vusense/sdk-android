package com.vusense.sdk.proofmode

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import com.vusense.sdk.sensors.SensorPayload
import com.vusense.sdk.media.SecureMediaCapture
import com.vusense.sdk.sensors.LocationData
import com.vusense.sdk.sensors.ImuData
import com.vusense.sdk.sensors.CellTowerData
import com.vusense.sdk.sensors.WifiData
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ProofModeIntegration connects SDK data to the ProofMode PGP generator.
 * 
 * This class implements the ProofMode data format requirements:
 * - Converts sensor data to ProofMode JSON schema
 * - Generates PGP signatures using ProofMode library
 * - Creates compressed proof bundles for transmission
 * - Maintains compatibility with ProofMode verification tools
 */
@Singleton
class ProofModeIntegration @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val PROOFMODE_VERSION = "1.0"
        private const val SDK_VERSION = "1.0.0"
        private const val PROOF_TYPE = "vusense_attestation"
    }
    
    /**
     * Creates a ProofMode bundle from sensor and media data.
     * 
     * @param sensorPayload Complete sensor data
     * @param mediaCapture Captured media with hash
     * @param timestamp Capture timestamp
     * @return ProofModeBundle ready for PGP signing
     */
    suspend fun createProofBundle(
        sensorPayload: SensorPayload,
        mediaCapture: SecureMediaCapture,
        timestamp: Long
    ): ProofModeBundle = withContext(Dispatchers.IO) {
        
        // Create the main proof data structure
        val proofData = JSONObject().apply {
            put("version", PROOFMODE_VERSION)
            put("sdk_version", SDK_VERSION)
            put("type", PROOF_TYPE)
            put("timestamp", timestamp)
            put("created_at", System.currentTimeMillis())
            
            // Add sensor data
            put("sensors", createSensorDataJson(sensorPayload))
            
            // Add media data
            put("media", createMediaDataJson(mediaCapture))
            
            // Add device metadata
            put("device", createDeviceMetadataJson())
        }
        
        // Convert to bytes and compress
        val proofBytes = compressJson(proofData.toString())
        
        // Generate PGP signature (using ProofMode library)
        val pgpSignature = generatePgpSignature(proofBytes)
        
        ProofModeBundle(
            proofData = proofBytes,
            signature = pgpSignature,
            mediaHash = mediaCapture.getHash(),
            timestamp = timestamp,
            metadata = BundleMetadata(
                version = PROOFMODE_VERSION,
                type = PROOF_TYPE,
                uncompressedSize = proofData.toString().toByteArray().size,
                compressedSize = proofBytes.size
            )
        )
    }
    
    /**
     * Creates sensor data JSON in ProofMode format.
     */
    private fun createSensorDataJson(sensorPayload: SensorPayload): JSONObject {
        return JSONObject().apply {
            
            // Location data
            sensorPayload.location?.let { location ->
                put("location", JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    put("altitude", location.altitude)
                    put("bearing", location.bearing)
                    put("speed", location.speed)
                    put("timestamp", location.timestamp)
                    put("provider", location.provider)
                })
            }
            
            // IMU data
            sensorPayload.imu?.let { imu ->
                put("imu", JSONObject().apply {
                    put("timestamp", imu.timestamp)
                    
                    imu.accelerometer?.let { accel ->
                        put("accelerometer", JSONObject().apply {
                            put("x", accel.x)
                            put("y", accel.y)
                            put("z", accel.z)
                            put("accuracy", accel.accuracy)
                            put("timestamp", accel.timestamp)
                        })
                    }
                    
                    imu.gyroscope?.let { gyro ->
                        put("gyroscope", JSONObject().apply {
                            put("x", gyro.x)
                            put("y", gyro.y)
                            put("z", gyro.z)
                            put("accuracy", gyro.accuracy)
                            put("timestamp", gyro.timestamp)
                        })
                    }
                    
                    imu.magnetometer?.let { mag ->
                        put("magnetometer", JSONObject().apply {
                            put("x", mag.x)
                            put("y", mag.y)
                            put("z", mag.z)
                            put("accuracy", mag.accuracy)
                            put("timestamp", mag.timestamp)
                        })
                    }
                    
                    imu.lightSensor?.let { light ->
                        put("light_sensor", light)
                    }
                })
            }
            
            // Cellular data
            sensorPayload.cellTower?.let { cellData ->
                put("cellular", JSONObject().apply {
                    put("timestamp", cellData.timestamp)
                    put("cells", JSONArray().apply {
                        cellData.cellInfo.forEach { cell ->
                            put(JSONObject().apply {
                                put("type", cell.type)
                                put("mcc", cell.mcc)
                                put("mnc", cell.mnc)
                                cell.lac?.let { put("lac", it) }
                                cell.tac?.let { put("tac", it) }
                                cell.cid?.let { put("cid", it) }
                                cell.pci?.let { put("pci", it) }
                                cell.signalStrength?.let { put("signal_strength", it) }
                                cell.timingAdvance?.let { put("timing_advance", it) }
                            })
                        }
                    })
                })
            }
            
            // WiFi data
            sensorPayload.wifi?.let { wifiData ->
                put("wifi", JSONObject().apply {
                    put("timestamp", wifiData.timestamp)
                    put("networks", JSONArray().apply {
                        wifiData.scanResults.forEach { wifi ->
                            put(JSONObject().apply {
                                put("bssid", wifi.bssid)
                                put("ssid", wifi.ssid)
                                put("frequency", wifi.frequency)
                                put("rssi", wifi.rssi)
                                put("capabilities", wifi.capabilities)
                            })
                        }
                    })
                })
            }
        }
    }
    
    /**
     * Creates media data JSON in ProofMode format.
     */
    private fun createMediaDataJson(mediaCapture: SecureMediaCapture): JSONObject {
        return JSONObject().apply {
            put("hash", mediaCapture.getHash())
            put("size", mediaCapture.data.size)
            put("format", "JPEG")
            put("quality", 95)
            put("capture_time", mediaCapture.captureTime)
            
            mediaCapture.metadata.let { metadata ->
                put("metadata", JSONObject().apply {
                    put("width", metadata.width)
                    put("height", metadata.height)
                    put("format", metadata.format)
                    put("timestamp", metadata.timestamp)
                    put("rotation", metadata.rotationDegrees)
                    put("filename", metadata.filename)
                })
            }
        }
    }
    
    /**
     * Creates device metadata JSON.
     */
    private fun createDeviceMetadataJson(): JSONObject {
        return JSONObject().apply {
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("sdk_version", android.os.Build.VERSION.SDK_INT)
            put("device_id", android.os.Build.ID)
            put("hardware", android.os.Build.HARDWARE)
        }
    }
    
    /**
     * Compresses JSON data using GZIP.
     */
    private suspend fun compressJson(jsonString: String): ByteArray = withContext(Dispatchers.IO) {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(jsonString.toByteArray())
        }
        outputStream.toByteArray()
    }
    
    /**
     * Generates PGP signature using ProofMode library.
     * 
     * Note: This is a placeholder implementation. The actual ProofMode integration
     * would use the ProofMode Android library to generate proper PGP signatures.
     */
    private suspend fun generatePgpSignature(data: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        try {
            // TODO: Integrate with actual ProofMode library
            // For now, return a mock signature
            val mockSignature = "proofmode_mock_signature_${data.size}_${System.currentTimeMillis()}"
            mockSignature.toByteArray()
        } catch (e: Exception) {
            throw ProofModeException("Failed to generate PGP signature", e)
        }
    }
    
    /**
     * Verifies a ProofMode bundle signature.
     * 
     * @param bundle ProofMode bundle to verify
     * @return true if signature is valid
     */
    suspend fun verifyBundle(bundle: ProofModeBundle): Boolean = withContext(Dispatchers.IO) {
        try {
            // TODO: Implement actual PGP verification using ProofMode library
            // For now, return true for mock signatures
            bundle.signature.toString().contains("proofmode_mock_signature")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Serializes a ProofMode bundle for transmission.
     * 
     * @param bundle Bundle to serialize
     * @return Serialized bundle bytes
     */
    suspend fun serializeBundle(bundle: ProofModeBundle): ByteArray = withContext(Dispatchers.IO) {
        try {
            val bundleJson = JSONObject().apply {
                put("proof_data", bundle.proofData.toString(Charsets.UTF_8))
                put("signature", bundle.signature.toString(Charsets.UTF_8))
                put("media_hash", bundle.mediaHash)
                put("timestamp", bundle.timestamp)
                put("metadata", JSONObject().apply {
                    put("version", bundle.metadata.version)
                    put("type", bundle.metadata.type)
                    put("uncompressed_size", bundle.metadata.uncompressedSize)
                    put("compressed_size", bundle.metadata.compressedSize)
                })
            }
            
            bundleJson.toString().toByteArray()
        } catch (e: Exception) {
            throw ProofModeException("Failed to serialize bundle", e)
        }
    }
    
    /**
     * Deserializes a ProofMode bundle from bytes.
     * 
     * @param bundleBytes Serialized bundle bytes
     * @return Deserialized ProofMode bundle
     */
    suspend fun deserializeBundle(bundleBytes: ByteArray): ProofModeBundle = withContext(Dispatchers.IO) {
        try {
            val bundleJson = JSONObject(String(bundleBytes, Charsets.UTF_8))
            
            val metadata = BundleMetadata(
                version = bundleJson.getJSONObject("metadata").getString("version"),
                type = bundleJson.getJSONObject("metadata").getString("type"),
                uncompressedSize = bundleJson.getJSONObject("metadata").getInt("uncompressed_size"),
                compressedSize = bundleJson.getJSONObject("metadata").getInt("compressed_size")
            )
            
            ProofModeBundle(
                proofData = bundleJson.getString("proof_data").toByteArray(),
                signature = bundleJson.getString("signature").toByteArray(),
                mediaHash = bundleJson.getString("media_hash"),
                timestamp = bundleJson.getLong("timestamp"),
                metadata = metadata
            )
        } catch (e: Exception) {
            throw ProofModeException("Failed to deserialize bundle", e)
        }
    }
}

/**
 * ProofMode bundle containing all attestation data.
 */
data class ProofModeBundle(
    val proofData: ByteArray, // Compressed JSON data
    val signature: ByteArray, // PGP signature
    val mediaHash: String, // SHA-256 hash of media
    val timestamp: Long,
    val metadata: BundleMetadata
) {
    /**
     * Validates bundle integrity.
     */
    fun isValid(): Boolean {
        return proofData.isNotEmpty() &&
                signature.isNotEmpty() &&
                mediaHash.isNotEmpty() &&
                timestamp > 0 &&
                metadata.uncompressedSize > 0 &&
                metadata.compressedSize > 0
    }
    
    /**
     * Gets bundle size information.
     */
    fun getSizeInfo(): BundleSizeInfo {
        return BundleSizeInfo(
            proofDataSize = proofData.size,
            signatureSize = signature.size,
            totalSize = proofData.size + signature.size,
            compressionRatio = metadata.compressedSize.toFloat() / metadata.uncompressedSize
        )
    }
}

/**
 * Bundle metadata information.
 */
data class BundleMetadata(
    val version: String,
    val type: String,
    val uncompressedSize: Int,
    val compressedSize: Int
)

/**
 * Bundle size information for debugging.
 */
data class BundleSizeInfo(
    val proofDataSize: Int,
    val signatureSize: Int,
    val totalSize: Int,
    val compressionRatio: Float
)

/**
 * Exception thrown for ProofMode operations.
 */
class ProofModeException(message: String, cause: Throwable? = null) : Exception(message, cause)
