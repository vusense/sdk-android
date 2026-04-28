package com.vusense.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vusense.app.databinding.ActivityMainBinding
import com.vusense.sdk.VusenseClient
import com.vusense.sdk.VusenseConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VusenseSampleApp"
        private const val TEST_USER_ID = "sample-user-123"
        private const val TEST_POLICY_ID = "sample-policy-456"
    }

    private lateinit var binding: ActivityMainBinding
    private var vusenseClient: VusenseClient? = null
    private var isClientInitialized = false

    // Permission launcher for camera and location
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeVusenseClient()
        } else {
            showPermissionDeniedMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inflate the layout using ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up capture button listener
        binding.btnCapture.setOnClickListener {
            handleCaptureClick()
        }

        // Check and request permissions on startup
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initializeVusenseClient()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initializeVusenseClient() {
        if (isClientInitialized) {
            Log.d(TAG, "VusenseClient already initialized")
            return
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Initializing VusenseClient...")
                
                // Create test configuration
                val config = VusenseConfig(
                    userId = TEST_USER_ID,
                    policyId = TEST_POLICY_ID,
                    requireIntegrityCheck = true,
                    validatePayload = true,
                    timeoutMs = 45000L
                )

                // Note: In a real implementation, we would inject dependencies
                // For now, this is a placeholder showing the intended usage
                showStatusMessage("SDK initialization placeholder - requires dependency injection setup")
                
                // TODO: Initialize actual VusenseClient with proper DI
                // vusenseClient = // Get from DI container or create manually
                // val result = vusenseClient?.initialize(
                //     context = applicationContext,
                //     config = config,
                //     lifecycleOwner = this@MainActivity,
                //     surfaceProvider = binding.previewView.surfaceProvider
                // )
                
                // if (result?.isSuccess == true) {
                //     isClientInitialized = true
                //     showStatusMessage("VusenseClient initialized successfully")
                //     binding.btnCapture.isEnabled = true
                // } else {
                //     showErrorMessage("Failed to initialize VusenseClient: ${result?.exceptionOrNull()?.message}")
                // }

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing VusenseClient", e)
                showErrorMessage("Initialization error: ${e.message}")
            }
        }
    }

    private fun handleCaptureClick() {
        if (!isClientInitialized) {
            showErrorMessage("VusenseClient not initialized")
            return
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Starting attestation capture...")
                showStatusMessage("Starting capture...")
                
                // Disable button during capture
                binding.btnCapture.isEnabled = false
                
                // TODO: Execute actual capture
                // val attestationSignature = vusenseClient?.captureAttestation()
                
                // if (attestationSignature != null) {
                //     Log.d(TAG, "Capture completed successfully")
                //     showStatusMessage("Capture completed! Signature ready for broadcast.")
                //     
                //     // In a real app, you would now send this to your server
                //     // sendToServer(attestationSignature)
                // } else {
                //     showErrorMessage("Capture failed")
                // }

                // Placeholder for demonstration
                showStatusMessage("Capture placeholder - requires full SDK implementation")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during capture", e)
                showErrorMessage("Capture error: ${e.message}")
            } finally {
                // Re-enable button
                binding.btnCapture.isEnabled = true
            }
        }
    }

    private fun showPermissionDeniedMessage() {
        showErrorMessage("Camera and location permissions are required for this app to function.")
        binding.btnCapture.isEnabled = false
    }

    private fun showStatusMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.i(TAG, message)
    }

    private fun showErrorMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Clean up VusenseClient resources if needed
        // vusenseClient?.cleanup()
        vusenseClient = null
        isClientInitialized = false
    }
}
