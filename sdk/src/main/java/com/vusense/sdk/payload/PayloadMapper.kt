package com.vusense.sdk.payload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import com.vusense.sdk.proofmode.ProofModeBundle
import com.vusense.sdk.orchestration.AttestationResult
import com.vusense.sdk.orchestration.AttestationData
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PayloadMapper translates ProofMode output into the attestation_schema.json format.
 * 
 * This class implements the Vusense protocol schema:
 * - Converts ProofMode bundles to Vusense AttestationSignature format
 * - Ensures strict compliance with attestation_schema.json
 * - Handles cryptographic key serialization
 * - Provides validation against the schema
 */
@Singleton
class PayloadMapper @Inject constructor() {
    
    companion object {
        private const val SCHEMA_VERSION = "1.0"
        private const val PROTOCOL_VERSION = "v1.0"
        private const val PAYLOAD_TYPE = "AttestationSignature"
    }
    
    /**
     * Maps an AttestationResult to the Vusense AttestationSignature format.
     * 
     * @param attestationResult Complete attestation result with signature
     * @param userId User identifier for context
     * @param policyId Policy identifier for context
     * @return AttestationSignature in schema format
     */
    suspend fun mapToAttestationSignature(
        attestationResult: AttestationResult,
        userId: String,
        policyId: String
    ): AttestationSignature = withContext(Dispatchers.IO) {
        
        try {
            // Create the vusense context
            val vusenseContext = VusenseContext(
                userId = userId,
                policyId = policyId,
                timestamp = attestationResult.captureTime,
                deviceId = generateDeviceId()
            )
            
            // Create the attestation payload
            val attestationPayload = createAttestationPayload(attestationResult.data)
            
            // Create the signature wrapper
            val signatureWrapper = SignatureWrapper(
                algorithm = "ECDSA_SHA256",
                publicKey = serializePublicKey(attestationResult.publicKey),
                signature = Base64.getEncoder().encodeToString(attestationResult.signature),
                timestamp = attestationResult.captureTime
            )
            
            // Assemble the final AttestationSignature
            AttestationSignature(
                schemaVersion = SCHEMA_VERSION,
                protocolVersion = PROTOCOL_VERSION,
                type = PAYLOAD_TYPE,
                vusenseContext = vusenseContext,
                attestationPayload = attestationPayload,
                signature = signatureWrapper,
                metadata = createMetadata(attestationResult)
            )
            
        } catch (e: Exception) {
            throw PayloadException("Failed to map to AttestationSignature", e)
        }
    }
    
    /**
     * Creates the attestation payload from attestation data.
     */
    private suspend fun createAttestationPayload(data: AttestationData): AttestationPayload = withContext(Dispatchers.IO) {
        
        // Map sensor data
        val sensorData = SensorDataPayload(
            location = createLocationPayload(data.sensorPayload.location),
            imu = createImuPayload(data.sensorPayload.imu),
            network = createNetworkPayload(data.sensorPayload.cellTower, data.sensorPayload.wifi),
            timestamp = data.sensorPayload.timestamp
        )
        
        // Map media data
        val mediaData = MediaDataPayload(
            hash = data.mediaCapture.getHash(),
            size = data.mediaCapture.data.size,
            format = "JPEG",
            metadata = createMediaMetadataPayload(data.mediaCapture),
            timestamp = data.mediaCapture.captureTime
        )
        
        AttestationPayload(
            sensorData = sensorData,
            mediaData = mediaData,
            proofModeBundle = createProofModePayload(data), // Would be populated from actual ProofMode integration
            timestamp = data.timestamp
        )
    }
    
    /**
     * Creates location payload.
     */
    private fun createLocationPayload(location: com.vusense.sdk.sensors.LocationData?): LocationPayload? {
        return location?.let {
            LocationPayload(
                latitude = it.latitude,
                longitude = it.longitude,
                accuracy = it.accuracy,
                altitude = it.altitude,
                bearing = it.bearing,
                speed = it.speed,
                provider = it.provider,
                timestamp = it.timestamp
            )
        }
    }
    
    /**
     * Creates IMU payload.
     */
    private fun createImuPayload(imu: com.vusense.sdk.sensors.ImuData?): ImuPayload? {
        return imu?.let {
            ImuPayload(
                accelerometer = createSensorReadingPayload(it.accelerometer),
                gyroscope = createSensorReadingPayload(it.gyroscope),
                magnetometer = createSensorReadingPayload(it.magnetometer),
                lightSensor = it.lightSensor,
                timestamp = it.timestamp
            )
        }
    }
    
