# System Architecture & Technical Blueprint

## 1. Architecture Overview
The system is a highly deterministic, native Android offline application. It captures uncompressed sensor data, processes it via an embedded C++ computer vision pipeline, makes analytical decisions locally, and cryptographically chains the auditable results into a tamper-evident, encrypted local database before utilizing optional, deferred background synchronization. Every stage records what it actually did (for example, whether AWB/AE lock was achieved) so that the record describes reality rather than intent.

## 2. Technology Stack
- Platform: Native Android (Kotlin)
- Camera: Camera2 API (with capability detection for manual exposure/white-balance)
- Computer Vision: OpenCV (C++ via JNI)
- Persistence: Room (SQLite) + SQLCipher
- Cryptography: Android Keystore (StrongBox / TEE)
- Sync: WorkManager (mTLS), optional

## 3. Application Layers / Modules
* **UI Layer (Kotlin):** Camera viewfinder, geometric overlay, kinetic timer, officer badge entry and unlock, result display, chain-verification screen, and certificate export.
* **Sensor Layer (Kotlin):** Camera2 subsystem that attempts to lock AWB/AE and routes YUV_420_888 frames where device capabilities allow, then reports the lock state actually achieved.
* **Image Analysis (C++ / OpenCV):** Homography, Moore-Penrose pseudo-inverse for color correction, Lab* conversion, and CIEDE2000 algorithms. Exposed to Kotlin through a narrow JNI interface that takes a frame buffer and returns a plain result struct.
* **Data / Persistence (Kotlin / Room):** Entity mapping and encrypted SQLite storage. Sealed records are insert-only.
* **Security / Integrity (Kotlin / Keystore):** Hardware attestation, ECDSA signing, canonical serialization, SHA-256 chain calculation, and chain verification.
* **Sync (Kotlin / WorkManager, optional):** Deferred mTLS upload; never mutates local records.

## 4. Computer Vision Pipeline
1. **Frame conversion:** YUV_420_888 to RGB with a fixed, documented conversion; then sRGB to **linear RGB** (inverse gamma) before any matrix math.
2. **Image validation:** Laplacian variance for blur; L* thresholding for glare/saturation. Threshold values are configuration, not hard-coded science.
3. **Region detection:** detect the 4 corner ArUco markers (OpenCV aruco module; use the ArucoDetector API on OpenCV 4.7+), then cv::findHomography and cv::warpPerspective to a 500x500 image.
4. **Color normalization:** Extract observed swatch colors as C_obs (3 x N) and known ideal values C_ideal (3 x N).
   - **N must be at least 3** for a 3x3 matrix; use **at least 6 distinct swatches** (include neutral gray/white/black patches) for stability. If N is below the minimum, abort with ABORTED_GEOMETRY and do not apply a CCM.
   - M = C_ideal · C_obsᵀ · (C_obs · C_obsᵀ)⁻¹
   - Check conditioning of (C_obs · C_obsᵀ); if near-singular, abort.
   - C_calibrated = M · C_measured, clamped to [0,1], then linear RGB to XYZ (D65) to CIE Lab*.
5. **Color comparison:** CIEDE2000 (below) between sample Lab* and each reference centroid.
6. **Decision logic:** Maps ΔE00 to POSITIVE / NEGATIVE / INCONCLUSIVE via a configurable boundary table. All values are **Prototype Assumptions (MOCK)** until validated reference data exists. Always include an INCONCLUSIVE band.

### CIEDE2000 (kL = kC = kH = 1)
Inputs: (L1,a1,b1) reference, (L2,a2,b2) sample. Angles in degrees; all arithmetic in double precision.

```
C1 = sqrt(a1² + b1²);  C2 = sqrt(a2² + b2²)
Cbar = (C1 + C2) / 2
G = 0.5 * (1 - sqrt(Cbar⁷ / (Cbar⁷ + 25⁷)))
a1' = (1+G)*a1;  a2' = (1+G)*a2
C1' = sqrt(a1'² + b1²);  C2' = sqrt(a2'² + b2²)
h1' = atan2(b1, a1') in [0,360)   (0 if b1 = a1' = 0);  h2' likewise

dL' = L2 - L1
dC' = C2' - C1'
dh' = 0                      if C1'*C2' = 0
    = h2' - h1'              if |h2' - h1'| <= 180
    = h2' - h1' - 360        if h2' - h1' > 180
    = h2' - h1' + 360        if h2' - h1' < -180
dH' = 2 * sqrt(C1'*C2') * sin(dh'/2)

Lbar' = (L1 + L2)/2;  Cbar' = (C1' + C2')/2
hbar' = h1' + h2'                  if C1'*C2' = 0
      = (h1' + h2')/2              if |h1' - h2'| <= 180
      = (h1' + h2' + 360)/2        if |h1' - h2'| > 180 and h1' + h2' < 360
      = (h1' + h2' - 360)/2        if |h1' - h2'| > 180 and h1' + h2' >= 360

T = 1 - 0.17cos(hbar' - 30) + 0.24cos(2hbar') + 0.32cos(3hbar' + 6) - 0.20cos(4hbar' - 63)
d_theta = 30 * exp(-((hbar' - 275)/25)²)
R_C = 2 * sqrt(Cbar'⁷ / (Cbar'⁷ + 25⁷))
S_L = 1 + 0.015*(Lbar' - 50)² / sqrt(20 + (Lbar' - 50)²)
S_C = 1 + 0.045*Cbar'
S_H = 1 + 0.015*Cbar'*T
R_T = -sin(2*d_theta) * R_C

dE00 = sqrt( (dL'/S_L)² + (dC'/S_C)² + (dH'/S_H)² + R_T*(dC'/S_C)*(dH'/S_H) )
```
Verification: must match the Sharma, Wu & Dalal (2005) test dataset (34 pairs) within 1e-4.

