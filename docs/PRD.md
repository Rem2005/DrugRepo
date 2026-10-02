Product Requirements Document (PRD)


	Product Overview
Problem Statement: Field officers currently interpret chemical presumptive drug tests (e.g., Marquis, Scott) visually. This introduces human subjectivity, rendering the evidence vulnerable during cross-examination. Furthermore, the lack of immediate, unalterable digital records complicates the mandatory Magistrate inventory process under Section 52A of the NDPS Act.
Goals: To create a native Android application that mathematically normalizes ambient lighting and computes colorimetric reactions using CIEDE2000. The app is designed to support evidentiary integrity and the documentation requirements relevant to BSA 2023 Section 63; legal admissibility must be validated by qualified legal authorities.

	Users

	Field Officers / Narcotic Control Bureau (NCB) Agents: Require a fast, offline, standardized and guided tool to establish probable cause and log evidence securely during chaotic field seizures.

	Magistrates: Require an immutable "Pre-Magistrate Digital Inventory" to provide high technical assurance that the contraband presented for physical verification perfectly matches the state of the substance at the time of seizure.

	User Workflows

	App Initialization: Officer opens the app (offline) and authenticates (badge/login entry). The app requests necessary runtime permissions (Camera, Location) and verifies hardware backing (StrongBox preferred, TEE fallback).

	Test Selection: Officer selects the reagent assay (e.g., Marquis).

	Camera Alignment: The app displays an overlay wireframe. The officer aligns the smartphone camera with the physical printed reference card.

	Kinetic Timer: A mandatory countdown timer (e.g., 5 seconds for Marquis) locks the shutter to prevent premature or oxidized captures.

	Capture & Validation: The Camera2 API captures a frame. The app validates image sharpness (Laplacian variance) and reference card geometry.

	Color Normalization & Analysis: The OpenCV C++ pipeline extracts the fiducial swatches, applies a Color Correction Matrix (CCM), extracts the reaction region color, and calculates the ΔE_00 distance against known reference data.

	Result Generation: The app outputs POSITIVE, NEGATIVE, or INCONCLUSIVE.

	Cryptographic Sealing: A SHA-256 hash of the auditable record (including the previous_record_hash) is generated and signed by the device's secure hardware.

	Local Storage: The record is saved to the encrypted Room (SQLite) database.

	Background Sync (Optional): Upon network restoration, WorkManager defers syncing the chained records to the central server via mTLS.

	Functional Requirements

	FR-001 (Camera Control): The app must use the Camera2 API as the primary camera interface. Manual exposure/white-balance controls should be used where the device exposes the required capabilities. The application must detect unsupported camera capabilities rather than assuming every Android device supports them.

	FR-002 (Geometry Validation): The app must detect 4 ArUco markers and rectify perspective using homography prior to capture.

	FR-003 (Timer Lock): The UI must enforce a reagent-specific kinetic countdown before allowing image capture.

	FR-004 (Quality Gate): The app must reject blurred or glare-saturated images prior to processing.

	FR-005 (Color Pipeline): The app must convert calibrated RGB values to CIE Lab* space and calculate perceptual distance via CIEDE2000.

	FR-006 (Ledger Hashing): The app must generate a SHA-256 hash incorporating the canonicalized payload and the previous_record_hash.

	FR-007 (Hardware Signature): The app must sign the hash using an ECDSA secp256r1 key, attempting StrongBox first, with a TEE fallback. Software-only keys are strictly unsupported for production signing.

	FR-008 (Certificate Generation): The app must generate a PDF certificate containing statutory metadata fields relevant to BSA 2023 Section 63, Part A. Due to Android 10+ restrictions on IMEI access for non-privileged apps, device identity must rely on Keystore attestation ID or an app-generated UUID, with IMEI treated as strictly best-effort.

	FR-009 (Offline Storage): The app must store all records locally using Room and SQLCipher.

	Non-Functional Requirements

	Offline Availability:


	Core workflow (capture, analyze, seal, store) = MUST work offline.

	Synchronization = OPTIONAL, deferred functionality.

	Data Integrity: A single flipped bit in historical data must mathematically break the entire subsequent hash chain.

	Performance: Image rectification and matrix algebra must execute on background threads via C++ JNI to prevent UI freezing.

	Out of Scope (Prototype vs Future Scope)

	Out of Scope (Current): Real-time video stream classification, external Bluetooth spectrophotometer hardware, fully automated lab spectral parsing, and claims of definitive chemical analysis.

	Prototype Assumption: Reagent target centroid Lab* values and decision boundaries are placeholder estimates. TBD — requires validated reference data.

	Acceptance Criteria

	The system successfully identifies an ArUco marker card and rectifies it to a flat 500x500 matrix.

	The system gracefully degrades if manual controls (CONTROL_AWB_MODE_OFF, CONTROL_AE_MODE_OFF) are unsupported by the hardware.

	The SHA-256 hash of Record N correctly incorporates the hash of Record N-1 using strict canonicalization.

	The generated PDF contains the device identifiers (Keystore attestation ID / app-generated UUID, and best-effort IMEI), GPS coordinates, SHA-256 hash, and digital signature designed to support BSA 2023 Schedule formats.