    /**
     * Creates sensor reading payload.
     */
    private fun createSensorReadingPayload(reading: com.vusense.sdk.sensors.SensorReading?): SensorReadingPayload? {
        return reading?.let {
            SensorReadingPayload(
                x = it.x,
                y = it.y,
                z = it.z,
                accuracy = it.accuracy,
                timestamp = it.timestamp
            )
        }
    }
    
    /**
     * Creates network payload.
     */
    private fun createNetworkPayload(
        cellTower: com.vusense.sdk.sensors.CellTowerData?,
        wifi: com.vusense.sdk.sensors.WifiData?
    ): NetworkPayload? {
        val cellPayload = cellTower?.let { cell ->
            CellularPayload(
                timestamp = cell.timestamp,
                cells = cell.cellInfo.map { cellInfo ->
                    CellularCellPayload(
                        type = cellInfo.type,
                        mcc = cellInfo.mcc,
                        mnc = cellInfo.mnc,
                        lac = cellInfo.lac,
                        tac = cellInfo.tac,
                        cid = cellInfo.cid,
                        pci = cellInfo.pci,
                        signalStrength = cellInfo.signalStrength,
                        timingAdvance = cellInfo.timingAdvance
                    )
                }
            )
        }
        
        val wifiPayload = wifi?.let { wifiData ->
            WifiPayload(
                timestamp = wifiData.timestamp,
                networks = wifiData.scanResults.map { wifiResult ->
                    WifiNetworkPayload(
                        bssid = wifiResult.bssid,
                        ssid = wifiResult.ssid,
                        frequency = wifiResult.frequency,
                        rssi = wifiResult.rssi,
                        capabilities = wifiResult.capabilities
                    )
                }
            )
        }
        
        return if (cellPayload != null || wifiPayload != null) {
            NetworkPayload(
                cellular = cellPayload,
                wifi = wifiPayload
            )
        } else null
    }
    
    /**
     * Creates media metadata payload.
     */
    private fun createMediaMetadataPayload(mediaCapture: com.vusense.sdk.media.SecureMediaCapture): MediaMetadataPayload {
        return MediaMetadataPayload(
            width = mediaCapture.metadata.width,
            height = mediaCapture.metadata.height,
            format = mediaCapture.metadata.format,
            timestamp = mediaCapture.metadata.timestamp,
            rotation = mediaCapture.metadata.rotationDegrees,
            filename = mediaCapture.metadata.filename
        )
    }
    
    /**
     * Creates ProofMode payload placeholder.
     */
    private suspend fun createProofModePayload(data: AttestationData): ProofModePayload = withContext(Dispatchers.IO) {
        // TODO: This would be populated from actual ProofMode integration
        ProofModePayload(
            version = "1.0",
            bundleHash = "", // Would be actual ProofMode bundle hash
            signature = "", // Would be actual PGP signature
            timestamp = data.timestamp
        )
    }
    
    /**
     * Creates metadata for the attestation signature.
     */
    private fun createMetadata(result: AttestationResult): AttestationMetadata {
        return AttestationMetadata(
            sdkVersion = "1.0.0",
            deviceInfo = DeviceInfo(
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                androidVersion = android.os.Build.VERSION.RELEASE,
                sdkVersion = android.os.Build.VERSION.SDK_INT
            ),
            captureDuration = 0L, // Would be calculated from actual timing
            validationStatus = "pending"
        )
    }
    
    /**
     * Serializes public key to PEM format.
     */
    private suspend fun serializePublicKey(publicKey: PublicKey): String = withContext(Dispatchers.IO) {
        try {
            // Convert to X.509 and then to Base64
            val encoded = publicKey.encoded
            val base64 = Base64.getEncoder().encodeToString(encoded)
            
            // Format as PEM
            buildString {
                appendLine("-----BEGIN PUBLIC KEY-----")
                base64.chunked(64).forEach { line ->
                    appendLine(line)
                }
                appendLine("-----END PUBLIC KEY-----")
            }
        } catch (e: Exception) {
            throw PayloadException("Failed to serialize public key", e)
        }
    }
    
    /**
     * Generates a device identifier.
     */
    private fun generateDeviceId(): String {
        return "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}_${android.os.Build.ID}"
            .replace("[^A-Za-z0-9_]".toRegex(), "_")
            .lowercase()
    }
    
