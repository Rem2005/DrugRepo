package nic.drugrepo.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TODO.md Phase 1 task 1: the auditable record defined by architecture.md section 7.
 *
 * This is the on-disk shape of a sealed Test Record. It replaces nothing in Phase 0: the
 * Phase 0 probe (ProbeDatabase) is a separate database and stays until the SQLCipher
 * passphrase becomes Keystore-wrapped in TODO Phase 1 task 2.
 *
 * Three rules govern every field below, and breaking any of them silently invalidates the
 * hash chain in architecture.md section 6:
 *
 * 1. COLUMN NAMES ARE THE CANONICAL JSON KEYS. @ColumnInfo pins every name that differs from
 *    the Kotlin property, and the nesting of the @Embedded groups reproduces the section 7
 *    JSON object structure exactly. The RFC 8785 serializer in TODO Phase 6 is therefore a
 *    mechanical walk of this class rather than a hand-maintained name mapping that can drift.
 *    AuditableRecordSchemaTest fails the build if a name, type or nullability changes.
 *
 * 2. NO FLOATING-POINT COLUMNS. Every decimal in section 7 is described there as a
 *    DECIMAL_STRING, stored with fixed precision, and ADR 003 records why: float formatting
 *    differs across CPU architectures, and a record whose bytes cannot be reproduced cannot
 *    be re-verified by a Magistrate or a court. Do not "clean this up" into Double.
 *
 * 3. NO NULLS THAT SECTION 7 DOES NOT DECLARE. Absent values (no GPS fix, unreadable IMEI)
 *    are the only nullable fields here. Everything else is mandatory even for an aborted
 *    analysis: architecture.md section 7 requires failed attempts to be sealed too.
 *
 * Absent vs zero is a deliberate distinction. `gps_metadata.available = false` means no fix
 * was obtained (PRD.md FR-012) and the certificate says "unavailable"; a captured fix with an
 * accuracy of 0.000000 is a real reading, however implausible. Same for `laplacian_variance`:
 * a genuine 0 on a rejected image is recorded, and the rejection itself lives in
 * `analysis_status`.
 *
 * No DAO, no queries and no sealing logic live in this file: chaining, signing and the
 * insert-only guarantee are TODO Phase 1 tasks 3 and 4, and Keystore/ECDSA is Phase 6.
 */
@Entity(tableName = "records")
data class AuditableRecord(

    /**
     * UUID v4, generated on device (architecture.md section 7). It identifies the record;
     * it is NOT the chain link - that is previous_record_hash plus sequence_number, so a
     * caller cannot substitute one record's id for another's.
     */
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,

    /**
     * Monotonically increasing across the chain (architecture.md section 6, step 6). This is
     * what makes deletion of a sealed record detectable: FR-010 verification finds the gap
     * even when every remaining hash and signature is intact.
     */
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Int,

    /** Badge id the officer typed at unlock (PRD.md workflow 2). Stored verbatim. */
    @ColumnInfo(name = "officer_badge_id")
    val officerBadgeId: String,

    @Embedded
    val deviceInformation: DeviceInformation,

    @Embedded
    val gpsMetadata: GpsMetadata,

    /** ISO-8601 UTC with millisecond precision, ending in 'Z' (architecture.md section 6). */
    @ColumnInfo(name = "timestamp_utc")
    val timestampUtc: String,

    @Embedded
    val cameraParameters: CameraParameters,

    @Embedded
    val imageQualityMetrics: ImageQualityMetrics,

    @Embedded
    val calibrationData: CalibrationData,

    /**
     * COMPLETED, or ABORTED_QUALITY / ABORTED_GEOMETRY. Non-null on purpose: architecture.md
     * section 7 requires aborted analyses to be sealed as records so failed attempts stay
     * visible in the chain, so "this capture did not complete" is data, never a missing row.
     */
    @ColumnInfo(name = "analysis_status")
    val analysisStatus: AnalysisStatus,

    /**
     * Free-form on purpose. architecture.md section 7 says ENUM but never lists the values,
     * and PRD.md only names Marquis and Scott in passing. Declaring an enum would invent a
     * closed list of supported assays that no document establishes (AGENTS.md directive 3).
     * TODO Phase 5 owns the reagent/decision-boundary config table; until validated reference
     * data exists these are MOCK values and must be labelled as such wherever they are shown.
     */
    @ColumnInfo(name = "reagent_type")
    val reagentType: String,

    /** Version of the C++ pipeline that produced the numbers (ADR 003). */
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: String,

    /**
     * Which reference dataset was compared against, e.g. MOCK-0 (AGENTS.md directive 3).
     * Required on every record so a result is never read without knowing its provenance.
     */
    @ColumnInfo(name = "reference_data_version")
    val referenceDataVersion: String,

    @Embedded
    val reactionRoiColorMeasurements: ReactionRoiColorMeasurements,

    /** Delta E00 against the nearest reference centroid, 4 decimals, as a decimal string. */
    @ColumnInfo(name = "ciede2000_result")
    val ciede2000Result: String,

    /** Presumptive only. A POSITIVE here is probable cause, never identification. */
    @ColumnInfo(name = "presumptive_result")
    val presumptiveResult: PresumptiveResult,

    /** SHA-256 of the captured frame. Hashes the evidence, not the analysis of it. */
    @ColumnInfo(name = "raw_image_sha256")
    val rawImageSha256: String,

    /**
     * Hex hash of the preceding record; 64 zeros for the genesis record (GLOSSARY: Genesis
     * Record). Hex text in the payload, raw bytes when hashing - see architecture.md
     * section 6, step 4.
     */
    @ColumnInfo(name = "previous_record_hash")
    val previousRecordHash: String,

    /** SHA-256 over the canonical payload (this record's fields minus the two fields below). */
    @ColumnInfo(name = "record_hash")
    val recordHash: String,

    /** Base64 DER ECDSA-P256/SHA-256 signature of record_hash (architecture.md section 5). */
    @ColumnInfo(name = "digital_signature_ecdsa")
    val digitalSignatureEcdsa: String,
)

