package com.vusense.sdk.di

import android.content.Context
import com.vusense.sdk.orchestration.AttestationOrchestrator
import com.vusense.sdk.payload.PayloadMapper
import com.vusense.sdk.security.CryptoEnclave
import com.vusense.sdk.security.PlayIntegrityChecker
import com.vusense.sdk.sensors.SensorHarvester
import com.vusense.sdk.sensors.NetworkHarvester
import com.vusense.sdk.media.SecureMediaEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.mockk.mockk
import javax.inject.Singleton

/**
 * Test Hilt module for Vusense SDK that provides mock implementations.
 * 
 * This module replaces all real hardware-dependent components with mocks,
 * enabling reliable unit testing without requiring actual hardware:
 * - Mock GPS, IMU, and Network sensors
 * - Mock CameraX capture
 * - Mock Android Keystore operations
 * - Mock Play Integrity API responses
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [VusenseModule::class]
)
object TestVusenseModule {

    @Provides
    @Singleton
    fun provideMockNetworkHarvester(): NetworkHarvester {
        return mockk(relaxed = true)
    }

    @Provides
    @Singleton
    fun provideMockSensorHarvester(): SensorHarvester {
        return mockk(relaxed = true)
    }

    @Provides
    @Singleton
    fun provideMockCryptoEnclave(): CryptoEnclave {
        return mockk(relaxed = true)
    }

    @Provides
    @Singleton
    fun provideMockSecureMediaEngine(): SecureMediaEngine {
        return mockk(relaxed = true)
    }

    @Provides
    @Singleton
    fun provideMockPlayIntegrityChecker(): PlayIntegrityChecker {
        return mockk(relaxed = true)
    }

    @Provides
    @Singleton
    fun provideMockPayloadMapper(): PayloadMapper {
        return mockk(relaxed = true)
    }

    @Provides
    @Singleton
    fun provideMockAttestationOrchestrator(): AttestationOrchestrator {
        return mockk(relaxed = true)
    }
}
