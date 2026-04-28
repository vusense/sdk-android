# Vusense Android SDK: Implementation Milestones

## Phase 1: Project Initialization & Skeleton
- [ ] Initialize Android Gradle project (Library Module).
- [ ] Configure `minSdk 26` and target SDK.
- [ ] Add Kotlin Coroutines, CameraX, and AndroidX Security Crypto dependencies.
- [ ] Integrate ProofMode Android library.

## Phase 2: Core Engines - Hardware Interfaces
- [ ] **CryptoEnclave:** Implement Android Keystore KeyPair generation and hardware backing validation.
- [ ] **SensorHarvester:** Implement Coroutine Flows for `LocationManager` (GPS) and `SensorManager` (IMU).
- [ ] **SensorHarvester:** Implement Coroutine Flows for `TelephonyManager` and `WifiManager` (Network taxonomy).
- [ ] **SecureMediaEngine:** Configure headless CameraX session.
- [ ] **SecureMediaEngine:** Implement volatile RAM buffering or `EncryptedFile` wrapper for raw media capture.

## Phase 3: Orchestration & Payload Assembly
- [ ] **AttestationOrchestrator:** Build the `StateFlow` state machine for the synchronous capture pipeline.
- [ ] **ProofMode Integration:** Connect the `SecureMediaEngine` hash and `SensorHarvester` data to the ProofMode PGP generator.
- [ ] **Payload Mapper:** Build the mapper to translate the ProofMode output into the `attestation_schema.json` format from `shared-protocol`.

## Phase 4: Security Hardening & The Facade
- [ ] Integrate Google Play Integrity API for root/tamper detection.
- [ ] **VusenseClient:** Expose the public API facade for the host application to pass `VusenseConfig` and `SurfaceProvider`.
- [ ] **Validation:** Add strict local schema validation to ensure the generated payload strictly matches `attestation_schema.json` before broadcasting.

## Phase 5: Testing & Release
- [ ] Write integration tests mocking hardware responses.
- [ ] Verify zero-leak policy (ensure Logcat is completely stripped of PII/keys).
- [ ] Build and export local AAR for testing against a host application.