/**
 * Where the record was made and with what key. The attestation and security-level fields are
 * populated in TODO Phase 6; the columns exist from Phase 1 because section 7 fixes the
 * payload, and a field added to a sealed chain later would change every record_hash after it.
 */
data class DeviceInformation(

    /** App-generated UUID, persisted on first launch. Stable per install (FR-011). */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /**
     * Best-effort, therefore nullable: Android 10+ blocks IMEI for non-privileged apps
     * (PRD.md FR-011). The app declares no telephony permission at all, deliberately.
     */
    @ColumnInfo(name = "imei")
    val imei: String?,

    @ColumnInfo(name = "make_model")
    val makeModel: String,

    @ColumnInfo(name = "os_version")
    val osVersion: String,

    @ColumnInfo(name = "app_version")
    val appVersion: String,

    /** Never SOFTWARE: a software-only key is refused rather than recorded (architecture.md 5). */
    @ColumnInfo(name = "key_security_level")
    val keySecurityLevel: KeySecurityLevel,

    /** Hex SHA-256 of the attestation leaf certificate (architecture.md section 5). */
    @ColumnInfo(name = "attestation_leaf_cert_sha256")
    val attestationLeafCertSha256: String,
)

/**
 * Fix at capture time, or an honest absence of one. `available` is the flag the certificate
 * renders as "unavailable"; the coordinates are nullable rather than sentinel strings so no
 * verifier can mistake a placeholder for a real coordinate (PRD.md FR-012).
 */
data class GpsMetadata(
    @ColumnInfo(name = "available")
    val available: Boolean,

    @ColumnInfo(name = "latitude")
    val latitude: String?,

    @ColumnInfo(name = "longitude")
    val longitude: String?,

    @ColumnInfo(name = "accuracy_meters")
    val accuracyMeters: String?,
)

/**
 * What the camera actually did, not what was requested (PRD.md FR-001). A device that
 * ignored CONTROL_AWB_MODE_OFF is recorded as unlocked; that is the honest reading of the
 * record and it is exactly what capability detection is for.
 */
data class CameraParameters(
    @ColumnInfo(name = "awb_locked")
    val awbLocked: Boolean,

    @ColumnInfo(name = "ae_locked")
    val aeLocked: Boolean,

    @ColumnInfo(name = "manual_controls_supported")
    val manualControlsSupported: Boolean,
)

/**
 * The quality gate's own numbers, so a rejected capture can be audited later (PRD.md FR-004).
 * Thresholds are configuration in TODO Phase 3 and are not part of this schema.
 */
data class ImageQualityMetrics(
    @ColumnInfo(name = "laplacian_variance")
    val laplacianVariance: String,

    @ColumnInfo(name = "glare_threshold_passed")
    val glareThresholdPassed: Boolean,
)

/**
 * Geometry and color normalization outcome (PRD.md FR-002, architecture.md section 4).
 */
data class CalibrationData(
    @ColumnInfo(name = "aruco_detected")
    val arucoDetected: Boolean,

    @ColumnInfo(name = "swatch_count")
    val swatchCount: Int,

    /** False when the swatch count was below the minimum and no CCM was derived. */
    @ColumnInfo(name = "ccm_applied")
    val ccmApplied: Boolean,
)

/**
 * Colours of the reaction region (GLOSSARY: Reaction Region / ROI), before and after the CCM.
 *
 * Both values are JSON arrays of three fixed-precision decimal strings in [0,1] channel order,
 * e.g. "[0.123456,0.234567,0.345678]" - the ROI mean triple that CIEDE2000 is applied to,
 * not the per-pixel array. Storing every pixel would put a large, redundant blob inside a
 * record whose hash must be reproducible by a third party; the raw frame is already pinned by
 * `raw_image_sha256`, so nothing is lost by keeping the measurement itself.
 */
data class ReactionRoiColorMeasurements(
    @ColumnInfo(name = "raw_rgb")
    val rawRgb: String,

    /** CIE Lab* triple, 4 decimals per architecture.md section 6. */
    @ColumnInfo(name = "calibrated_lab")
    val calibratedLab: String,
)

/**
 * Hardware backing of the signing key. Restricted to the two secure levels on purpose:
 * architecture.md section 5 requires a fatal error and a blocked seal for a software-only
 * key, so "software" must not be expressible here - it must not be a recordable state.
 */
enum class KeySecurityLevel {
    STRONGBOX,
    TEE,
}

/**
 * How far the pipeline got. Every value is a legitimate sealed record, including the two
 * aborts, which are what make "no positive was found here" a verifiable statement.
 */
enum class AnalysisStatus {
    COMPLETED,
    ABORTED_QUALITY,
    ABORTED_GEOMETRY,
}

/**
 * Presumptive outcome only (GLOSSARY: Presumptive Assay). INCONCLUSIVE is a first-class
 * result, not a failure: architecture.md section 4 requires a band around every decision
 * boundary so an ambiguous colour is reported as ambiguous rather than rounded to a verdict.
 * Confirmatory laboratory testing is always required.
 */
enum class PresumptiveResult {
    POSITIVE,
    NEGATIVE,
    INCONCLUSIVE,
}
