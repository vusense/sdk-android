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
import javax.inject.Singleton

/**
 * Hilt dependency injection module for Vusense SDK.
 * 
 * This module provides all the core dependencies needed for the SDK:
 * - Hardware interfaces (sensors, camera, crypto)
 * - Orchestration and payload processing
 * - Security and integrity checking
 * 
 * In test environments, this module can be overridden with test doubles
 * to enable mocking of hardware responses.
 */
@Module
@InstallIn(SingletonComponent::class)
object VusenseModule {

    @Provides
    @Singleton
    fun provideNetworkHarvester(@ApplicationContext context: Context): NetworkHarvester {
        return NetworkHarvester(context)
    }

    @Provides
    @Singleton
    fun provideSensorHarvester(
        @ApplicationContext context: Context,
        networkHarvester: NetworkHarvester
    ): SensorHarvester {
        return SensorHarvester(context, networkHarvester)
    }

    @Provides
    @Singleton
    fun provideCryptoEnclave(@ApplicationContext context: Context): CryptoEnclave {
        return CryptoEnclave(context)
    }

    @Provides
    @Singleton
    fun provideSecureMediaEngine(@ApplicationContext context: Context): SecureMediaEngine {
        return SecureMediaEngine(context)
    }

    @Provides
    @Singleton
    fun providePlayIntegrityChecker(@ApplicationContext context: Context): PlayIntegrityChecker {
        return PlayIntegrityChecker(context)
    }

    @Provides
    @Singleton
    fun providePayloadMapper(): PayloadMapper {
        return PayloadMapper()
    }

    @Provides
    @Singleton
    fun provideAttestationOrchestrator(
        mediaEngine: SecureMediaEngine,
        sensorHarvester: SensorHarvester,
        cryptoEnclave: CryptoEnclave
    ): AttestationOrchestrator {
        return AttestationOrchestrator(mediaEngine, sensorHarvester, cryptoEnclave)
    }
}