## 5. Security Architecture
- **Preferred:** StrongBox-backed key (setIsStrongBoxBacked(true)).
- **Fallback:** TEE-backed Keystore key.
- **Unsupported:** Software-only key for production signing; raise a fatal security error and block sealing.
- **Attestation:** Generate the key with an attestation challenge. Parse the X.509 chain extension OID 1.3.6.1.4.1.11129.2.1.17 and read attestationSecurityLevel (StrongBox or TrustedEnvironment). Store the leaf certificate hash and security level in the record. Full chain validation to a Google root requires a trust anchor bundled with the app; offline this is best-effort and flagged as such.
- **Key use:** Require user authentication (device credential) for signing where supported.
- **Order of Volatility:** The record is hashed and signed in memory before persistence.

## 6. Hash Chain Specification (normative)
**Payload** = all record fields **except** `record_hash` and `digital_signature_ecdsa`.

1. Serialize the payload as canonical JSON per RFC 8785 (JCS): UTF-8, keys sorted, no whitespace.
2. Floating-point values are stored as **fixed-precision decimal strings** (e.g., Lab* with 4 decimals, GPS with 6 decimals) to avoid cross-platform float formatting drift. Timestamps are ISO-8601 UTC with millisecond precision, ending in `Z`.
3. `record_hash = SHA-256( canonical_payload_bytes || previous_record_hash_bytes )`, where previous_record_hash is the raw 32 bytes (not hex text).
4. **Genesis:** the first record uses 32 zero bytes as previous_record_hash. `previous_record_hash` in the payload is already hex, so it is included in the canonical payload too (defense in depth).
5. `digital_signature_ecdsa = ECDSA-P256-SHA256-sign(record_hash_bytes)`, DER-encoded, then Base64.
6. Each record has a monotonically increasing `sequence_number` (in the payload) so deleted records are detectable.
7. **Verification** (FR-010) recomputes steps 1 to 3 for every record, checks the link to the previous one, and verifies each signature with the stored public key.

Limits: see PRD §6. The chain is tamper-evident; it cannot alone prevent deletion of the newest records or a full rebuild on a rooted device.

## 7. Data Model (Auditable Record)
```json
{
  "record_id": "UUID-v4",
  "sequence_number": "INT",
  "officer_badge_id": "STRING",
  "device_information": {
    "device_id": "UUID (app-generated, persisted)",
    "imei": "STRING or null (best-effort; Android 10+ blocks non-privileged access)",
    "make_model": "STRING",
    "os_version": "STRING",
    "app_version": "STRING",
    "key_security_level": "ENUM(STRONGBOX, TEE)",
    "attestation_leaf_cert_sha256": "HEX_STRING"
  },
  "gps_metadata": { "available": "BOOLEAN", "latitude": "DECIMAL_STRING", "longitude": "DECIMAL_STRING", "accuracy_meters": "DECIMAL_STRING" },
  "timestamp_utc": "ISO-8601 UTC, ms precision",
  "camera_parameters": { "awb_locked": "BOOLEAN", "ae_locked": "BOOLEAN", "manual_controls_supported": "BOOLEAN" },
  "image_quality_metrics": { "laplacian_variance": "DECIMAL_STRING", "glare_threshold_passed": "BOOLEAN" },
  "calibration_data": { "aruco_detected": "BOOLEAN", "swatch_count": "INT", "ccm_applied": "BOOLEAN" },
  "analysis_status": "ENUM(COMPLETED, ABORTED_QUALITY, ABORTED_GEOMETRY)",
  "reagent_type": "ENUM",
  "algorithm_version": "STRING",
  "reference_data_version": "STRING (e.g., MOCK-0)",
  "reaction_roi_color_measurements": { "raw_rgb": "ARRAY", "calibrated_lab": "ARRAY" },
  "ciede2000_result": "DECIMAL_STRING",
  "presumptive_result": "ENUM(POSITIVE, NEGATIVE, INCONCLUSIVE)",
  "raw_image_sha256": "HEX_STRING",
  "previous_record_hash": "HEX_STRING",
  "record_hash": "HEX_STRING",
  "digital_signature_ecdsa": "BASE64_STRING"
}
```
Aborted analyses (quality/geometry) are also sealed as records, so failed attempts remain visible in the chain.
