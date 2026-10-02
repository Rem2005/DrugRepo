Implementation Roadmap

Phase 0 — Project Setup


	[ ] Initialize Native Android (Kotlin) project with C++ JNI support.

	[ ] Configure CMake and import OpenCV binaries.

	[ ] Setup Room, SQLCipher, and WorkManager dependencies.

Phase 1 — Application Foundation & Local Persistence


	[ ] Implement Officer login/badge entry UI.

	[ ] Implement runtime permission requests (Camera, Location).

	[ ] Define Room entities matching the comprehensive auditable JSON schema.

	[ ] Implement SQLCipher encryption for the Room database.

	[ ] Implement DAO layer for inserting and retrieving hash-chained records.

	[ ] Write unit tests for database insertion, schema validation, and retrieval.

Phase 2 — Camera & UI Restraints


	[ ] Implement Camera2 API initialization with capability detection.

	[ ] Attempt to lock CONTROL_AWB_MODE_OFF and CONTROL_AE_MODE_OFF, applying safe fallbacks if the hardware rejects it.

	[ ] Configure ImageReader strictly for ImageFormat.YUV_420_888 (or fallback).

	[ ] Implement Reagent Kinetic Timer (disables shutter button until X seconds).

	[ ] Write instrumented tests for Camera2 capability detection and UI timer locking.

Phase 3 — Image Processing (C++ / OpenCV)


	[ ] Implement cv::aruco::detectMarkers to extract reference card corners.

	[ ] Implement perspective rectification via cv::findHomography and cv::warpPerspective.

	[ ] Implement Laplacian variance check to reject blurred frames prior to processing.

	[ ] Write JNI/C++ unit tests for marker detection and variance thresholds.

Phase 4 — Colour Analysis


	[ ] Implement Moore-Penrose pseudo-inverse math to derive the Color Correction Matrix (M).

	[ ] Ensure the CCM extraction algorithm utilizes a minimum of 3–4 reference swatches to prevent underdetermined matrix calculations.

	[ ] Apply the CCM to extract the calibrated RGB values of the reaction ROI.

	[ ] Implement the RGB to CIE Lab* color space conversion.

	[ ] Implement the full CIEDE2000 (ΔE_00) equation with all weighting and rotation terms.

	[ ] Write unit tests verifying CCM accuracy and ΔE_00 math against known static datasets.

Phase 5 — Decision Logic


	[ ] Define prototype decision-boundary mechanism.

	[ ] Do NOT invent chemical thresholds.

	[ ] Use mock/reference values only for UI/pipeline testing.

	[ ] Replace with validated reference dataset before claiming analytical validity.

	[ ] Write unit tests validating prototype thresholds.

Phase 6 — Cryptographic Integrity


	[ ] Implement Android Keystore KeyPair generator.

	[ ] Enforce capability checks: try setIsStrongBoxBacked(true); catch StrongBoxUnavailableException and fallback to TEE. Reject software-only execution.

	[ ] Parse OID 1.3.6.1.4.1.11129.2.1.17 in the X.509 certificate to verify StrongBox or TEE attestation.

	[ ] Implement strict JSON canonicalization (alphabetical keys, no whitespace, standard floats).

	[ ] Implement SHA-256 generation logic concatenating the canonical payload with previous_record_hash.

	[ ] Implement ECDSA signing of the final SHA-256 hash using the hardware private key.

	[ ] Implement a chain-verification routine to recalculate and validate the ledger integrity locally.

	[ ] Write unit tests verifying cryptographic signatures, hash canonicalization, and chain verification logic.

Phase 7 — BSA Certificate Generation


	[ ] Map database entities to a PDF payload designed to align with the BSA 2023 Section 63 Schedule format.

	[ ] Implement device identity mapping logic (Attestation ID / UUID + best-effort IMEI).

	[ ] Implement PDF rendering logic displaying the SHA-256 hash, device ID, and signature visibly.

	[ ] Write instrumented tests for PDF layout generation and metadata accuracy.

Phase 8 — Offline Sync


	[ ] Configure WorkManager for deferred background sync.

	[ ] Implement mTLS upload payload logic for when network connectivity is restored.

	[ ] Write tests to simulate network restoration and WorkManager execution.

