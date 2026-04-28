package com.vusense.sdk.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SecureMediaEngine provides headless camera capture using CameraX with secure buffering.
 * 
 * This class implements strict security policies:
 * - Never writes captured media to public storage
 * - Uses volatile RAM buffering or EncryptedFile for temporary storage
 * - Headless operation (no preview required)
 * - Hardware-level capture with minimal processing
 * - Automatic cleanup of temporary buffers
 */
@Singleton
class SecureMediaEngine @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val CAPTURE_TIMEOUT_MS = 5000L
        private const val MAX_IMAGE_SIZE_MB = 10 // Maximum image size in memory
        private const val JPEG_QUALITY = 95 // High quality for cryptographic hashing
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    
    private val mediaScope = CoroutineScope(Dispatchers.IO)
    private val captureExecutor = Executor { command -> command.run() }
    
    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()
    
    /**
     * Initializes the camera with headless configuration.
     * 
     * @param lifecycleOwner Lifecycle owner for camera operations
     * @param surfaceProvider Optional surface provider (can be null for headless)
     */
    suspend fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: SurfaceProvider? = null
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            if (!hasCameraPermission()) {
                return@withContext Result.failure(
                    MediaException("Camera permission not granted")
                )
            }
            
            _captureState.value = CaptureState.Initializing
            
            // Get camera provider
            val provider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider = provider
            
            // Configure image capture for high-quality photos
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(JPEG_QUALITY)
                .build()
            
            // Configure image analysis for real-time processing
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            // Unbind any existing use cases
            provider.unbindAll()
            
            // Select back camera by default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // Bind use cases to lifecycle
            val useCases = mutableListOf<UseCase>()
            imageCapture?.let { useCases.add(it) }
            imageAnalysis?.let { useCases.add(it) }
            
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )
            
            _captureState.value = CaptureState.Ready
            Result.success(Unit)
            
        } catch (e: Exception) {
            _captureState.value = CaptureState.Error(e.message ?: "Unknown error")
            Result.failure(MediaException("Failed to initialize camera", e))
        }
    }
    
    /**
     * Captures a high-quality image and returns it as secure byte array.
     * 
     * @param filename Optional filename for the capture
     * @return SecureMediaCapture with image bytes and metadata
     */
    suspend fun captureImage(filename: String? = null): SecureMediaCapture = withContext(Dispatchers.IO) {
        try {
            _captureState.value = CaptureState.Capturing
            
            val imageCapture = this@SecureMediaEngine.imageCapture
                ?: throw MediaException("Camera not initialized")
            
            val captureResult = suspendCancellableCoroutine<SecureMediaCapture> { continuation ->
                val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
                    ByteArrayOutputStream()
                ).build()
                
                imageCapture.takePicture(
                    captureExecutor,
                    object : OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val mediaCapture = processImageProxy(image, filename)
                                continuation.resume(mediaCapture)
                            } catch (e: Exception) {
                                continuation.resumeWithException(
                                    MediaException("Failed to process captured image", e)
                                )
                            } finally {
                                image.close()
                            }
                        }
                        
                        override fun onError(exception: ImageCaptureException) {
                            continuation.resumeWithException(
                                MediaException("Camera capture failed", exception)
                            )
                        }
                    }
                )
                
                continuation.invokeOnCancellation {
                    // Cancel capture if coroutine is cancelled
                }
            }
            
            _captureState.value = CaptureState.Ready
            captureResult
            
        } catch (e: Exception) {
            _captureState.value = CaptureState.Error(e.message ?: "Capture failed")
            throw MediaException("Failed to capture image", e)
        }
    }
    
    /**
     * Processes ImageProxy into SecureMediaCapture with volatile buffering.
     */
    private suspend fun processImageProxy(
        imageProxy: ImageProxy,
        filename: String?
    ): SecureMediaCapture = withContext(Dispatchers.IO) {
        
        // Convert image to JPEG byte array
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        
        // Validate image size
        val imageSizeMB = data.size / (1024 * 1024)
        if (imageSizeMB > MAX_IMAGE_SIZE_MB) {
            throw MediaException("Image too large: ${imageSizeMB}MB")
        }
        
        // Extract metadata
        val metadata = MediaMetadata(
            width = imageProxy.width,
            height = imageProxy.height,
            format = imageProxy.format,
            timestamp = imageProxy.imageInfo.timestamp,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            filename = filename ?: "capture_${System.currentTimeMillis()}.jpg"
        )
        
        SecureMediaCapture(
            data = data,
            metadata = metadata,
            captureTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Starts continuous image analysis for real-time processing.
     * 
     * @param analyzer Custom image analyzer function
     * @return Flow of analysis results
     */
    fun startImageAnalysis(
        analyzer: suspend (ImageProxy) -> AnalysisResult
    ): Flow<AnalysisResult> = channelFlow {
        val imageAnalysis = this@SecureMediaEngine.imageAnalysis
            ?: throw MediaException("Camera not initialized")
        
        imageAnalysis.setAnalyzer(Executor { command -> command.run() }) { imageProxy ->
            mediaScope.launch {
                try {
                    val result = analyzer(imageProxy)
                    send(result)
                } catch (e: Exception) {
                    // Log error but continue analysis
                } finally {
                    imageProxy.close()
                }
            }
        }
        
        awaitClose {
            imageAnalysis.clearAnalyzer()
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Gets camera capabilities for debugging.
     */
    suspend fun getCameraCapabilities(): CameraCapabilities? = withContext(Dispatchers.Main) {
        try {
            val camera = this@SecureMediaEngine.camera ?: return@withContext null
            val cameraInfo = camera.cameraInfo
            
            CameraCapabilities(
                hasFlash = cameraInfo.hasFlashUnit(),
                zoomRatio = cameraInfo.zoomState.value?.zoomRatio ?: 1.0f,
                exposureCompensation = cameraInfo.exposureState.exposureCompensationIndex,
                torchState = cameraInfo.torchState.value
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Enables/disables torch (flash light).
     */
    suspend fun setTorch(enabled: Boolean): Boolean = withContext(Dispatchers.Main) {
        try {
            val camera = this@SecureMediaEngine.camera ?: return@withContext false
            camera.cameraControl.enableTorch(enabled).get()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Sets zoom level.
     */
    suspend fun setZoomRatio(ratio: Float): Boolean = withContext(Dispatchers.Main) {
        try {
            val camera = this@SecureMediaEngine.camera ?: return@withContext false
            camera.cameraControl.setZoomRatio(ratio).get()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Releases camera resources.
     */
    fun releaseCamera() {
        mediaScope.launch {
            try {
                cameraProvider?.unbindAll()
                camera = null
                imageCapture = null
                imageAnalysis = null
                _captureState.value = CaptureState.Idle
            } catch (e: Exception) {
                _captureState.value = CaptureState.Error("Failed to release camera: ${e.message}")
            }
        }
    }
    
    /**
     * Checks if camera permission is granted.
     */
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        releaseCamera()
        mediaScope.cancel()
    }
}

/**
 * Secure media capture with volatile buffering.
 */
data class SecureMediaCapture(
    val data: ByteArray, // Volatile in-memory data
    val metadata: MediaMetadata,
    val captureTime: Long
) {
    /**
     * Returns SHA-256 hash of the media data for integrity verification.
     */
    fun getHash(): String {
        return data.sha256()
    }
    
    /**
     * Validates the capture data integrity.
     */
    fun isValid(): Boolean {
        return data.isNotEmpty() && 
                metadata.width > 0 && 
                metadata.height > 0 &&
                captureTime > 0
    }
    
    /**
     * Gets data size in human-readable format.
     */
    fun getDataSize(): String {
        val bytes = data.size.toDouble()
        val kb = bytes / 1024
        val mb = kb / 1024
        return when {
            mb >= 1 -> "%.2f MB".format(mb)
            kb >= 1 -> "%.2f KB".format(kb)
            else -> "$bytes bytes"
        }
    }
}

/**
 * Media metadata for captured images.
 */
data class MediaMetadata(
    val width: Int,
    val height: Int,
    val format: Int, // ImageFormat constant
    val timestamp: Long, // nanoseconds
    val rotationDegrees: Int,
    val filename: String
)

/**
 * Camera capabilities information.
 */
data class CameraCapabilities(
    val hasFlash: Boolean,
    val zoomRatio: Float,
    val exposureCompensation: Int,
    val torchState: Int // TorchState.OFF/ON
)

/**
 * Image analysis result.
 */
data class AnalysisResult(
    val timestamp: Long,
    val brightness: Float?, // Average brightness 0-1
    val motionDetected: Boolean,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Camera capture state.
 */
sealed class CaptureState {
    object Idle : CaptureState()
    object Initializing : CaptureState()
    object Ready : CaptureState()
    object Capturing : CaptureState()
    data class Error(val message: String) : CaptureState()
}

/**
 * Exception thrown for media capture failures.
 */
class MediaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Extension function to calculate SHA-256 hash.
 */
private fun ByteArray.sha256(): String {
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
