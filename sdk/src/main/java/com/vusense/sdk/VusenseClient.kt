package com.vusense.sdk

import android.content.Context
import android.util.Log
import android.view.SurfaceProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import com.vusense.sdk.orchestration.AttestationOrchestrator
import com.vusense.sdk.orchestration.AttestationResult
import com.vusense.sdk.orchestration.OrchestrationState
import com.vusense.sdk.orchestration.CaptureProgress
import com.vusense.sdk.payload.PayloadMapper
import com.vusense.sdk.payload.AttestationSignature
import com.vusense.sdk.payload.ValidationResult
import com.vusense.sdk.security.PlayIntegrityChecker
import com.vusense.sdk.security.IntegrityResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VusenseClient is the public API facade for the Vusense Android SDK.
 * 
 * This class provides a clean, secure interface for host applications:
 * - Simplified initialization with configuration
 * - Single-method capture execution
 * - Real-time state and progress monitoring
 * - Built-in integrity checking and validation
 * - Comprehensive error handling and logging
 * - Thread-safe operations with coroutines
 */
@Singleton
class VusenseClient @Inject constructor(
    private val orchestrator: AttestationOrchestrator,
    private val payloadMapper: PayloadMapper,
    private val integrityChecker: PlayIntegrityChecker
) {
    
    companion object {
        private const val TAG = "VusenseClient"
        private const val DEFAULT_TIMEOUT_MS = 45000L // 45 seconds total
    }
    
    private var isInitialized = false
    private var currentConfig: VusenseConfig? = null
    
    /**
     * Initializes the Vusense SDK with configuration.
     * 
     * @param context Application context
     * @param config SDK configuration
     * @param lifecycleOwner Lifecycle owner for camera operations
     * @param surfaceProvider Optional surface provider for camera preview
     * @return Result indicating success or failure
     */
    suspend fun initialize(
        context: Context,
        config: VusenseConfig,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: PreviewView.SurfaceProvider? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) {
                Log.w(TAG, "VusenseClient already initialized")
                return@withContext Result.success(Unit)
            }
            
            currentConfig = config
            
            Log.d(TAG, "Initializing Vusense SDK with config: userId=${config.userId}, policyId=${config.policyId}")
            
            // Perform integrity check if required
            if (config.requireIntegrityCheck) {
                val integrityResult = integrityChecker.checkIntegrity()
                if (!integrityChecker.meetsAttestationRequirements(integrityResult)) {
                    val error = "Device integrity check failed: ${integrityResult.getStatusSummary()}"
                    Log.e(TAG, error)
                    return@withContext Result.failure(VusenseException(error))
                }
                Log.d(TAG, "Device integrity verified: ${integrityResult.getStatusSummary()}")
            }
            
            // Initialize the orchestrator
            val orchestratorResult = orchestrator.initialize(lifecycleOwner, surfaceProvider)
            if (orchestratorResult.isFailure) {
                val error = "Failed to initialize orchestrator: ${orchestratorResult.exceptionOrNull()?.message}"
                Log.e(TAG, error)
                return@withContext Result.failure(VusenseException(error))
            }
            
            isInitialized = true
            Log.d(TAG, "Vusense SDK initialized successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            val error = "Failed to initialize Vusense SDK: ${e.message}"
            Log.e(TAG, error, e)
            Result.failure(VusenseException(error, e))
        }
    }
    
    /**
     * Executes a complete attestation capture.
     * 
     * @param captureConfig Optional capture configuration overrides
     * @return Complete attestation signature ready for transmission
     */
    suspend fun captureAttestation(
        captureConfig: CaptureConfig? = null
    ): AttestationSignature = withContext(Dispatchers.IO) {
        
        if (!isInitialized) {
            throw VusenseException("VusenseClient not initialized. Call initialize() first.")
        }
        
        val config = currentConfig ?: throw VusenseException("No configuration available")
        
        try {
            Log.d(TAG, "Starting attestation capture")
            
            // Perform integrity check if configured
            if (config.requireIntegrityCheck) {
                val integrityResult = integrityChecker.quickCheck()
                if (!integrityChecker.meetsAttestationRequirements(integrityResult)) {
                    throw VusenseException("Device integrity check failed: ${integrityResult.getStatusSummary()}")
                }
            }
            
            // Execute capture with timeout
            val attestationResult = withTimeout(config.timeoutMs) {
                orchestrator.executeCapture(captureConfig ?: com.vusense.sdk.orchestration.CaptureConfig.default())
            }
            
            Log.d(TAG, "Capture completed, mapping to attestation signature")
            
            // Map to attestation signature
            val attestationSignature = payloadMapper.mapToAttestationSignature(
                attestationResult = attestationResult,
                userId = config.userId,
                policyId = config.policyId
            )
            
            // Validate the signature if configured
            if (config.validatePayload) {
                val validationResult = payloadMapper.validateSignature(attestationSignature)
                if (!validationResult.isValid) {
                    val errors = validationResult.errors.joinToString(", ")
                    throw VusenseException("Payload validation failed: $errors")
                }
                Log.d(TAG, "Payload validation passed")
            }
            
            Log.d(TAG, "Attestation capture completed successfully")
            attestationSignature
            
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Capture timeout after ${config.timeoutMs}ms")
            throw VusenseException("Capture timeout", e)
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            throw VusenseException("Capture failed: ${e.message}", e)
        }
    }
    
    /**
     * Gets the current orchestration state.
     * 
     * @return Current state of the attestation pipeline
     */
    fun getOrchestrationState(): OrchestrationState {
        return orchestrator.getCurrentState()
    }
    
    /**
     * Gets the current capture progress.
     * 
     * @return Current progress of the capture operation
     */
    fun getCaptureProgress(): CaptureProgress {
        return orchestrator.getCurrentProgress()
    }
    
    /**
     * Gets the orchestration state as a StateFlow for reactive updates.
     * 
     * @return StateFlow of orchestration state changes
     */
    fun getOrchestrationStateFlow(): StateFlow<OrchestrationState> {
        return orchestrator.orchestrationState
    }
    
    /**
     * Gets the capture progress as a StateFlow for reactive updates.
     * 
     * @return StateFlow of capture progress updates
     */
    fun getCaptureProgressFlow(): StateFlow<CaptureProgress> {
        return orchestrator.captureProgress
    }
    
    /**
     * Performs a device integrity check.
     * 
     * @return Integrity check result
     */
    suspend fun checkIntegrity(): IntegrityResult {
        return integrityChecker.checkIntegrity()
    }
    
    /**
     * Gets the latest integrity check result.
     * 
     * @return Cached integrity result if available
     */
    fun getLastIntegrityResult(): IntegrityResult? {
        return integrityChecker.getIntegrityReport().lastResult
    }
    
    /**
     * Validates an attestation signature.
     * 
     * @param signature Attestation signature to validate
     * @return Validation result with details
     */
    suspend fun validateSignature(signature: AttestationSignature): ValidationResult {
        return payloadMapper.validateSignature(signature)
    }
    
    /**
     * Serializes an attestation signature to JSON for transmission.
     * 
     * @param signature Attestation signature to serialize
     * @return JSON string representation
     */
    suspend fun serializeSignature(signature: AttestationSignature): String {
        return payloadMapper.serializeToJson(signature)
    }
    
    /**
     * Checks if the SDK is ready for capture operations.
     * 
     * @return true if SDK is initialized and ready
     */
    fun isReady(): Boolean {
        return isInitialized && orchestrator.isReady()
    }
    
    /**
     * Checks if a capture operation is currently in progress.
     * 
     * @return true if capture is active
     */
    fun isCapturing(): Boolean {
        return orchestrator.isCapturing()
    }
    
    /**
     * Cancels any ongoing capture operation.
     */
    fun cancelCapture() {
        Log.d(TAG, "Cancelling capture operation")
        orchestrator.cancelCapture()
    }
    
    /**
     * Gets SDK status information for debugging.
     * 
     * @return SDK status report
     */
    fun getStatus(): VusenseStatus {
        return VusenseStatus(
            isInitialized = isInitialized,
            isReady = isReady(),
            isCapturing = isCapturing(),
            orchestrationState = getOrchestrationState(),
            captureProgress = getCaptureProgress(),
            integrityReport = integrityChecker.getIntegrityReport(),
            config = currentConfig
        )
    }
    
    /**
     * Updates the SDK configuration.
     * 
     * @param newConfig New configuration to apply
     * @return Result indicating success or failure
     */
    suspend fun updateConfiguration(newConfig: VusenseConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (newConfig != currentConfig) {
                currentConfig = newConfig
                Log.d(TAG, "Configuration updated")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(VusenseException("Failed to update configuration", e))
        }
    }
    
    /**
     * Cleanup SDK resources.
     * 
     * This should be called when the SDK is no longer needed.
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up Vusense SDK")
        try {
            orchestrator.cleanup()
            integrityChecker.clearCache()
            isInitialized = false
            currentConfig = null
            Log.d(TAG, "Vusense SDK cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

/**
 * Configuration for the Vusense SDK.
 */
