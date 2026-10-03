# Implementation Roadmap

**Rule:** each task is done only when its tests pass. Do not jump ahead. Phases are strictly ordered; if a task is blocked, stop and ask the user.


## Phase 0 — Project Setup
- [x] Initialize native Android (Kotlin) project with C++ JNI support.
- [x] Configure CMake and import OpenCV (with aruco module).
- [ ] Add Room, SQLCipher, WorkManager dependencies.
- [x] Set up unit-test (JUnit) and instrumented-test (AndroidX) scaffolding, and native test harness for C++.
- [x] Declare permissions (CAMERA + ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION) with rationale strings; no IMEI/phone-state permission. ACCESS_COARSE_LOCATION is mandatory alongside ACCESS_FINE_LOCATION: on targetSdk 31+ the platform ignores a FINE-only request and grants neither.

## Phase 1 — Foundation & Persistence
- [ ] Define Room entities matching the auditable schema (architecture.md §7).
- [x] Implement SQLCipher encryption; database passphrase protected by a Keystore-wrapped key.
- [x] Implement DAO for inserting and reading chained records; block UPDATE/DELETE of sealed records at the DAO level.
- [x] Tests: insert/read round trip; update and delete are rejected.

## Phase 2 — Camera & UI
- [ ] Officer entry screen (badge ID) and device-credential unlock.
- [ ] Camera2 init with capability detection (manual sensor, manual WB).
- [ ] Attempt CONTROL_AWB_MODE_OFF and CONTROL_AE_MODE_OFF with safe fallbacks; report achieved lock state.
- [ ] ImageReader for YUV_420_888 (documented fallback if unavailable).
- [ ] Reagent kinetic timer (shutter disabled until countdown ends).
- [ ] Tests: timer logic, capability-fallback paths with mocked camera characteristics.

## Phase 3 — Image Processing (C++/OpenCV)
- [ ] YUV to RGB and sRGB-to-linear conversion with unit tests.
- [x] ArUco detection over the captured JPEG against the profile's dictionary (`DICT_4X4_50`), with explicit failure statuses; no centre fallback — see `docs/CV_PIPELINE.md`.
- [x] Homography rectification to the profile's 500x500 card; regions reported in rectified card coordinates and required to lie inside the card without overlapping.
- [x] Laplacian variance and glare fraction reported as measurements (thresholds deliberately not applied — no validated thresholds exist).
- [x] Tests: fixture images (markers present/absent, wrong card/partial marker set, invalid homography, invalid input, byte-identical determinism), asserted both in the native harness and across JNI.
- [x] Real capture end-to-end on device: a single physical capture was decoded and analysed through `RealCvTestAnalyzer` and the full result screen. It honestly reported `kCvStatusNoMarkers` (no reference card in frame) and recorded no colour values. Confirms the no-centre-fallback path; it does not confirm colour measurement on a real card.

## Phase 4 — Colour Analysis
- [x] Swatch extraction (six profile swatches, measured separately then averaged with equal weight); minimum count and conditioning check enforced before any CCM is fitted.
- [x] CCM solve in linear light, `M = C_ideal . C_obs^T (C_obs . C_obs^T)^-1`, guarded by normalized covariance isotropy and a maximum per-channel gain; fails closed rather than fitting a matrix to rank-deficient or collinear swatches.
- [x] Linear RGB to XYZ (D65) to CIE L*a*b*.
- [x] Full CIEDE2000 per architecture.md §4 (kL = kC = kH = 1, Sharma formulation).
- [x] CCM currently inert: the only profile is PROVISIONAL and carries no authoritative patch colours, so no matrix is applied. Applying one fitted to invented targets would launder placeholder data into a "calibrated" measurement.
- [x] Tests: CCM recovers a known synthetic transform and is deterministic; all 34 Sharma et al. (2005) supplementary pairs match within 1e-4, with symmetry, zero-distance, sRGB-companding and Lab-anchor checks.
- [ ] Ship the calibrated path against a profile that has authoritative swatch colours. Blocked on kit data.

## Phase 5 — Decision Logic
- [x] Decision-boundary mechanism driven by a config table in the reference profile, with a genuine INCONCLUSIVE band (currently `<= 2.0` positive, `>= 5.0` negative, between is inconclusive).
- [x] No chemical thresholds invented; the shipped boundary values are placeholders, labelled MOCK, and every one of them lives in `reference_profile.cpp` so replacing them is a one-file change.
- [x] Presumptive-only labelling propagates from `ProfileValidation::kUnvalidated` into `AnalysisResult.demo`, the analyzer message, the result screen badge, and the record's `reference_data_version`.
- [ ] Enforce "no authority claim" in the PDF certificate. Blocked on Phase 7.
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

## Phase 8 — Offline Sync 
- [ ] WorkManager deferred sync job (network-constrained).
- [ ] mTLS upload of chained records; server returns acknowledgement of latest hash (off-device anchor).
- [ ] Tests: sync never blocks or alters the local chain.
Offline Searchable Log: Every test is stored on the device, works without internet, and can be searched and exported as a PDF certificate.

## Phase 9 — CV Hardening (not in the original roadmap)

- [x] Remove the prototype centre fallback. It produced plausible numbers from frames with no card in them.
- [x] Reject extra foreign markers even when all four expected markers are present. An overlapping second card can displace a detection, and a required-set check alone would report the wrong card.
- [ ] Implement `ComparisonTarget::kProfileReferenceLab`. Only `kMeasuredReferencePatches` works today; an authoritative kit profile will need the comparison-target path.
- [ ] Decide what happens when a profile *requests* calibration but the CCM solve fails. Today the failure is recorded and the uncorrected measurement continues; with authoritative data this may need to abort instead.
- [x] Fix a double-companding defect on the calibrated path: the CCM acts in linear light, but its output was being fed back through the sRGB transfer function by the Lab conversion. Reported corrected channels are now re-encoded exactly once, and a test runs the calibrated branch against a profile with authoritative swatch colours so the branch is no longer untested.
- [x] Fix the CCM contract: a refused solve used to leave a half-written matrix in the caller's array. It now solves into a local and copies out only on success.
- [x] Reject a NaN in the CCM's ideal targets, not just in the observations.
- [ ] Quality metrics (Laplacian variance, glare) are measured and reported but never gated. Thresholding needs validated thresholds.