    /**
     * Validates an AttestationSignature against the schema.
     * 
     * @param signature AttestationSignature to validate
     * @return ValidationResult with details
     */
    suspend fun validateSignature(signature: AttestationSignature): ValidationResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        try {
            // Check required fields
            if (signature.schemaVersion.isEmpty()) {
                errors.add("Missing schema version")
            }
            
            if (signature.type != PAYLOAD_TYPE) {
                errors.add("Invalid payload type: ${signature.type}")
            }
            
            if (signature.vusenseContext.userId.isEmpty()) {
                errors.add("Missing user ID")
            }
            
            if (signature.vusenseContext.policyId.isEmpty()) {
                errors.add("Missing policy ID")
            }
            
            // Validate sensor data
            val sensorData = signature.attestationPayload.sensorData
            if (sensorData.location == null) {
                warnings.add("No location data available")
            } else {
                if (sensorData.location.latitude !in -90.0..90.0) {
                    errors.add("Invalid latitude: ${sensorData.location.latitude}")
                }
                if (sensorData.location.longitude !in -180.0..180.0) {
                    errors.add("Invalid longitude: ${sensorData.location.longitude}")
                }
                if (sensorData.location.accuracy < 0) {
                    errors.add("Invalid accuracy: ${sensorData.location.accuracy}")
                }
            }
            
            // Validate media data
            val mediaData = signature.attestationPayload.mediaData
            if (mediaData.hash.isEmpty()) {
                errors.add("Missing media hash")
            }
            
            if (mediaData.size <= 0) {
                errors.add("Invalid media size: ${mediaData.size}")
            }
            
            // Validate signature
            if (signature.signature.signature.isEmpty()) {
                errors.add("Missing signature")
            }
            
            if (signature.signature.publicKey.isEmpty()) {
                errors.add("Missing public key")
            }
            
            ValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
                warnings = warnings
            )
            
        } catch (e: Exception) {
            ValidationResult(
                isValid = false,
                errors = listOf("Validation failed: ${e.message}"),
                warnings = warnings
            )
        }
    }
    
    /**
     * Serializes an AttestationSignature to JSON for transmission.
     */
    suspend fun serializeToJson(signature: AttestationSignature): String = withContext(Dispatchers.IO) {
        try {
            JSONObject().apply {
                put("schema_version", signature.schemaVersion)
                put("protocol_version", signature.protocolVersion)
                put("type", signature.type)
                
                // Vusense context
                put("vusense_context", JSONObject().apply {
                    put("user_id", signature.vusenseContext.userId)
                    put("policy_id", signature.vusenseContext.policyId)
                    put("timestamp", signature.vusenseContext.timestamp)
                    put("device_id", signature.vusenseContext.deviceId)
                })
                
                // Attestation payload
                put("attestation_payload", createPayloadJson(signature.attestationPayload))
                
                // Signature
                put("signature", JSONObject().apply {
                    put("algorithm", signature.signature.algorithm)
                    put("public_key", signature.signature.publicKey)
                    put("signature", signature.signature.signature)
                    put("timestamp", signature.signature.timestamp)
                })
                
                // Metadata
                put("metadata", JSONObject().apply {
                    put("sdk_version", signature.metadata.sdkVersion)
                    put("device_info", JSONObject().apply {
                        put("manufacturer", signature.metadata.deviceInfo.manufacturer)
                        put("model", signature.metadata.deviceInfo.model)
                        put("android_version", signature.metadata.deviceInfo.androidVersion)
                        put("sdk_version", signature.metadata.deviceInfo.sdkVersion)
                    })
                    put("capture_duration", signature.metadata.captureDuration)
                    put("validation_status", signature.metadata.validationStatus)
                })
            }.toString()
        } catch (e: Exception) {
            throw PayloadException("Failed to serialize to JSON", e)
        }
    }
    
    /**
     * Creates JSON for attestation payload.
     */
    private fun createPayloadJson(payload: AttestationPayload): JSONObject {
        return JSONObject().apply {
            // Sensor data
            put("sensor_data", JSONObject().apply {
                payload.sensorData.location?.let { location ->
                    put("location", JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("altitude", location.altitude)
                        put("bearing", location.bearing)
                        put("speed", location.speed)
                        put("provider", location.provider)
                        put("timestamp", location.timestamp)
                    })
                }
                
                payload.sensorData.imu?.let { imu ->
                    put("imu", JSONObject().apply {
                        imu.accelerometer?.let { accel ->
                            put("accelerometer", JSONObject().apply {
                                put("x", accel.x)
                                put("y", accel.y)
                                put("z", accel.z)
                                put("accuracy", accel.accuracy)
                                put("timestamp", accel.timestamp)
                            })
                        }
                        // Add gyroscope, magnetometer, light_sensor similarly
                    })
                }
                
                put("timestamp", payload.sensorData.timestamp)
            })
            
            // Media data
            put("media_data", JSONObject().apply {
                put("hash", payload.mediaData.hash)
                put("size", payload.mediaData.size)
                put("format", payload.mediaData.format)
                put("timestamp", payload.mediaData.timestamp)
            })
            
            put("timestamp", payload.timestamp)
        }
    }
}

