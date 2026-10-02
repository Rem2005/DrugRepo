Product Requirements Document (PRD)


	Product Overview
Problem Statement: Field officers currently interpret chemical presumptive drug tests (e.g., Marquis, Scott) visually. This introduces human subjectivity, rendering the evidence vulnerable during cross-examination. Furthermore, the lack of immediate, unalterable digital records complicates the mandatory Magistrate inventory process under Section 52A of the NDPS Act.
Goals: To create a native Android application that mathematically normalizes ambient lighting and computes colorimetric reactions using CIEDE2000. The app is designed to support evidentiary integrity and the documentation requirements relevant to BSA 2023 Section 63; legal admissibility must be validated by qualified legal authorities.

	# Product Requirements Document (PRD)

## 1. Product Overview
**Problem Statement:** Field officers currently interpret chemical presumptive drug tests (e.g., Marquis, Scott) visually. This introduces human subjectivity, rendering the evidence vulnerable during cross-examination. Furthermore, the lack of immediate, tamper-evident digital records complicates the mandatory Magistrate inventory process under Section 52A of the NDPS Act.

**Goals:** To create a native Android application that mathematically normalizes ambient lighting using a printed reference card and computes colorimetric reactions using CIEDE2000. The app is designed to support evidentiary integrity and the documentation requirements relevant to BSA 2023 Section 63. Legal admissibility must be validated by qualified legal authorities. All outputs are **presumptive** and require confirmatory laboratory testing.

## 2. Users
* **Field Officers / Narcotic Control Bureau (NCB) Agents:** Require a fast, offline, standardized and guided tool to establish probable cause and log evidence securely during chaotic field seizures. They are not assumed to be technical users, so the flow must be linear and forgiving.
* **Magistrates:** Require a tamper-evident "Pre-Magistrate Digital Inventory" to provide high technical assurance that the contraband presented for physical verification matches the recorded state of the substance at the time of seizure.

## 3. User Workflows
1. **App Initialization:** Officer opens the app (offline). The app verifies hardware key backing (StrongBox preferred, TEE fallback, software-only blocked) and checks that required permissions are granted.
2. **Officer Identification:** Officer enters a badge ID and unlocks with the device credential (biometric/PIN). The badge ID is stored in every record the officer creates.
3. **Test Selection:** Officer selects the reagent assay (e.g., Marquis).
4. **Camera Alignment:** The app displays an overlay wireframe. The officer aligns the smartphone camera with the physical printed reference card.
5. **Kinetic Timer:** A mandatory reagent-specific countdown timer (e.g., 5 seconds for Marquis; value is configuration) locks the shutter to prevent premature or oxidized captures.
6. **Capture & Validation:** The Camera2 API captures a frame. The app validates image sharpness (Laplacian variance), glare, and reference card geometry. On failure the officer is told why and may retry; the failed attempt is still sealed as an aborted record.
7. **Color Normalization & Analysis:** The OpenCV C++ pipeline extracts the fiducial swatches, derives and applies a Color Correction Matrix (CCM), extracts the reaction region color, and calculates the ΔE00 distance against reference data.
8. **Result Generation:** The app outputs POSITIVE, NEGATIVE, or INCONCLUSIVE (presumptive).
9. **Cryptographic Sealing:** A SHA-256 hash of the canonical auditable record (including the previous_record_hash) is generated and signed by the device's secure hardware.
10. **Local Storage:** The record is saved to the encrypted Room (SQLite) database.
11. **Certificate:** The officer can generate the PDF certificate for the record.
12. **Background Sync (Optional):** Upon network restoration, WorkManager defers syncing the chained records to the central server via mTLS.

## 4. Functional Requirements
* **FR-001 (Camera Control):** The app must use the Camera2 API as the primary camera interface. Manual exposure/white-balance controls should be used where the device exposes the required capabilities. The application must detect unsupported camera capabilities rather than assuming every Android device supports them, and must record in the auditable record what lock state was actually achieved.
* **FR-002 (Geometry Validation):** The app must detect 4 ArUco markers and rectify perspective using homography prior to accepting a capture.
* **FR-003 (Timer Lock):** The UI must enforce a reagent-specific kinetic countdown before allowing image capture.
* **FR-004 (Quality Gate):** The app must reject blurred or glare-saturated images prior to processing.
* **FR-005 (Color Pipeline):** The app must convert calibrated RGB values to CIE Lab* space and calculate perceptual distance via CIEDE2000.
* **FR-006 (Ledger Hashing):** The app must generate a SHA-256 hash incorporating the canonical payload and the previous_record_hash (specification: architecture.md §6).
* **FR-007 (Hardware Signature):** The app must sign the hash using an ECDSA secp256r1 key, attempting StrongBox first, with a TEE fallback. Software-only keys are strictly unsupported for production signing.
* **FR-008 (Certificate Generation):** The app must generate a PDF certificate containing statutory metadata fields relevant to BSA 2023 Section 63, Part A.
* **FR-009 (Offline Storage):** The app must store all records locally using Room and SQLCipher.
* **FR-010 (Chain Verification):** The app must provide an on-device routine that recomputes every record hash, checks every link and sequence number, verifies every signature, and reports the first broken record.
* **FR-011 (Device Identification):** The app must identify the device using an app-generated device ID and Keystore attestation data. IMEI is best-effort only, because Android 10+ restricts it for non-privileged apps.
* **FR-012 (Permissions):** The app must request camera and location permissions with a clear rationale. If location is unavailable, GPS is recorded as unavailable (not blocked) and flagged on the certificate.

## 5. Non-Functional Requirements
* **Offline Availability:**
  * Core workflow (capture, analyze, seal, store) = MUST work offline.
  * Synchronization = OPTIONAL, deferred functionality.
* **Data Integrity:** A single changed bit in any historical record must cause chain verification to fail for that record and every subsequent record.
* **Performance:** Image rectification and matrix algebra must execute on background threads via C++ JNI to prevent UI freezing.
* **Determinism / Testability:** The image pipeline and hashing must be deterministic, so the same input produces byte-identical output and can be re-verified by a third party.

## 6. Security Limits (stated honestly)
The hash chain is **tamper-evident, not tamper-proof**. On a rooted device an attacker could delete the newest records or rebuild an unsigned chain. Hardware-backed signatures and attestation make forged records detectable because the private key cannot be exported, but they cannot by themselves prevent deletion. Mitigations: sequence numbers (detect gaps), optional server sync that anchors the latest hash off-device, and periodic export of the chain head. These limits should be disclosed in the project presentation rather than hidden.

## 7. Out of Scope (Prototype vs Future Scope)
* **Out of Scope (Current):** Real-time video stream classification, external Bluetooth spectrophotometer hardware, fully automated lab spectral parsing, and claims of definitive chemical analysis.
* **Prototype Assumption:** Reagent target centroid Lab* values and decision boundaries are placeholder estimates. **TBD — requires validated reference data.**

## 8. Acceptance Criteria
* The system successfully identifies an ArUco marker card and rectifies it to a flat 500x500 matrix.
* The system gracefully degrades if manual controls (CONTROL_AWB_MODE_OFF, CONTROL_AE_MODE_OFF) are unsupported by the hardware, and records the actual lock state.
* The SHA-256 hash of Record N correctly incorporates the hash of Record N-1, and chain verification detects any modified, reordered, or removed record.
* The CIEDE2000 implementation matches the published reference test pairs within 1e-4.
* The generated PDF contains the device ID, key security level, GPS coordinates (or "unavailable"), SHA-256 hash, and digital signature, designed to support BSA 2023 Schedule formats.
