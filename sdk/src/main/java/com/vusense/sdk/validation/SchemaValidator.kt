package com.vusense.sdk.validation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import com.vusense.sdk.payload.AttestationSignature
import com.vusense.sdk.payload.AttestationPayload
import com.vusense.sdk.payload.SensorDataPayload
import com.vusense.sdk.payload.LocationPayload
import com.vusense.sdk.payload.MediaDataPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SchemaValidator provides strict validation against attestation_schema.json.
 * 
 * This class ensures complete compliance with the Vusense protocol:
 * - Validates all required fields and data types
 * - Enforces value ranges and formats
 * - Checks cryptographic signature structure
 * - Provides detailed validation reports
 * - Prevents malformed payloads from being transmitted
 */
@Singleton
class SchemaValidator @Inject constructor() {
    
    companion object {
        private const val SCHEMA_VERSION = "1.0"
        private const val PROTOCOL_VERSION = "v1.0"
        private const val PAYLOAD_TYPE = "AttestationSignature"
        
        // Validation constraints
        private const val MAX_LATITUDE = 90.0
        private const val MIN_LATITUDE = -90.0
        private const val MAX_LONGITUDE = 180.0
        private const val MIN_LONGITUDE = -180.0
        private const val MAX_GPS_ACCURACY = 1000.0f // 1km max
        private const val MAX_MEDIA_SIZE = 50 * 1024 * 1024 // 50MB max
        private const val MIN_TIMESTAMP = 1609459200000L // Jan 1, 2021
    }
    
    /**
     * Performs comprehensive schema validation.
     * 
     * @param signature AttestationSignature to validate
     * @return Detailed validation result
     */
    suspend fun validateSchema(signature: AttestationSignature): SchemaValidationResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<ValidationError>()
        val warnings = mutableListOf<ValidationWarning>()
        
