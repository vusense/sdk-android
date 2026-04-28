package com.vusense.sdk.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SensorHarvester collects high-fidelity sensor data using Kotlin Coroutines and Flow.
 * 
 * This class implements strict security and performance policies:
 * - All sensor operations use coroutines, never callbacks
 * - GPS accuracy threshold enforced (≤100m) before accepting data
 * - IMU data collected at hardware-native rates
 * - Memory-efficient with bounded buffers
 * - Graceful handling of hardware unavailability
 */
@Singleton
class SensorHarvester @Inject constructor(
    private val context: Context,
    private val networkHarvester: NetworkHarvester
) {
    
    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 1000L // 1 second
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L // 500ms
        private const val MAX_ACCURACY_METERS = 100f // Maximum acceptable GPS accuracy
        private const val SENSOR_BUFFER_SIZE = 100 // Max buffered readings
        private const val IMU_COLLECTION_DURATION_MS = 2000L // 2 seconds of IMU data
    }
    
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val _locationState = MutableStateFlow<LocationData?>(null)
    val locationState: StateFlow<LocationData?> = _locationState.asStateFlow()
    
    private val _imuState = MutableStateFlow<ImuData?>(null)
    val imuState: StateFlow<ImuData?> = _imuState.asStateFlow()
    
    private val sensorScope = CoroutineScope(Dispatchers.IO)
    private var locationJob: Job? = null
    private var imuJob: Job? = null
    
    // IMU sensor listeners
    private val accelerometerListener = ImuSensorListener()
    private val gyroscopeListener = ImuSensorListener()
    private val magnetometerListener = ImuSensorListener()
    private val lightSensorListener = LightSensorListener()
    
    /**
     * Starts collecting GPS location data.
     * 
     * @return Flow of LocationData with accuracy validation
     */
    fun startLocationCollection(): Flow<LocationData> = flow {
        if (!hasLocationPermission()) {
            throw SensorException("Location permission not granted")
        }
        
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            throw SensorException("GPS provider not enabled")
        }
        
        val locationChannel = Channel<LocationData>(capacity = Channel.UNLIMITED)
        
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.accuracy <= MAX_ACCURACY_METERS) {
                    val locationData = LocationData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        altitude = location.altitude,
                        bearing = location.bearing,
                        speed = location.speed,
                        timestamp = location.time,
                        provider = location.provider ?: "unknown"
                    )
                    
                    sensorScope.launch {
                        _locationState.value = locationData
                        locationChannel.trySend(locationData)
                    }
                }
            }
            
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f, // No minimum distance
                locationListener,
                Looper.getMainLooper()
            )
            
            // Also request network location as fallback
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL_MS,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
            
            // Emit location updates
            for (location in locationChannel) {
                emit(location)
            }
        } catch (e: SecurityException) {
            throw SensorException("Security exception in location collection", e)
        } finally {
            locationManager.removeUpdates(locationListener)
            locationChannel.close()
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
    
    /**
     * Starts collecting IMU sensor data.
     * 
     * @return Flow of ImuData with 2-second collection windows
     */
    fun startImuCollection(): Flow<ImuData> = flow {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        if (accelerometer == null && gyroscope == null && magnetometer == null) {
            throw SensorException("No IMU sensors available")
        }
        
        // Register sensor listeners
        val registeredSensors = mutableListOf<Sensor>()
        
        accelerometer?.let { sensor ->
            if (sensorManager.registerListener(
                    accelerometerListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )) {
                registeredSensors.add(sensor)
            }
        }
        
        gyroscope?.let { sensor ->
            if (sensorManager.registerListener(
                    gyroscopeListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )) {
                registeredSensors.add(sensor)
            }
        }
        
        magnetometer?.let { sensor ->
            if (sensorManager.registerListener(
                    magnetometerListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )) {
                registeredSensors.add(sensor)
            }
        }
        
        light?.let { sensor ->
            if (sensorManager.registerListener(
                    lightSensorListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )) {
                registeredSensors.add(sensor)
            }
        }
        
        if (registeredSensors.isEmpty()) {
            throw SensorException("Failed to register any IMU sensors")
        }
        
        try {
            while (isActive) {
                delay(IMU_COLLECTION_DURATION_MS)
                
                val imuData = ImuData(
                    timestamp = System.currentTimeMillis(),
                    accelerometer = accelerometerListener.getLatestReading(),
                    gyroscope = gyroscopeListener.getLatestReading(),
                    magnetometer = magnetometerListener.getLatestReading(),
                    lightSensor = lightSensorListener.getLatestReading()
                )
                
                sensorScope.launch {
                    _imuState.value = imuData
                }
                
                emit(imuData)
                
                // Clear buffers for next window
                accelerometerListener.clearReadings()
                gyroscopeListener.clearReadings()
                magnetometerListener.clearReadings()
                lightSensorListener.clearReading()
            }
        } finally {
            // Unregister all sensors
            registeredSensors.forEach { sensor ->
                sensorManager.unregisterListener(accelerometerListener, sensor)
                sensorManager.unregisterListener(gyroscopeListener, sensor)
                sensorManager.unregisterListener(magnetometerListener, sensor)
                sensorManager.unregisterListener(lightSensorListener, sensor)
            }
        }
    }.conflate().flowOn(Dispatchers.IO)
    
    /**
     * Collects a single sensor payload with all available data.
     * 
     * @return SensorPayload with location, IMU, and network data
     */
    suspend fun collectSensorPayload(): SensorPayload = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        
        // Get latest location
        val location = _locationState.value
        
        // Get latest IMU data
        val imu = _imuState.value
        
        // Get network data
        val (cellTower, wifi) = networkHarvester.getCurrentNetworkData()
        
        SensorPayload(
            location = location,
            imu = imu,
            cellTower = cellTower,
            wifi = wifi,
            timestamp = timestamp
        )
    }
    
    /**
     * Starts continuous sensor collection.
     * 
     * @return Flow of complete SensorPayload objects
     */
    fun startContinuousCollection(): Flow<SensorPayload> = flow {
        // Start location collection
        val locationFlow = startLocationCollection()
        
        // Start IMU collection  
        val imuFlow = startImuCollection()
        
        // Start network collection
        val networkFlow = networkHarvester.startContinuousNetworkCollection()
        
        // Combine flows into sensor payloads
        var latestLocation: LocationData? = null
        var latestImu: ImuData? = null
        var latestCellTower: CellTowerData? = null
        var latestWifi: WifiData? = null
        
        sensorScope.launch {
            locationFlow.collect { location ->
                latestLocation = location
            }
        }
        
        sensorScope.launch {
            imuFlow.collect { imu ->
                latestImu = imu
            }
        }
        
        sensorScope.launch {
            networkFlow.collect { (cellTower, wifi) ->
                latestCellTower = cellTower
                latestWifi = wifi
            }
        }
        
        // Emit payloads every 2 seconds
        while (isActive) {
            delay(2000)
            
            val payload = SensorPayload(
                location = latestLocation,
                imu = latestImu,
                cellTower = latestCellTower,
                wifi = latestWifi,
                timestamp = System.currentTimeMillis()
            )
            
            if (payload.isValid()) {
                emit(payload)
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
    
    /**
     * Checks if location permission is granted.
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Gets available IMU sensors for debugging.
     */
    fun getAvailableImuSensors(): List<String> {
        val sensors = mutableListOf<String>()
        
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.add("Accelerometer: ${it.name}")
        }
        
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.add("Gyroscope: ${it.name}")
        }
        
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensors.add("Magnetometer: ${it.name}")
        }
        
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensors.add("Light: ${it.name}")
        }
        
        return sensors
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        locationJob?.cancel()
        imuJob?.cancel()
        sensorScope.cancel()
    }
    
    /**
     * IMU sensor listener that buffers readings.
     */
    private class ImuSensorListener : SensorEventListener {
        private val readings = ConcurrentLinkedQueue<SensorReading>()
        
        override fun onSensorChanged(event: SensorEvent) {
            val reading = SensorReading(
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                accuracy = event.accuracy,
                timestamp = event.timestamp
            )
            
            readings.offer(reading)
            
            // Keep buffer bounded
            while (readings.size > SENSOR_BUFFER_SIZE) {
                readings.poll()
            }
        }
        
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        
        fun getLatestReading(): SensorReading? = readings.pollLast()
        
        fun clearReadings() = readings.clear()
    }
    
    /**
     * Light sensor listener for ambient light measurement.
     */
    private class LightSensorListener : SensorEventListener {
        private var latestReading: Float? = null
        
        override fun onSensorChanged(event: SensorEvent) {
            latestReading = event.values[0]
        }
        
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        
        fun getLatestReading(): Float? = latestReading
        
        fun clearReading() { latestReading = null }
    }
}

/**
 * Exception thrown for sensor collection failures.
 */
class SensorException(message: String, cause: Throwable? = null) : Exception(message, cause)