/**
 * Main attestation signature structure matching attestation_schema.json
 */
data class AttestationSignature(
    val schemaVersion: String,
    val protocolVersion: String,
    val type: String,
    val vusenseContext: VusenseContext,
    val attestationPayload: AttestationPayload,
    val signature: SignatureWrapper,
    val metadata: AttestationMetadata
)

/**
 * Vusense context information
 */
data class VusenseContext(
    val userId: String,
    val policyId: String,
    val timestamp: Long,
    val deviceId: String
)

/**
 * Attestation payload containing all collected data
 */
data class AttestationPayload(
    val sensorData: SensorDataPayload,
    val mediaData: MediaDataPayload,
    val proofModeBundle: ProofModePayload,
    val timestamp: Long
)

/**
 * Sensor data payload
 */
data class SensorDataPayload(
    val location: LocationPayload?,
    val imu: ImuPayload?,
    val network: NetworkPayload?,
    val timestamp: Long
)

/**
 * Location payload
 */
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val bearing: Float?,
    val speed: Float?,
    val provider: String,
    val timestamp: Long
)

/**
 * IMU payload
 */
data class ImuPayload(
    val accelerometer: SensorReadingPayload?,
    val gyroscope: SensorReadingPayload?,
    val magnetometer: SensorReadingPayload?,
    val lightSensor: Float?,
    val timestamp: Long
)

/**
 * Sensor reading payload
 */
data class SensorReadingPayload(
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int,
    val timestamp: Long
)

/**
 * Network payload
 */
data class NetworkPayload(
    val cellular: CellularPayload?,
    val wifi: WifiPayload?
)

/**
 * Cellular payload
 */
data class CellularPayload(
    val timestamp: Long,
    val cells: List<CellularCellPayload>
)

/**
 * Individual cell payload
 */
data class CellularCellPayload(
    val type: String,
    val mcc: Int,
    val mnc: Int,
    val lac: Int?,
    val tac: Int?,
    val cid: Int?,
    val pci: Int?,
    val signalStrength: Int?,
    val timingAdvance: Int?
)

/**
 * WiFi payload
 */
data class WifiPayload(
    val timestamp: Long,
    val networks: List<WifiNetworkPayload>
)

/**
 * Individual WiFi network payload
 */
data class WifiNetworkPayload(
    val bssid: String,
    val ssid: String?,
    val frequency: Int,
    val rssi: Int,
    val capabilities: String
)

/**
 * Media data payload
 */
data class MediaDataPayload(
    val hash: String,
    val size: Int,
    val format: String,
    val metadata: MediaMetadataPayload,
    val timestamp: Long
)

/**
 * Media metadata payload
 */
data class MediaMetadataPayload(
    val width: Int,
    val height: Int,
    val format: Int,
    val timestamp: Long,
    val rotation: Int,
    val filename: String
)

/**
 * ProofMode payload placeholder
 */
data class ProofModePayload(
    val version: String,
    val bundleHash: String,
    val signature: String,
    val timestamp: Long
)

/**
 * Signature wrapper
 */
data class SignatureWrapper(
    val algorithm: String,
    val publicKey: String,
    val signature: String,
    val timestamp: Long
)

/**
 * Attestation metadata
 */
data class AttestationMetadata(
    val sdkVersion: String,
    val deviceInfo: DeviceInfo,
    val captureDuration: Long,
    val validationStatus: String
)

/**
 * Device information
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkVersion: Int
)

/**
 * Validation result
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

/**
 * Exception thrown for payload mapping operations
 */
class PayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