        try {
            // Validate top-level structure
            validateTopLevelStructure(signature, errors, warnings)
            
            // Validate vusense context
            validateVusenseContext(signature.vusenseContext, errors, warnings)
            
            // Validate attestation payload
            validateAttestationPayload(signature.attestationPayload, errors, warnings)
            
            // Validate signature wrapper
            validateSignatureWrapper(signature.signature, errors, warnings)
            
            // Validate metadata
            validateMetadata(signature.metadata, errors, warnings)
            
            // Cross-field validations
            validateCrossFieldConsistency(signature, errors, warnings)
            
            val isValid = errors.isEmpty()
            val severity = when {
                errors.isNotEmpty() -> ValidationSeverity.ERROR
                warnings.isNotEmpty() -> ValidationSeverity.WARNING
                else -> ValidationSeverity.SUCCESS
            }
            
            SchemaValidationResult(
                isValid = isValid,
                severity = severity,
                errors = errors,
                warnings = warnings,
                summary = generateValidationSummary(isValid, errors, warnings)
            )
            
        } catch (e: Exception) {
            SchemaValidationResult(
                isValid = false,
                severity = ValidationSeverity.ERROR,
                errors = listOf(ValidationError("SCHEMA_VALIDATION_FAILED", "Schema validation crashed: ${e.message}")),
                warnings = warnings,
                summary = "Schema validation failed with exception"
            )
        }
    }
    
    /**
     * Validates top-level structure.
     */
    private fun validateTopLevelStructure(
        signature: AttestationSignature,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Check schema version
        if (signature.schemaVersion != SCHEMA_VERSION) {
            errors.add(ValidationError(
                code = "INVALID_SCHEMA_VERSION",
                message = "Expected schema version $SCHEMA_VERSION, got ${signature.schemaVersion}"
            ))
        }
        
        // Check protocol version
        if (signature.protocolVersion != PROTOCOL_VERSION) {
            errors.add(ValidationError(
                code = "INVALID_PROTOCOL_VERSION",
                message = "Expected protocol version $PROTOCOL_VERSION, got ${signature.protocolVersion}"
            ))
        }
        
        // Check payload type
        if (signature.type != PAYLOAD_TYPE) {
            errors.add(ValidationError(
                code = "INVALID_PAYLOAD_TYPE",
                message = "Expected payload type $PAYLOAD_TYPE, got ${signature.type}"
            ))
        }
        
        // Check for null required fields
        if (signature.vusenseContext == null) {
            errors.add(ValidationError(
                code = "MISSING_VUSENSE_CONTEXT",
                message = "vusense_context is required"
            ))
        }
        
        if (signature.attestationPayload == null) {
            errors.add(ValidationError(
                code = "MISSING_ATTESTATION_PAYLOAD",
                message = "attestation_payload is required"
            ))
        }
        
        if (signature.signature == null) {
            errors.add(ValidationError(
                code = "MISSING_SIGNATURE",
                message = "signature is required"
            ))
        }
    }
    
    /**
     * Validates vusense context.
     */
    private fun validateVusenseContext(
        context: com.vusense.sdk.payload.VusenseContext,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate user ID
        if (context.userId.isEmpty()) {
            errors.add(ValidationError(
                code = "MISSING_USER_ID",
                message = "user_id cannot be empty"
            ))
        } else if (context.userId.length > 256) {
            warnings.add(ValidationWarning(
                code = "LONG_USER_ID",
                message = "user_id is unusually long (${context.userId.length} characters)"
            ))
        }
        
        // Validate policy ID
        if (context.policyId.isEmpty()) {
            errors.add(ValidationError(
                code = "MISSING_POLICY_ID",
                message = "policy_id cannot be empty"
            ))
        } else if (context.policyId.length > 256) {
            warnings.add(ValidationWarning(
                code = "LONG_POLICY_ID",
                message = "policy_id is unusually long (${context.policyId.length} characters)"
            ))
        }
        
        // Validate timestamp
        if (context.timestamp < MIN_TIMESTAMP) {
            errors.add(ValidationError(
                code = "INVALID_TIMESTAMP",
                message = "timestamp ${context.timestamp} is before minimum allowed time"
            ))
        } else if (context.timestamp > System.currentTimeMillis() + 60000) { // 1 minute tolerance
            warnings.add(ValidationWarning(
                code = "FUTURE_TIMESTAMP",
                message = "timestamp ${context.timestamp} is in the future"
            ))
        }
        
        // Validate device ID
        if (context.deviceId.isEmpty()) {
            errors.add(ValidationError(
                code = "MISSING_DEVICE_ID",
                message = "device_id cannot be empty"
            ))
        }
    }
    
    /**
     * Validates attestation payload.
     */
    private fun validateAttestationPayload(
        payload: AttestationPayload,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate sensor data
        validateSensorData(payload.sensorData, errors, warnings)
        
        // Validate media data
        validateMediaData(payload.mediaData, errors, warnings)
        
        // Validate ProofMode bundle
        validateProofModeBundle(payload.proofModeBundle, errors, warnings)
        
        // Validate payload timestamp
        if (payload.timestamp < MIN_TIMESTAMP) {
            errors.add(ValidationError(
                code = "INVALID_PAYLOAD_TIMESTAMP",
                message = "payload timestamp ${payload.timestamp} is before minimum allowed time"
            ))
        }
    }
    
    /**
     * Validates sensor data payload.
     */
    private fun validateSensorData(
        sensorData: SensorDataPayload,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate location data if present
        sensorData.location?.let { location ->
            validateLocationData(location, errors, warnings)
        } ?: run {
            warnings.add(ValidationWarning(
                code = "MISSING_LOCATION_DATA",
                message = "No location data available in sensor payload"
            ))
        }
        
        // Validate IMU data if present
        sensorData.imu?.let { imu ->
            validateImuData(imu, errors, warnings)
        } ?: run {
            warnings.add(ValidationWarning(
                code = "MISSING_IMU_DATA",
                message = "No IMU data available in sensor payload"
            ))
        }
        
        // Validate sensor timestamp
        if (sensorData.timestamp < MIN_TIMESTAMP) {
            errors.add(ValidationError(
                code = "INVALID_SENSOR_TIMESTAMP",
                message = "sensor timestamp ${sensorData.timestamp} is before minimum allowed time"
            ))
        }
    }
    
    /**
     * Validates location data.
     */
    private fun validateLocationData(
        location: LocationPayload,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate latitude
        if (location.latitude !in MIN_LATITUDE..MAX_LATITUDE) {
            errors.add(ValidationError(
                code = "INVALID_LATITUDE",
                message = "latitude ${location.latitude} is outside valid range [$MIN_LATITUDE, $MAX_LATITUDE]"
            ))
        }
        
        // Validate longitude
        if (location.longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
            errors.add(ValidationError(
                code = "INVALID_LONGITUDE",
                message = "longitude ${location.longitude} is outside valid range [$MIN_LONGITUDE, $MAX_LONGITUDE]"
            ))
        }
        
        // Validate accuracy
        if (location.accuracy < 0) {
            errors.add(ValidationError(
                code = "INVALID_ACCURACY",
                message = "accuracy ${location.accuracy} cannot be negative"
            ))
        } else if (location.accuracy > MAX_GPS_ACCURACY) {
            warnings.add(ValidationWarning(
                code = "LOW_GPS_ACCURACY",
                message = "GPS accuracy ${location.accuracy}m is very poor"
            ))
        }
        
        // Validate provider
        val validProviders = setOf("gps", "network", "passive", "fused")
        if (location.provider.lowercase() !in validProviders) {
            warnings.add(ValidationWarning(
                code = "UNKNOWN_LOCATION_PROVIDER",
                message = "Unknown location provider: ${location.provider}"
            ))
        }
        
        // Validate altitude if present
        location.altitude?.let { altitude ->
            if (altitude < -1000 || altitude > 10000) { // Reasonable Earth altitude range
                warnings.add(ValidationWarning(
                    code = "UNUSUAL_ALTITUDE",
                    message = "Unusual altitude: ${altitude}m"
                ))
            }
        }
        
        // Validate bearing if present
        location.bearing?.let { bearing ->
            if (bearing < 0 || bearing > 360) {
                errors.add(ValidationError(
                    code = "INVALID_BEARING",
                    message = "bearing $bearing is outside valid range [0, 360]"
                ))
            }
        }
        
        // Validate speed if present
        location.speed?.let { speed ->
            if (speed < 0) {
                errors.add(ValidationError(
                    code = "INVALID_SPEED",
                    message = "speed $speed cannot be negative"
                ))
            } else if (speed > 200) { // 200 m/s is very fast
                warnings.add(ValidationWarning(
                    code = "HIGH_SPEED",
                    message = "Unusually high speed: ${speed}m/s"
                ))
            }
        }
    }
    
    /**
     * Validates IMU data.
     */
    private fun validateImuData(
        imu: com.vusense.sdk.payload.ImuPayload,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate accelerometer if present
        imu.accelerometer?.let { accel ->
            validateSensorReading(accel, "accelerometer", errors, warnings)
        }
        
        // Validate gyroscope if present
        imu.gyroscope?.let { gyro ->
            validateSensorReading(gyro, "gyroscope", errors, warnings)
        }
        
        // Validate magnetometer if present
        imu.magnetometer?.let { mag ->
            validateSensorReading(mag, "magnetometer", errors, warnings)
        }
        
        // Validate light sensor if present
        imu.lightSensor?.let { light ->
            if (light < 0) {
                errors.add(ValidationError(
                    code = "INVALID_LIGHT_SENSOR",
                    message = "light sensor value $light cannot be negative"
                ))
            } else if (light > 100000) { // Very high light level
                warnings.add(ValidationWarning(
                    code = "HIGH_LIGHT_LEVEL",
                    message = "Unusually high light level: ${light}lux"
                ))
            }
        }
        
        // Validate IMU timestamp
        if (imu.timestamp < MIN_TIMESTAMP) {
            errors.add(ValidationError(
                code = "INVALID_IMU_TIMESTAMP",
                message = "IMU timestamp ${imu.timestamp} is before minimum allowed time"
            ))
        }
    }
    
    /**
     * Validates individual sensor reading.
     */
    private fun validateSensorReading(
        reading: com.vusense.sdk.payload.SensorReadingPayload,
        sensorType: String,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Check for invalid values
        if (reading.x.isNaN() || reading.y.isNaN() || reading.z.isNaN()) {
            errors.add(ValidationError(
                code = "INVALID_SENSOR_VALUES",
                message = "$sensorType contains NaN values"
            ))
        }
        
        if (reading.x.isInfinite() || reading.y.isInfinite() || reading.z.isInfinite()) {
            errors.add(ValidationError(
                code = "INFINITE_SENSOR_VALUES",
                message = "$sensorType contains infinite values"
            ))
        }
        
        // Validate accuracy
        val validAccuracies = setOf(0, 1, 2, 3) // SensorManager.SENSOR_STATUS_*
        if (reading.accuracy !in validAccuracies) {
            warnings.add(ValidationWarning(
                code = "UNKNOWN_SENSOR_ACCURACY",
                message = "Unknown $sensorType accuracy: ${reading.accuracy}"
            ))
        }
        
        // Validate timestamp
        if (reading.timestamp <= 0) {
            errors.add(ValidationError(
                code = "INVALID_SENSOR_TIMESTAMP",
                message = "$sensorType timestamp ${reading.timestamp} is invalid"
            ))
        }
    }
    
    /**
     * Validates media data.
     */
    private fun validateMediaData(
        mediaData: MediaDataPayload,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate hash
        if (mediaData.hash.isEmpty()) {
            errors.add(ValidationError(
                code = "MISSING_MEDIA_HASH",
                message = "media hash cannot be empty"
            ))
        } else if (!isValidHash(mediaData.hash)) {
            errors.add(ValidationError(
                code = "INVALID_MEDIA_HASH",
                message = "media hash format is invalid: ${mediaData.hash}"
            ))
        }
        
        // Validate size
        if (mediaData.size <= 0) {
            errors.add(ValidationError(
                code = "INVALID_MEDIA_SIZE",
                message = "media size ${mediaData.size} must be positive"
            ))
        } else if (mediaData.size > MAX_MEDIA_SIZE) {
            errors.add(ValidationError(
                code = "MEDIA_TOO_LARGE",
                message = "media size ${mediaData.size} exceeds maximum allowed size $MAX_MEDIA_SIZE"
            ))
        }
        
        // Validate format
        val validFormats = setOf("JPEG", "PNG", "WEBP")
        if (mediaData.format.uppercase() !in validFormats) {
            warnings.add(ValidationWarning(
                code = "UNKNOWN_MEDIA_FORMAT",
                message = "Unknown media format: ${mediaData.format}"
            ))
        }
        
        // Validate media timestamp
        if (mediaData.timestamp < MIN_TIMESTAMP) {
            errors.add(ValidationError(
                code = "INVALID_MEDIA_TIMESTAMP",
                message = "media timestamp ${mediaData.timestamp} is before minimum allowed time"
            ))
        }
    }
    
    /**
     * Validates ProofMode bundle.
     */
    private fun validateProofModeBundle(
        bundle: com.vusense.sdk.payload.ProofModePayload,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate version
        if (bundle.version.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_PROOFMODE_VERSION",
                message = "ProofMode bundle version is missing"
            ))
        }
        
        // Validate bundle hash
        if (bundle.bundleHash.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_BUNDLE_HASH",
                message = "ProofMode bundle hash is missing"
            ))
        } else if (!isValidHash(bundle.bundleHash)) {
            warnings.add(ValidationWarning(
                code = "INVALID_BUNDLE_HASH",
                message = "ProofMode bundle hash format is invalid"
            ))
        }
        
        // Validate signature
        if (bundle.signature.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_PROOFMODE_SIGNATURE",
                message = "ProofMode signature is missing"
            ))
        }
        
        // Validate timestamp
        if (bundle.timestamp < MIN_TIMESTAMP) {
            warnings.add(ValidationWarning(
                code = "INVALID_PROOFMODE_TIMESTAMP",
                message = "ProofMode timestamp ${bundle.timestamp} is before minimum allowed time"
            ))
        }
    }
    
    /**
     * Validates signature wrapper.
     */
    private fun validateSignatureWrapper(
        signature: com.vusense.sdk.payload.SignatureWrapper,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate algorithm
        val validAlgorithms = setOf("ECDSA_SHA256", "RSA_SHA256", "ED25519")
        if (signature.algorithm !in validAlgorithms) {
            errors.add(ValidationError(
                code = "INVALID_SIGNATURE_ALGORITHM",
                message = "Unknown signature algorithm: ${signature.algorithm}"
            ))
        }
        
        // Validate public key
        if (signature.publicKey.isEmpty()) {
            errors.add(ValidationError(
                code = "MISSING_PUBLIC_KEY",
                message = "public key cannot be empty"
            ))
        } else if (!isValidPublicKey(signature.publicKey)) {
            errors.add(ValidationError(
                code = "INVALID_PUBLIC_KEY",
                message = "public key format is invalid"
            ))
        }
        
        // Validate signature
        if (signature.signature.isEmpty()) {
            errors.add(ValidationError(
                code = "MISSING_SIGNATURE",
                message = "signature cannot be empty"
            ))
        } else if (!isValidSignature(signature.signature)) {
            errors.add(ValidationError(
                code = "INVALID_SIGNATURE",
                message = "signature format is invalid"
            ))
        }
        
        // Validate timestamp
        if (signature.timestamp < MIN_TIMESTAMP) {
            errors.add(ValidationError(
                code = "INVALID_SIGNATURE_TIMESTAMP",
                message = "signature timestamp ${signature.timestamp} is before minimum allowed time"
            ))
        }
    }
    
    /**
     * Validates metadata.
     */
    private fun validateMetadata(
        metadata: com.vusense.sdk.payload.AttestationMetadata,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate SDK version
        if (metadata.sdkVersion.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_SDK_VERSION",
                message = "SDK version is missing"
            ))
        }
        
        // Validate device info
        validateDeviceInfo(metadata.deviceInfo, errors, warnings)
        
        // Validate capture duration
        if (metadata.captureDuration < 0) {
            errors.add(ValidationError(
                code = "INVALID_CAPTURE_DURATION",
                message = "capture duration ${metadata.captureDuration} cannot be negative"
            ))
        } else if (metadata.captureDuration > 60000) { // 1 minute max
            warnings.add(ValidationWarning(
                code = "LONG_CAPTURE_DURATION",
                message = "Capture took unusually long: ${metadata.captureDuration}ms"
            ))
        }
        
        // Validate validation status
        val validStatuses = setOf("pending", "passed", "failed")
        if (metadata.validationStatus.lowercase() !in validStatuses) {
            warnings.add(ValidationWarning(
                code = "UNKNOWN_VALIDATION_STATUS",
                message = "Unknown validation status: ${metadata.validationStatus}"
            ))
        }
    }
    
    /**
     * Validates device information.
     */
    private fun validateDeviceInfo(
        deviceInfo: com.vusense.sdk.payload.DeviceInfo,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Validate manufacturer
        if (deviceInfo.manufacturer.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_MANUFACTURER",
                message = "Device manufacturer is missing"
            ))
        }
        
        // Validate model
        if (deviceInfo.model.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_MODEL",
                message = "Device model is missing"
            ))
        }
        
        // Validate Android version
        if (deviceInfo.androidVersion.isEmpty()) {
            warnings.add(ValidationWarning(
                code = "MISSING_ANDROID_VERSION",
                message = "Android version is missing"
            ))
        }
        
        // Validate SDK version
        if (deviceInfo.sdkVersion < 26) { // Our minimum SDK
            errors.add(ValidationError(
                code = "UNSUPPORTED_ANDROID_VERSION",
                message = "Android SDK version ${deviceInfo.sdkVersion} is below minimum supported version (26)"
            ))
        }
    }
    
    /**
     * Validates cross-field consistency.
     */
    private fun validateCrossFieldConsistency(
        signature: AttestationSignature,
        errors: MutableList<ValidationError>,
        warnings: MutableList<ValidationWarning>
    ) {
        // Check timestamp consistency
        val contextTime = signature.vusenseContext.timestamp
        val payloadTime = signature.attestationPayload.timestamp
        val signatureTime = signature.signature.timestamp
        
        if (payloadTime != contextTime) {
            warnings.add(ValidationWarning(
                code = "TIMESTAMP_MISMATCH",
                message = "Payload timestamp ($payloadTime) doesn't match context timestamp ($contextTime)"
            ))
        }
        
        if (signatureTime != contextTime) {
            warnings.add(ValidationWarning(
                code = "SIGNATURE_TIMESTAMP_MISMATCH",
                message = "Signature timestamp ($signatureTime) doesn't match context timestamp ($contextTime)"
            ))
        }
        
        // Check media timestamp consistency
        val mediaTime = signature.attestationPayload.mediaData.timestamp
        if (mediaTime != contextTime) {
            warnings.add(ValidationWarning(
                code = "MEDIA_TIMESTAMP_MISMATCH",
                message = "Media timestamp ($mediaTime) doesn't match context timestamp ($contextTime)"
            ))
        }
    }
    
    /**
     * Validates hash format.
     */
    private fun isValidHash(hash: String): Boolean {
        return hash.matches(Regex("^[a-fA-F0-9]{64}$")) // SHA-256 hex
    }
    
    /**
     * Validates public key format.
     */
    private fun isValidPublicKey(publicKey: String): Boolean {
        // Basic PEM format validation
        return publicKey.contains("-----BEGIN PUBLIC KEY-----") &&
                publicKey.contains("-----END PUBLIC KEY-----") &&
                publicKey.split("\n").size >= 3
    }
    
    /**
     * Validates signature format.
     */
    private fun isValidSignature(signature: String): Boolean {
        // Base64 validation
        return try {
            java.util.Base64.getDecoder().decode(signature)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generates validation summary.
     */
    private fun generateValidationSummary(
        isValid: Boolean,
        errors: List<ValidationError>,
        warnings: List<ValidationWarning>
    ): String {
        return when {
            !isValid -> "Validation failed with ${errors.size} error(s)"
            warnings.isNotEmpty() -> "Validation passed with ${warnings.size} warning(s)"
            else -> "Validation passed successfully"
        }
    }
}

/**
 * Comprehensive schema validation result.
 */
data class SchemaValidationResult(
    val isValid: Boolean,
    val severity: ValidationSeverity,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val summary: String
) {
    /**
     * Gets all validation issues (errors + warnings).
     */
    fun getAllIssues(): List<ValidationIssue> = errors + warnings
    
    /**
     * Checks if there are any critical errors.
     */
    fun hasCriticalErrors(): Boolean = errors.any { it.isCritical }
    
    /**
     * Gets a detailed report.
     */
    fun getDetailedReport(): String {
        return buildString {
            appendLine("=== Schema Validation Report ===")
            appendLine("Status: ${if (isValid) "PASSED" else "FAILED"}")
            appendLine("Severity: $severity")
            appendLine("Summary: $summary")
            appendLine()
            
            if (errors.isNotEmpty()) {
                appendLine("ERRORS (${errors.size}):")
                errors.forEach { error ->
                    appendLine("  [${error.code}] ${error.message}")
                }
                appendLine()
            }
            
            if (warnings.isNotEmpty()) {
                appendLine("WARNINGS (${warnings.size}):")
                warnings.forEach { warning ->
                    appendLine("  [${warning.code}] ${warning.message}")
                }
                appendLine()
            }
            
            appendLine("=== End Report ===")
        }
    }
}

/**
 * Validation severity levels.
 */
enum class ValidationSeverity {
    SUCCESS,    // No issues
    WARNING,    // Only warnings
    ERROR       // At least one error
}

/**
 * Base validation issue.
 */
sealed class ValidationIssue(val code: String, val message: String)

/**
 * Validation error.
 */
data class ValidationError(
    val errorCode: String,
    val errorMessage: String,
    val isCritical: Boolean = true
) : ValidationIssue(errorCode, errorMessage)

/**
 * Validation warning.
 */
data class ValidationWarning(
    val warningCode: String,
    val warningMessage: String
) : ValidationIssue(warningCode, warningMessage)
