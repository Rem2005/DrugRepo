# Implementation Roadmap

**Rule:** each task is done only when its tests pass. Do not jump ahead. Phases are strictly ordered; if a task is blocked, stop and ask the user.


## Phase 0 — Project Setup
- [ ] Initialize native Android (Kotlin) project with C++ JNI support.
- [ ] Configure CMake and import OpenCV (with aruco module).
- [ ] Add Room, SQLCipher, WorkManager dependencies.
- [ ] Set up unit-test (JUnit) and instrumented-test (AndroidX) scaffolding, and native test harness for C++.
- [ ] Declare permissions (CAMERA, ACCESS_FINE_LOCATION) with rationale strings; no IMEI/phone-state permission.

## Phase 1 — Foundation & Persistence
- [ ] Define Room entities matching the auditable schema (architecture.md §7).
- [ ] Implement SQLCipher encryption; database passphrase protected by a Keystore-wrapped key.
- [ ] Implement DAO for inserting and reading chained records; block UPDATE/DELETE of sealed records at the DAO level.
- [ ] Tests: insert/read round trip; update and delete are rejected.

## Phase 2 — Camera & UI
- [ ] Officer entry screen (badge ID) and device-credential unlock.
- [ ] Camera2 init with capability detection (manual sensor, manual WB).
- [ ] Attempt CONTROL_AWB_MODE_OFF and CONTROL_AE_MODE_OFF with safe fallbacks; report achieved lock state.
- [ ] ImageReader for YUV_420_888 (documented fallback if unavailable).
- [ ] Reagent kinetic timer (shutter disabled until countdown ends).
- [ ] Tests: timer logic, capability-fallback paths with mocked camera characteristics.

## Phase 3 — Image Processing (C++/OpenCV)
- [ ] YUV to RGB and sRGB-to-linear conversion with unit tests.
- [ ] ArUco 4-marker detection.
- [ ] Homography rectification to 500x500.
- [ ] Laplacian-variance blur gate and L* glare gate (thresholds in config).
- [ ] Tests: fixture images (sharp, blurred, glare, missing marker).

## Phase 4 — Colour Analysis
- [ ] Swatch extraction; enforce minimum swatch count and conditioning check.
- [ ] Pseudo-inverse CCM derivation; apply to ROI.
- [ ] Linear RGB to XYZ (D65) to CIE Lab*.
- [ ] Full CIEDE2000 per architecture.md §4.
- [ ] Tests: CCM recovers a known synthetic transform; CIEDE2000 matches the 34 Sharma et al. (2005) pairs within 1e-4.

## Phase 5 — Decision Logic
- [ ] Prototype decision-boundary mechanism driven by a config table with an INCONCLUSIVE band.
- [ ] Do NOT invent chemical thresholds; mark all values MOCK.
- [ ] Display "MOCK DATA / presumptive only" in UI and PDF while reference data is unvalidated.
- [ ] Replace with validated reference data before claiming analytical validity.

## Phase 6 — Cryptographic Integrity
- [ ] Keystore ECDSA secp256r1 key generation with attestation challenge.
- [ ] Try StrongBox; catch StrongBoxUnavailableException and fall back to TEE; reject software-only.
- [ ] Parse OID 1.3.6.1.4.1.11129.2.1.17 and read attestationSecurityLevel.
- [ ] Canonical JSON (RFC 8785) serializer with fixed-precision decimal strings.
- [ ] record_hash per architecture.md §6, including genesis and sequence_number.
- [ ] ECDSA signing of record_hash.
- [ ] Chain verification routine (FR-010) and a "Verify chain" screen.
- [ ] Tests: known-answer hash vectors; flipping any bit in record K fails records K..N; signature verifies; deleted record detected via sequence gap.

## Phase 7 — BSA Certificate
- [ ] Map entities to a PDF payload designed to align with the BSA 2023 Section 63 Schedule format (legal review pending).
- [ ] Render device ID, attestation level, GPS (or "unavailable"), SHA-256 hash, and signature visibly; IMEI shown only if available.
- [ ] Include previous_record_hash and sequence number for traceability.

## Phase 8 — Offline Sync (Optional)
- [ ] WorkManager deferred sync job (network-constrained).
- [ ] mTLS upload of chained records; server returns acknowledgement of latest hash (off-device anchor).
- [ ] Tests: sync never blocks or alters the local chain.
