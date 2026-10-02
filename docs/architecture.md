System Architecture & Technical Blueprint


	Architecture Overview
The system is a highly deterministic, native Android offline application. It captures uncompressed sensor data, processes it via an embedded C++ computer vision pipeline, makes analytical decisions locally, and cryptographically chains the auditable results into a tamper-proof local database before utilizing deferred background synchronization.

	Technology Stack

	Platform: Native Android (Kotlin).

	Camera: Camera2 API (with capability detection for manual exposure/white-balance).

	Computer Vision: OpenCV (C++ via JNI) for mathematically deterministic matrix operations.

	Local Persistence: Room (SQLite) with SQLCipher.

	Cryptography: Android Keystore system (StrongBox / TEE).

	Background Sync: Android WorkManager (mTLS networking).

	Application Layers / Modules

	UI Layer (Kotlin): Camera viewfinder, geometric overlay, kinetic timer, result display, and officer authentication.

	Sensor Layer (Kotlin): Camera2 subsystem enforcing locked AWB/AE and routing YUV_420_888 frames where device capabilities allow; location and runtime permission management.

	Image Analysis (C++ / OpenCV): Homography, Moore-Penrose pseudo-inverse for color correction, and CIEDE2000 algorithms.

	Data / Persistence (Kotlin / Room): Entity mapping and encrypted SQLite storage.

	Security / Integrity (Kotlin / Keystore): Hardware attestation, ECDSA signing, canonicalization, and SHA-256 chain calculation.

	Computer Vision Pipeline

	Image Validation: Laplacian variance check for blur; L* channel thresholding for extreme glare/saturation.

	Region Detection: Detect ArUco corner markers (cv::aruco::detectMarkers) and rectify perspective (cv::findHomography, cv::warpPerspective).

	Color Normalization: Extract observed RGB swatches (C_obs) and derive the Color Correction Matrix (M) against ideal references (C_ideal). Constraint: The CCM extraction must process a minimum of 3 to 4 reference swatches to ensure the matrix computation is not mathematically underdetermined.
M=C_ideal×C_obs^T×(C_obs×C_obs^T )^(-1)
Apply M to the reaction ROI: C_calibrated=M×C_measured

	Color Comparison: Convert C_calibrated to Lab* space. Calculate ΔE_00 against target datasets using the full CIEDE2000 formula:

ΔE_00=√((ΔL'/(k_L S_L ))^2+(ΔC'/(k_C S_C ))^2+(ΔH'/(k_H S_H ))^2+R_T (ΔC'/(k_C S_C ))(ΔH'/(k_H S_H )) )
	Decision Logic: Computes the mathematical perceptual distance. Note: Specific chemical target thresholds are Prototype Assumptions and must be replaced with validated reference datasets.

	Security Architecture
The application implements strict fallback logic for cryptographic integrity:

	Preferred: StrongBox-backed KeyMint element (setIsStrongBoxBacked(true)).

	Fallback: TEE-backed Android Keystore key.

	Unsupported: Software-only key for production integrity signing (will trigger a fatal security error to protect evidence integrity).

	Attestation: The app parses the X.509 certificate chain for OID 1.3.6.1.4.1.11129.2.1.17 to verify the attestationSecurityLevel.

	Cryptographic Chaining: H_n="SHA-256" ("Canonical_JSON_Payload" ∥H_(n-1) ). Generated in active memory (Order of Volatility) before persistent storage.

	Hash Canonicalization: To ensure reproducible verification, "Canonical_JSON_Payload"  must enforce strict alphabetical key ordering, strip all extraneous whitespace, and enforce standardized float/number formatting.

	Chain Limits & Mitigation: A sophisticated attacker with a rooted device could theoretically rewrite the entire local hash chain. This vulnerability is mitigated by appending the hardware ECDSA signature, embedding Keystore attestation data, and performing deferred server synchronization, which acts as a timestamped cryptographic anchor.

	Data Model (Auditable Record)

JSON
{
  "record_id": "UUID-v4",
  "officer_badge_id": "STRING",
  "device_information": { 
    "device_id_uuid": "STRING", 
    "imei_best_effort": "STRING", 
    "make_model": "STRING", 
    "app_version": "STRING" 
  },
  "gps_metadata": { "latitude": "DECIMAL", "longitude": "DECIMAL", "accuracy_meters": "FLOAT" },
  "timestamp_epoch_iso": "ISO-8601 UTC",
  "camera_parameters": { "awb_locked": "BOOLEAN", "ae_locked": "BOOLEAN" },
  "image_quality_metrics": { "laplacian_variance": "FLOAT", "glare_threshold_passed": "BOOLEAN" },
  "calibration_data": { "aruco_detected": "BOOLEAN", "ccm_applied": "BOOLEAN" },
  "analysis_status": "ENUM(COMPLETED, ABORTED_QUALITY, ABORTED_GEOMETRY)",
  "reagent_type": "ENUM",
  "algorithm_version": "STRING",
  "reaction_roi_color_measurements": { "raw_rgb": "ARRAY", "calibrated_lab": "ARRAY" },
  "ciede2000_result": "FLOAT",
  "presumptive_result": "ENUM(POSITIVE, NEGATIVE, INCONCLUSIVE)",
  "raw_image_sha256": "HEX_STRING",
  "previous_record_hash": "HEX_STRING",
  "record_hash": "HEX_STRING",
  "digital_signature_ecdsa": "BASE64_STRING"
}

