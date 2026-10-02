package nic.drugrepo

import nic.drugrepo.db.AnalysisStatus
import nic.drugrepo.db.AuditableRecord
import nic.drugrepo.db.KeySecurityLevel
import nic.drugrepo.db.PresumptiveResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * TODO.md Phase 1 task 1 gate: the entity still matches the schema in architecture.md section 7.
 *
 * The audit record's columns are normative, not incidental. Every one of them is part of the
 * canonical payload in architecture.md section 6, so a rename, a dropped column or a silent
 * type change does not break the build - it produces a different record_hash and makes the
 * ledger unverifiable. Nothing else in the codebase would notice, which is why this test
 * exists.
 *
 * It asserts against the schema JSON that Room's KSP processor exports, because that artifact
 * is what will actually be created in SQLite, and because it is the file that has to be
 * reviewed and committed alongside schema changes (app/build.gradle.kts) - so a drift shows up
 * in a diff either way. The alternative, reflecting over @ColumnInfo and @Embedded, does not
 * work: both are @AnnotationRetention(BINARY), so they are absent from the runtime reflection
 * data this test would need.
 *
 * Each expected entry is keyed by the Kotlin property path, so one literal pins four things per
 * column: which section 7 object it sits in (embedded paths are dotted, e.g.
 * "deviceInformation.imei"), its Kotlin name, its database column name - the canonical JSON key
 * in architecture.md section 6 - and its type. Affinity is asserted because it is what makes a
 * determinism violation visible: a Double would report REAL here, an architecture.md section 6
 * break that stays invisible until a third party's hash fails to reproduce.
 *
 * Parsing note, because it is not obvious from the JSON: Room omits the "notNull" key entirely
 * when a column is nullable instead of writing "notNull": false.
 */
class AuditableRecordSchemaTest {

    @Test
    fun exportedSchemaMatchesArchitectureSectionSeven() {
        assertEquals(
            "the ledger must stay a single table named records",
            1,
            occurrences(schemaJson(), "\"tableName\"", "\"records\""),
        )
        assertEquals(
            "columns drifted from architecture.md section 7; got ${actualColumns()}",
            EXPECTED_COLUMNS,
            actualColumns(),
        )
    }

    @Test
    fun enumColumnsUseTheSpellingFromArchitectureSectionSeven() {
        // Room stores an enum by its constant name, so these strings ARE the canonical JSON
        // values. Renaming a constant would rewrite sealed records' hashes with no other
        // symptom, so they are pinned here.
        assertEquals(listOf("STRONGBOX", "TEE"), constantNames(KeySecurityLevel::class.java))
        assertEquals(
            listOf("COMPLETED", "ABORTED_QUALITY", "ABORTED_GEOMETRY"),
            constantNames(AnalysisStatus::class.java),
        )
        assertEquals(
            listOf("POSITIVE", "NEGATIVE", "INCONCLUSIVE"),
            constantNames(PresumptiveResult::class.java),
        )
    }

    @Test
    fun reagentTypeIsNotAnEnum() {
        // architecture.md section 7 says ENUM but never lists the values, and PRD.md only
        // mentions Marquis and Scott in passing. An enum here would invent a closed list of
        // supported assays (AGENTS.md directive 3); TODO Phase 5's config table owns it.
        assertEquals(
            "reagent_type must stay a free-form String until Phase 5 defines the assay list",
            String::class.java,
            AuditableRecord::class.java.getDeclaredField("reagentType").type,
        )
    }

    private fun constantNames(enumClass: Class<*>): List<String> =
        enumClass.enumConstants.orEmpty().map { (it as Enum<*>).name }