data class VusenseConfig(
    val userId: String,
    val policyId: String,
    val requireIntegrityCheck: Boolean = true,
    val validatePayload: Boolean = true,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val enableLogging: Boolean = true,
    val customMetadata: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * Creates a default configuration.
         */
        fun default(userId: String, policyId: String) = VusenseConfig(
            userId = userId,
            policyId = policyId
        )
        
        /**
         * Creates a development configuration with relaxed security.
         */
        fun development(userId: String, policyId: String) = VusenseConfig(
            userId = userId,
            policyId = policyId,
            requireIntegrityCheck = false,
            validatePayload = false,
            enableLogging = true
        )
        
        /**
         * Creates a production configuration with strict security.
         */
        fun production(userId: String, policyId: String) = VusenseConfig(
            userId = userId,
            policyId = policyId,
            requireIntegrityCheck = true,
            validatePayload = true,
            enableLogging = false,
            timeoutMs = 30000L // Shorter timeout for production
        )
    }
}

/**
 * SDK status information.
 */
data class VusenseStatus(
    val isInitialized: Boolean,
    val isReady: Boolean,
    val isCapturing: Boolean,
    val orchestrationState: OrchestrationState,
    val captureProgress: CaptureProgress,
    val integrityReport: com.vusense.sdk.security.IntegrityReport,
    val config: VusenseConfig?
) {
    /**
     * Gets a human-readable status summary.
     */
    fun getSummary(): String {
        return when {
            !isInitialized -> "Not initialized"
            isCapturing -> "Capturing in progress"
            isReady -> "Ready for capture"
            else -> "Not ready"
        }
    }
}

/**
 * Exception thrown for Vusense SDK operations.
 */
class VusenseException(message: String, cause: Throwable? = null) : Exception(message, cause)
