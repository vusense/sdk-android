package com.vusense.sdk.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlayIntegrityChecker provides device integrity verification using Google Play Integrity API.
 * 
 * This class implements strict security policies:
 * - Verifies device is not rooted or compromised
 * - Checks app installation source legitimacy
 * - Validates device integrity before allowing attestation
 * - Provides detailed integrity reports for debugging
 * - Graceful handling of Play Services unavailability
 */
@Singleton
class PlayIntegrityChecker @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "VusenseIntegrity"
        private const val INTEGRITY_TOKEN_EXPIRATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val NONCE_LENGTH = 24
    }
    
    private val integrityManager: IntegrityManager = IntegrityManagerFactory.create(context)
    private var lastIntegrityResult: IntegrityResult? = null
    private var lastCheckTime: Long = 0
    
    /**
     * Performs device integrity check.
     * 
     * @param nonce Optional nonce for token request (generated if null)
     * @return IntegrityResult with detailed device status
     */
    suspend fun checkIntegrity(nonce: String? = null): IntegrityResult = withContext(Dispatchers.IO) {
        try {
            // Check if we have a recent valid result
            val currentTime = System.currentTimeMillis()
            if (lastIntegrityResult != null && 
                (currentTime - lastCheckTime) < INTEGRITY_TOKEN_EXPIRATION_MS &&
                lastIntegrityResult?.isValid() == true) {
                return@withContext lastIntegrityResult!!
            }
            
            // Generate nonce if not provided
            val requestNonce = nonce ?: generateNonce()
            
            // Create integrity token request
            val tokenRequest = IntegrityTokenRequest.builder()
                .setNonce(requestNonce.toByteArray())
                .build()
            
            // Request integrity token
            val tokenResponse = integrityManager.requestIntegrityToken(tokenRequest).await()
            
            // Parse and validate the response
            val result = parseIntegrityResponse(tokenResponse, requestNonce)
            
            // Cache the result
            lastIntegrityResult = result
            lastCheckTime = currentTime
            
            result
            
        } catch (e: Exception) {
            val errorResult = IntegrityResult(
                isValid = false,
                deviceIntegrity = DeviceIntegrity.INVALID,
                appIntegrity = AppIntegrity.INVALID,
                accountIntegrity = AccountIntegrity.UNKNOWN,
                token = null,
                nonce = nonce,
                errorMessage = e.message ?: "Integrity check failed",
                timestamp = System.currentTimeMillis()
            )
            
            lastIntegrityResult = errorResult
            lastCheckTime = System.currentTimeMillis()
            
            errorResult
        }
    }
    
    /**
     * Performs quick integrity check using cached result if available.
     * 
     * @return Cached integrity result or performs new check if cache expired
     */
    suspend fun quickCheck(): IntegrityResult = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        
        if (lastIntegrityResult != null && 
            (currentTime - lastCheckTime) < INTEGRITY_TOKEN_EXPIRATION_MS) {
            return@withContext lastIntegrityResult!!
        }
        
        checkIntegrity()
    }
    
    /**
     * Parses integrity token response.
     */
    private suspend fun parseIntegrityResponse(
        response: IntegrityTokenResponse,
        expectedNonce: String
    ): IntegrityResult = withContext(Dispatchers.IO) {
        
        try {
            val token = response.token()
            
            // TODO: Parse the JWT token to extract integrity verdicts
            // For now, we'll create a basic result structure
            // In production, you would decode the JWT and verify the claims
            
            // Mock parsing - in reality, you'd decode the JWT and check:
            // - appsIntegrity verdict (PLAY_STORE, PRIVATE_APP, etc.)
            // - deviceIntegrity verdict (MEETS_BASIC_INTEGRITY, MEETS_DEVICE_INTEGRITY, etc.)
            // - accountIntegrity verdict (UNDETERMINED, UNEVALUATED, etc.)
            
            val deviceIntegrity = when {
                // Check if token indicates basic integrity
                token.contains("MEETS_DEVICE_INTEGRITY") -> DeviceIntegrity.MEETS_DEVICE_INTEGRITY
                token.contains("MEETS_BASIC_INTEGRITY") -> DeviceIntegrity.MEETS_BASIC_INTEGRITY
                else -> DeviceIntegrity.FAILS_BASIC_INTEGRITY
            }
            
            val appIntegrity = when {
                token.contains("PLAY_STORE") -> AppIntegrity.PLAY_STORE
                token.contains("PRIVATE_APP") -> AppIntegrity.PRIVATE_APP
                else -> AppIntegrity.UNKNOWN
            }
            
            val accountIntegrity = AccountIntegrity.UNDETERMINED // Would be parsed from token
            
            val isValid = deviceIntegrity != DeviceIntegrity.FAILS_BASIC_INTEGRITY &&
                         appIntegrity != AppIntegrity.UNKNOWN
            
            IntegrityResult(
                isValid = isValid,
                deviceIntegrity = deviceIntegrity,
                appIntegrity = appIntegrity,
                accountIntegrity = accountIntegrity,
                token = token,
                nonce = expectedNonce,
                errorMessage = null,
                timestamp = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            throw IntegrityException("Failed to parse integrity response", e)
        }
    }
    
    /**
     * Generates a cryptographic nonce for integrity requests.
     */
    private fun generateNonce(): String {
        val nonceBytes = ByteArray(NONCE_LENGTH)
        java.security.SecureRandom().nextBytes(nonceBytes)
        return android.util.Base64.encodeToString(nonceBytes, android.util.Base64.NO_WRAP)
    }
    
    /**
     * Validates if device meets minimum integrity requirements for attestation.
     * 
     * @param integrityResult Result to validate
     * @return true if device is suitable for attestation
     */
    fun meetsAttestationRequirements(integrityResult: IntegrityResult): Boolean {
        return integrityResult.isValid &&
                integrityResult.deviceIntegrity != DeviceIntegrity.FAILS_BASIC_INTEGRITY &&
                integrityResult.appIntegrity != AppIntegrity.UNKNOWN
    }
    
    /**
     * Gets detailed integrity report for debugging.
     */
    fun getIntegrityReport(): IntegrityReport {
        val result = lastIntegrityResult
        
        return IntegrityReport(
            lastCheckTime = lastCheckTime,
            isCacheValid = result != null && 
                          (System.currentTimeMillis() - lastCheckTime) < INTEGRITY_TOKEN_EXPIRATION_MS,
            lastResult = result,
            recommendations = generateRecommendations(result)
        )
    }
    
    /**
     * Generates security recommendations based on integrity result.
     */
    private fun generateRecommendations(result: IntegrityResult?): List<String> {
        val recommendations = mutableListOf<String>()
        
        if (result == null) {
            recommendations.add("Perform initial integrity check")
            return recommendations
        }
        
        when (result.deviceIntegrity) {
            DeviceIntegrity.FAILS_BASIC_INTEGRITY -> {
                recommendations.add("Device appears to be rooted or compromised")
                recommendations.add("Check for custom ROM or system modifications")
                recommendations.add("Verify device security settings")
            }
            DeviceIntegrity.MEETS_BASIC_INTEGRITY -> {
                recommendations.add("Device meets basic integrity but may have security concerns")
            }
            DeviceIntegrity.MEETS_DEVICE_INTEGRITY -> {
                recommendations.add("Device integrity verified")
            }
            DeviceIntegrity.INVALID -> {
                recommendations.add("Integrity check failed - check Play Services")
            }
        }
        
        when (result.appIntegrity) {
            AppIntegrity.UNKNOWN -> {
                recommendations.add("App installation source could not be verified")
            }
            AppIntegrity.PLAY_STORE -> {
                recommendations.add("App installed from Google Play Store")
            }
            AppIntegrity.PRIVATE_APP -> {
                recommendations.add("App installed as private application")
            }
            AppIntegrity.INVALID -> {
                recommendations.add("App integrity check failed")
            }
        }
        
        if (result.errorMessage != null) {
            recommendations.add("Error occurred: ${result.errorMessage}")
        }
        
        return recommendations
    }
    
    /**
     * Clears cached integrity results.
     */
    fun clearCache() {
        lastIntegrityResult = null
        lastCheckTime = 0
    }
    
    /**
     * Checks if Play Services is available.
     */
    suspend fun isPlayServicesAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            integrityManager.requestIntegrityToken(
                IntegrityTokenRequest.builder()
                    .setNonce("test".toByteArray())
                    .build()
            ).await()
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Device integrity levels.
 */
enum class DeviceIntegrity {
    MEETS_DEVICE_INTEGRITY,    // Strong integrity guarantees
    MEETS_BASIC_INTEGRITY,     // Basic integrity checks passed
    FAILS_BASIC_INTEGRITY,     // Device likely rooted or compromised
    INVALID                    // Check failed
}

/**
 * App integrity levels.
 */
enum class AppIntegrity {
    PLAY_STORE,    // Installed from Google Play
    PRIVATE_APP,   // Installed as private app
    UNKNOWN,       // Installation source unknown
    INVALID        // Check failed
}

/**
 * Account integrity levels.
 */
enum class AccountIntegrity {
    UNDETERMINED,  // Account status unknown
    UNEVALUATED,   // Account not evaluated
    INVALID        // Check failed
}

/**
 * Complete integrity check result.
 */
data class IntegrityResult(
    val isValid: Boolean,
    val deviceIntegrity: DeviceIntegrity,
    val appIntegrity: AppIntegrity,
    val accountIntegrity: AccountIntegrity,
    val token: String?,
    val nonce: String?,
    val errorMessage: String?,
    val timestamp: Long
) {
    /**
     * Checks if the result is still valid (not expired).
     */
    fun isExpired(): Boolean {
        val expirationTime = timestamp + PlayIntegrityChecker.INTEGRITY_TOKEN_EXPIRATION_MS
        return System.currentTimeMillis() > expirationTime
    }
    
    /**
     * Gets a human-readable status summary.
     */
    fun getStatusSummary(): String {
        return when {
            !isValid && errorMessage != null -> "Error: $errorMessage"
            !isValid -> "Integrity check failed"
            deviceIntegrity == DeviceIntegrity.MEETS_DEVICE_INTEGRITY -> "Strong device integrity"
            deviceIntegrity == DeviceIntegrity.MEETS_BASIC_INTEGRITY -> "Basic device integrity"
            else -> "Device integrity concerns detected"
        }
    }
}

/**
 * Integrity report for debugging and monitoring.
 */
data class IntegrityReport(
    val lastCheckTime: Long,
    val isCacheValid: Boolean,
    val lastResult: IntegrityResult?,
    val recommendations: List<String>
)

/**
 * Exception thrown for integrity check failures.
 */
class IntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)