    /** The 35 leaf columns of architecture.md section 7, in that document's order. */
    private val EXPECTED_COLUMNS: Map<String, Column> = buildMap {
        // Top level.
        put("recordId", Column("record_id", "TEXT", notNull = true))
        put("sequenceNumber", Column("sequence_number", "INTEGER", notNull = true))
        put("officerBadgeId", Column("officer_badge_id", "TEXT", notNull = true))
        put("timestampUtc", Column("timestamp_utc", "TEXT", notNull = true))
        put("analysisStatus", Column("analysis_status", "TEXT", notNull = true))
        put("reagentType", Column("reagent_type", "TEXT", notNull = true))
        put("algorithmVersion", Column("algorithm_version", "TEXT", notNull = true))
        put("referenceDataVersion", Column("reference_data_version", "TEXT", notNull = true))
        put("ciede2000Result", Column("ciede2000_result", "TEXT", notNull = true))
        put("presumptiveResult", Column("presumptive_result", "TEXT", notNull = true))
        put("rawImageSha256", Column("raw_image_sha256", "TEXT", notNull = true))
        put("previousRecordHash", Column("previous_record_hash", "TEXT", notNull = true))
        put("recordHash", Column("record_hash", "TEXT", notNull = true))
        put("digitalSignatureEcdsa", Column("digital_signature_ecdsa", "TEXT", notNull = true))
        // device_information.
        put("deviceInformation.deviceId", Column("device_id", "TEXT", notNull = true))
        put("deviceInformation.imei", Column("imei", "TEXT", notNull = false))
        put("deviceInformation.makeModel", Column("make_model", "TEXT", notNull = true))
        put("deviceInformation.osVersion", Column("os_version", "TEXT", notNull = true))
        put("deviceInformation.appVersion", Column("app_version", "TEXT", notNull = true))
        put("deviceInformation.keySecurityLevel", Column("key_security_level", "TEXT", notNull = true))
        put(
            "deviceInformation.attestationLeafCertSha256",
            Column("attestation_leaf_cert_sha256", "TEXT", notNull = true),
        )
        // gps_metadata. Nullable because PRD.md FR-012 requires the capture to be saved and
        // sealed when there is no fix.
        put("gpsMetadata.available", Column("available", "INTEGER", notNull = true))
        put("gpsMetadata.latitude", Column("latitude", "TEXT", notNull = false))
        put("gpsMetadata.longitude", Column("longitude", "TEXT", notNull = false))
        put("gpsMetadata.accuracyMeters", Column("accuracy_meters", "TEXT", notNull = false))
        // camera_parameters.
        put("cameraParameters.awbLocked", Column("awb_locked", "INTEGER", notNull = true))
        put("cameraParameters.aeLocked", Column("ae_locked", "INTEGER", notNull = true))
        put("cameraParameters.manualControlsSupported", Column("manual_controls_supported", "INTEGER", notNull = true))
        // image_quality_metrics.
        put("imageQualityMetrics.laplacianVariance", Column("laplacian_variance", "TEXT", notNull = true))
        put("imageQualityMetrics.glareThresholdPassed", Column("glare_threshold_passed", "INTEGER", notNull = true))
        // calibration_data.
        put("calibrationData.arucoDetected", Column("aruco_detected", "INTEGER", notNull = true))
        put("calibrationData.swatchCount", Column("swatch_count", "INTEGER", notNull = true))
        put("calibrationData.ccmApplied", Column("ccm_applied", "INTEGER", notNull = true))
        // reaction_roi_color_measurements.
        put("reactionRoiColorMeasurements.rawRgb", Column("raw_rgb", "TEXT", notNull = true))
        put("reactionRoiColorMeasurements.calibratedLab", Column("calibrated_lab", "TEXT", notNull = true))
    }

    private data class Column(val columnName: String, val affinity: String, val notNull: Boolean)

    /**
     * Minimal scan of the exported schema: field entries are flat scalars, so splitting the
     * "fields" array on '}' yields one chunk per column without needing a JSON dependency.
     */
    private fun actualColumns(): Map<String, Column> {
        val fields = schemaJson()
            .substringAfter("\"fields\"")
            .substringAfter('[', "")
            .substringBefore(']')
        return fields.split('}')
            .mapNotNull { entry ->
                val path = quoted(entry, "fieldPath") ?: return@mapNotNull null
                val columnName = quoted(entry, "columnName") ?: return@mapNotNull null
                val affinity = quoted(entry, "affinity") ?: return@mapNotNull null
                val notNull = Regex("\"notNull\"\\s*:\\s*(true|false)")
                    .find(entry)?.groupValues?.get(1)?.toBoolean() ?: false
                path to Column(columnName, affinity, notNull)
            }
            .toMap()
    }

    private fun quoted(text: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)

    private fun occurrences(text: String, key: String, value: String): Int =
        Regex(Regex.escape(key) + "\\s*:\\s*" + Regex.escape(value)).findAll(text).count()

    private fun schemaJson(): String = schemaFile().readText()

    /**
     * KSP writes the schema into app/schemas (app/build.gradle.kts) before the unit tests run,
     * so this reads the freshly generated file rather than a committed copy that could lag.
     */
    private fun schemaFile(): File {
        val candidates = listOf(File(SCHEMA_PATH), File("../$SCHEMA_PATH"))
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "exported Room schema not found; run :app:testDebugUnitTest, which generates it. " +
                    "Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    private companion object {
        const val SCHEMA_PATH = "schemas/nic.drugrepo.db.AuditableRecordDatabase/1.json"
    }
}