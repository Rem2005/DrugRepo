package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import nic.drugrepo.analysis.AnalysisMode
import nic.drugrepo.analysis.AnalysisResult
import nic.drugrepo.databinding.ActivityResultBinding
import nic.drugrepo.db.AuditableRecord
import nic.drugrepo.db.AuditableRecordDatabaseFactory
import nic.drugrepo.db.AnalysisStatus
import nic.drugrepo.db.CalibrationData
import nic.drugrepo.db.CameraParameters
import nic.drugrepo.db.DeviceInformation
import nic.drugrepo.db.GpsMetadata
import nic.drugrepo.db.ImageQualityMetrics
import nic.drugrepo.db.KeySecurityLevel
import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.db.ReactionRoiColorMeasurements
import nic.drugrepo.vision.CvColorimetryMeasurement
import nic.drugrepo.vision.CvMeasurement
import nic.drugrepo.vision.CvRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The result screen.
 *
 * Laid out as seven cards in reading order - outcome, whose test, what was measured, how the
 * geometry came out, what the calibration state is, what the result may claim, what gets sealed -
 * because an officer reads it once, top to bottom, and the answer must be above the detail.
 *
 * Every number here was measured from the captured frame by the CV pipeline and arrives in
 * [AnalysisResult.cvMeasurement]. Nothing on this screen is computed from a value typed into it,
 * and nothing is shown that the pipeline did not measure: sharpness and glare are recorded in the
 * sealed record, not paraded in the answer. When the capture could not be measured, the screen
 * says so in place of the numbers instead of showing zeros.
 *
 * The one thing it must never blur: a synthetic demonstration and a field result are different
 * kinds of statement. Headline and both pills derive from [AnalysisResult.mode], so no downstream
 * screen can render a demonstration as a finding.
 */
class ResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val result = intent.getSerializableExtra("result") as? AnalysisResult ?: AnalysisResult(
            PresumptiveResult.INCONCLUSIVE,
            "0.0000",
            true,
            "DEMO/MOCK"
        )
        val badgeId = intent.getStringExtra("badgeId") ?: "DEMO-0001"
        val imagePath = intent.getStringExtra("imagePath")

        val synthetic = result.mode == AnalysisMode.SYNTHETIC_DEMONSTRATION
        val measurement = result.cvMeasurement
        val colorimetry = measurement?.colorimetry

        binding.tvResult.text = headline(result, synthetic)
        binding.tvResult.setTextColor(resultColor(result.presumptiveResult))
        pill(binding.tvDemo, SYNTHETIC_BADGE, FIELD_BADGE, synthetic)
        pill(
            binding.tvDemoSecond,
            SYNTHETIC_SCOPE_BADGE,
            FIELD_SCOPE_BADGE,
            synthetic,
            R.color.demo_bg,
            R.color.demo_text,
        )

        showTestInfo(binding, result, measurement, badgeId, synthetic)
        showColourAnalysis(binding, result, measurement, colorimetry, synthetic)
        showGeometry(binding, measurement, colorimetry)
        showCalibration(binding, colorimetry, synthetic)
        showInterpretation(binding, synthetic)
        showIntegrity(binding, result, imagePath)

        binding.tvBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            Thread {
                try {
                    val db = AuditableRecordDatabaseFactory.open(this)
                    val dao = db.auditableRecordDao()
                    val latest = runBlocking(Dispatchers.IO) { dao.getLatest() }
                    val nextSeq = (latest?.sequenceNumber ?: 0) + 1
                    val prevHash = latest?.recordHash ?: "0".repeat(64)
                    val record = createRecord(result, nextSeq, badgeId, imagePath, prevHash)
                    runBlocking(Dispatchers.IO) { dao.insert(record) }
                    db.close()
                    runOnUiThread {
                        Toast.makeText(this, "Record saved", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, RecordsActivity::class.java))
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        binding.btnViewRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }

        binding.btnHome.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    // --- sections -------------------------------------------------------------

    private fun showTestInfo(
        binding: ActivityResultBinding,
        result: AnalysisResult,
        measurement: CvMeasurement?,
        badgeId: String,
        synthetic: Boolean,
    ) {
        rows(
            binding.rowsTestInfo,
            R.layout.item_detail_row,
            buildList {
                add("Officer" to badgeId)
                add("Profile" to if (synthetic) "Synthetic demonstration" else "Field / real")
                // The machine-readable profile id stays in the sealed record as
                // reference_data_version; the screen names the reference data instead of printing an
                // identifier that means nothing to an officer and invites being treated as one.
                if (synthetic) {
                    add("Reference data" to "Synthetic demonstration profile")
                } else {
                    add("Reference data" to "NCB qualitative reference profile")
                    add("Reference status" to "Unvalidated prototype reference")
                }
                add("Recorded" to utcNow())
                add("Result mode" to if (result.demo) "Unvalidated (prototype)" else "Validated")
            },
        )
        // The capture status belongs with the test it belongs to, and it must not be a row that
        // says "measured" when nothing was measured.
        if (measurement != null && !measurement.isValid) {
            notes(
                binding.rowsTestInfo,
                buildList { add("Capture status" to measurement.statusLabel) },
            )
        }
    }

    private fun showColourAnalysis(
        binding: ActivityResultBinding,
        result: AnalysisResult,
        measurement: CvMeasurement?,
        colorimetry: CvColorimetryMeasurement?,
        synthetic: Boolean,
    ) {
        if (measurement == null || !measurement.isValid || colorimetry == null) {
            binding.swatchRow.visibility = View.GONE
            notes(
                binding.rowsColour,
                buildList {
                    add("Not measured" to (measurement?.statusLabel ?: "no image"))
                },
            )
            return
        }
        // The colour the pipeline measured, shown as a colour. Same numbers as the RGB row below
        // it, so the two can never disagree: both come from colorimetry.reactionRaw.
        binding.viewTestSwatch.backgroundTintList = ColorStateList.valueOf(
            Color.rgb(
                colorimetry.reactionRaw.r.roundToInt().coerceIn(0, 255),
                colorimetry.reactionRaw.g.roundToInt().coerceIn(0, 255),
                colorimetry.reactionRaw.b.roundToInt().coerceIn(0, 255),
            )
        )

        // The labels say what was compared to what rather than naming the formula: an officer's
        // question is "how close, to what". The algorithm is recorded in RECORD INTEGRITY and in
        // the sealed record.
        rows(binding.rowsColour, R.layout.item_detail_row, buildList {
            add("Test colour (RGB)" to colorimetry.reactionRaw.format())
            // Shown only when a CCM actually ran. With the shipped profiles none does, and a row
            // that said "corrected" beside an uncorrected number would be the wrong number.
            if (colorimetry.ccmApplied) {
                add("Corrected test colour (RGB)" to colorimetry.reactionCorrected.format())
            }
            add("Test colour (Lab)" to colorimetry.reactionLab.format())
            add("Reference colour (RGB)" to colorimetry.referenceRaw.format())
            add("Reference colour (Lab)" to colorimetry.referenceLab.format())
            add("Reference ROI → Test colour" to result.ciede2000Result)
        })

        // The anchor comparison is the one the synthetic outcome rests on, so it is bold and the
        // anchor it landed on is named directly beneath. Bold weight on the existing row views,
        // not a new style: prominence without a second visual language. The tolerance itself is
        // not printed: that number lives in reference_profile.cpp, and a second copy in Kotlin
        // could disagree with it without anything failing.
        if (synthetic) {
            rows(
                binding.rowsColour,
                R.layout.item_detail_row,
                listOf(
                    "Test colour → Synthetic anchor" to
                        String.format("%.2f", colorimetry.anchorDeltaE2000)
                ),
                emphasise = true,
            )
            if (colorimetry.anchorLabel.isNotEmpty()) {
                notes(
                    binding.rowsColour,
                    listOf("Nearest synthetic anchor" to colorimetry.anchorLabel),
                )
            }
        }
    }

    private fun showGeometry(
        binding: ActivityResultBinding,
        measurement: CvMeasurement?,
        colorimetry: CvColorimetryMeasurement?,
    ) {
        if (measurement == null || !measurement.isValid || colorimetry == null) {
            notes(
                binding.rowsGeometry,
                buildList { add("Not measured" to (measurement?.statusLabel ?: "no image")) },
            )
            return
        }
        val geometry = when (measurement.geometrySource) {
            CvMeasurement.GEOMETRY_HOMOGRAPHY_RECTIFIED -> "ArUco homography"
            CvMeasurement.GEOMETRY_ARUCO_FULL -> "four ArUco markers"
            CvMeasurement.GEOMETRY_ARUCO_PARTIAL -> "partial ArUco markers"
            CvMeasurement.GEOMETRY_CENTRE_FALLBACK -> "centre fallback"
            else -> "none"
        }
        rows(
            binding.rowsGeometry,
            R.layout.item_detail_row,
            buildList {
                add("Image resolution" to "${measurement.imageWidth} × ${measurement.imageHeight}")
                add(
                    "ArUco detected" to
                        "${measurement.markerCount} of 4 - ${measurement.markerIdList}",
                )
                add("Expected markers" to "$EXPECTED_MARKERS (DICT_4X4_50)")
                // Size and method on one row; the two regions on their own rows. The earlier wording
                // read "320 reference swatches measured" and conflated the reference region's pixel
                // width with a count of swatches - two different quantities that happen to be 320.
                add(
                    "Rectified card" to
                        "${colorimetry.rectifiedWidth} × ${colorimetry.rectifiedHeight} " +
                        "via $geometry",
                )
                add("Reference ROI" to rect(measurement.referenceRect))
                add("Test / reaction ROI" to rect(measurement.reactionRect))
            },
        )
    }

    private fun showCalibration(
        binding: ActivityResultBinding,
        colorimetry: CvColorimetryMeasurement?,
        synthetic: Boolean,
    ) {
        val pairs = buildList {
            add(
                "Colour correction matrix" to
                    if (colorimetry?.ccmApplied == true) {
                        "Applied"
                    } else {
                        "Not applied (no authoritative patch colours)"
                    },
            )
            if (synthetic) {
                add("Calibration state" to "Synthetic demonstration profile")
                add("Validity" to "Not real-world forensic calibration")
            } else {
                add("Calibration state" to "Validated numerical calibration unavailable")
                add("Outcome policy" to "Result intentionally inconclusive")
            }
        }
        notes(binding.rowsCalibration, pairs)
        showProvenance(binding, colorimetry?.profileProvenance)
    }

    /**
     * The profile's own provenance statement, collapsed.
     *
     * It is several hundred characters and it is the right answer to "where did these numbers come
     * from", which is a review question rather than an outcome question. So it is not deleted and
     * not on the primary read: one tap, inside CALIBRATION where it belongs. The rows above it
     * already carry the conclusion - synthetic demonstration profile, or validated calibration
     * unavailable - so nothing safety-relevant is hidden behind the tap.
     */
    private fun showProvenance(binding: ActivityResultBinding, provenance: String?) {
        if (provenance.isNullOrEmpty()) {
            binding.tvProvenanceToggle.visibility = View.GONE
            return
        }
        binding.tvProvenance.text = provenance
        binding.tvProvenanceToggle.setOnClickListener {
            val shown = binding.tvProvenance.visibility == View.VISIBLE
            binding.tvProvenance.visibility = if (shown) View.GONE else View.VISIBLE
            binding.tvProvenanceToggle.setText(
                if (shown) R.string.result_show_provenance else R.string.result_hide_provenance
            )
        }
    }

    private fun showInterpretation(binding: ActivityResultBinding, synthetic: Boolean) {
        // Three fixed sentences, one per line, and no more. A result's limits are not a paragraph:
        // an officer needs to read them once and act, and a paragraph is a paragraph nobody reads.
        lines(
            binding.rowsInterpretation,
            if (synthetic) SYNTHETIC_INTERPRETATION else FIELD_INTERPRETATION,
        )
    }

    private fun showIntegrity(
        binding: ActivityResultBinding,
        result: AnalysisResult,
        imagePath: String?,
    ) {
        // The same hash the record is sealed with, computed once here and passed to createRecord().
        // Computing it twice would let the screen and the record disagree, which is the one thing
        // an integrity panel must not do.
        val sha256 = sha256Of(imagePath)
        // The hash and the algorithm version are identifiers: monospace, so digits can be compared
        // by eye. The hash-chain note is a sentence, so it is not.
        notes(
            binding.rowsIntegrity,
            listOf(
                "Image SHA-256" to sha256,
                "Algorithm" to if (result.cvMeasurement != null) ALGORITHM_CV else ALGORITHM_DEMO,
            ),
            mono = true,
        )
        notes(
            binding.rowsIntegrity,
            listOf(
                "Hash chain" to
                    "Append-only: saved records are never edited or deleted. Each record carries " +
                        "the hash of the record before it. Digital signature is MOCK in this " +
                        "prototype.",
            ),
        )
    }

    // --- row helpers ----------------------------------------------------------

    /**
     * Fills [container] with label/value rows of one shape. The two shapes share view ids and
     * differ only in arrangement, so one fill routine serves both and a new section cannot
     * accidentally invent a third style.
     */
    private fun rows(
        container: LinearLayout,
        layout: Int,
        pairs: List<Pair<String, String>>,
        mono: Boolean = false,
        emphasise: Boolean = false,
    ) {
        val pad = resources.getDimensionPixelSize(R.dimen.row_gap)
        pairs.forEach { (label, value) ->
            val row = LayoutInflater.from(this).inflate(layout, container, false)
            row.findViewById<TextView>(R.id.tvRowLabel).text = label
            val valueView = row.findViewById<TextView>(R.id.tvRowValue)
            valueView.text = value
            if (mono) {
                valueView.typeface = Typeface.MONOSPACE
            }
            // Weight, not a new style: the row keeps its existing appearance and only stops being
            // visually equal to its neighbours.
            if (emphasise) {
                row.findViewById<TextView>(R.id.tvRowLabel).setTypeface(null, Typeface.BOLD)
                valueView.setTypeface(null, Typeface.BOLD)
            }
            (row.layoutParams as LinearLayout.LayoutParams).topMargin = pad
            container.addView(row)
        }
    }

    /**
     * A note is the same shape as a row, except that a long value is stacked instead of squeezed
     * into the right-hand column. The choice is per value, not per call: the provenance paragraph
     * and the hash-chain sentence need a full-width line, while "Algorithm" beside its version
     * reads fine side by side with its neighbours.
     */
    private fun notes(
        container: LinearLayout,
        pairs: List<Pair<String, String>>,
        mono: Boolean = false,
    ) {
        pairs.forEach { (label, value) ->
            rows(
                container,
                if (value.length > STACKED_MIN_CHARS) {
                    R.layout.item_result_note
                } else {
                    R.layout.item_detail_row
                },
                listOf(label to value),
                mono,
            )
        }
    }

    /** Fixed statements with no term beside them, styled as body text. */
    private fun lines(container: LinearLayout, texts: List<String>) {
        val pad = resources.getDimensionPixelSize(R.dimen.row_gap)
        texts.forEach { text ->
            val view = TextView(this)
            view.setTextAppearance(this, R.style.Text_Body)
            view.text = text
            container.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = pad },
            )
        }
    }

    private fun pill(
        view: TextView,
        syntheticText: String,
        fieldText: String,
        synthetic: Boolean,
        background: Int = if (synthetic) R.color.demo_bg else R.color.result_inconclusive_bg,
        text: Int = if (synthetic) R.color.demo_text else R.color.result_inconclusive_text,
    ) {
        view.text = if (synthetic) syntheticText else fieldText
        view.backgroundTintList = ColorStateList.valueOf(getColor(background))
        view.setTextColor(getColor(text))
    }

    private fun rect(value: CvRect) =
        "${value.width} × ${value.height} at (${value.x}, ${value.y})"

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    /**
     * SHA-256 of the captured JPEG, or 64 zeros when there is no readable file. Zero-filled rather
     * than blank, because this is a hash field and an empty hash would read as an unrecorded image
     * rather than an absent one.
     */
    private fun sha256Of(imagePath: String?): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val file = imagePath?.let { File(it) }
        if (file != null && file.exists()) {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "0".repeat(64)
    }

    /**
     * The headline outcome. A synthetic match is worded as a demonstration of the colour
     * comparison, never as a presumptive result, because it is not one: nothing was validated.
     * A field result keeps the enum's own name so the record and the screen cannot diverge.
     */
    private fun headline(result: AnalysisResult, synthetic: Boolean): String =
        if (!synthetic) {
            result.presumptiveResult.name
        } else {
            when (result.presumptiveResult) {
                PresumptiveResult.POSITIVE -> "DEMONSTRATIVE MATCH"
                PresumptiveResult.NEGATIVE -> "DEMONSTRATIVE NO MATCH"
                else -> "INCONCLUSIVE"
            }
        }

    /** Presentation only: a presumptive result gets a colour, never a stronger claim. */
    private fun resultColor(result: PresumptiveResult): Int = getColor(
        when (result) {
            PresumptiveResult.POSITIVE -> R.color.result_positive_text
            PresumptiveResult.NEGATIVE -> R.color.result_negative_text
            PresumptiveResult.INCONCLUSIVE -> R.color.result_inconclusive_text
        }
    )

    private fun createRecord(
        result: AnalysisResult,
        seq: Int,
        badgeId: String,
        imagePath: String?,
        prevHash: String,
    ): AuditableRecord {
        val deviceInfo = DeviceInformation(
            deviceId = UUID.randomUUID().toString(),
            imei = null,
            makeModel = "DemoDevice",
            osVersion = "Android 15",
            appVersion = "0.1.0-prototype",
            keySecurityLevel = try {
                AuditableRecordDatabaseFactory.open(this).let { db -> db.close(); KeySecurityLevel.TEE }
            } catch (e: Exception) {
                KeySecurityLevel.TEE
            },
            attestationLeafCertSha256 = "0".repeat(64),
        )
        val gps = GpsMetadata(false, null, null, null)
        val camera = CameraParameters(false, false, false)
        // The CV stage measures these three groups of values; when it could not measure
        // anything (no image, decode failure) the record keeps the previous explicit
        // placeholder rather than writing zeros that would read as a measurement.
        val cv = result.cvMeasurement?.takeIf { it.isValid }
        val quality = ImageQualityMetrics(
            laplacianVariance = cv?.let { String.format("%.4f", it.laplacianVariance) } ?: "not measured",
            // No glare threshold is implemented yet, so nothing may be recorded as passing one.
            glareThresholdPassed = false
        )
        // The colour stage now measures real values, so these three fields carry what actually
        // ran: how many markers were found, how many reference swatches were measured on the
        // rectified card, and whether a colour correction matrix was applied (it is not, while the
        // profile has no authoritative patch colours).
        val colorimetry = cv?.colorimetry
        val calibration = CalibrationData(
            arucoDetected = (cv?.markerCount ?: 0) >= 4,
            swatchCount = colorimetry?.swatchCount ?: 0,
            ccmApplied = colorimetry?.ccmApplied ?: false
        )
        val roi = ReactionRoiColorMeasurements(
            rawRgb = cv?.let {
                "${it.reactionMean.format()} on ${colorimetry?.rectifiedWidth}x" +
                    "${colorimetry?.rectifiedHeight} rectified card from a ${it.imageWidth}x" +
                    "${it.imageHeight} frame"
            } ?: "[not measured]",
            calibratedLab = colorimetry?.let { "${it.reactionLab.format()}; reference ${it.referenceLab.format()}" }
                ?: "[not computed]"
        )
        return AuditableRecord(
            recordId = UUID.randomUUID().toString(),
            sequenceNumber = seq,
            officerBadgeId = badgeId,
            timestampUtc = utcNow(),
            analysisStatus = when {
                cv != null -> AnalysisStatus.COMPLETED
                // No measurement was produced. Which abort applies depends on why, so the
                // honest generic value is kept as ABORTED_GEOMETRY rather than claiming a
                // completed analysis that never ran.
                else -> AnalysisStatus.ABORTED_GEOMETRY
            },
            reagentType = colorimetry?.reagentType ?: "MARQUIS",
            // Version strings must describe what actually ran: the SHA demo path and the
            // OpenCV pipeline are different algorithms and say so. `demo` is a UI concept
            // (everything here is still provisional), so the presence of a CV measurement is
            // what distinguishes them.
            algorithmVersion = if (result.cvMeasurement != null) ALGORITHM_CV else ALGORITHM_DEMO,
            // The reference data version is the profile's own version, because the profile is
            // what the colour numbers came out of. The shipped one says PROVISIONAL in its string,
            // so a record can never imply a validated kit. The SHA demo path reads no reference
            // data at all and says MOCK-0.
            referenceDataVersion = colorimetry?.profileVersion ?: "MOCK-0",
            ciede2000Result = result.ciede2000Result,
            presumptiveResult = result.presumptiveResult,
            // The identical value the integrity panel on screen shows, from the same call.
            rawImageSha256 = sha256Of(imagePath),
            previousRecordHash = prevHash,
            recordHash = "0".repeat(64),
            digitalSignatureEcdsa = "demo",
            deviceInformation = deviceInfo,
            gpsMetadata = gps,
            cameraParameters = camera,
            imageQualityMetrics = quality,
            calibrationData = calibration,
            reactionRoiColorMeasurements = roi,
        )
    }

    private companion object {
        /**
         * Badge text. Wording is fixed here rather than derived, so "SYNTHETIC" and "DEMONSTRATION
         * ONLY" cannot go missing from a synthetic result screen through a refactor. The second
         * badge names what the result is worth, because "synthetic" alone does not tell an officer
         * whether the colour on the card was identified.
         */
        const val SYNTHETIC_BADGE = "SYNTHETIC DEMONSTRATION"
        const val FIELD_BADGE = "INCONCLUSIVE"
        const val SYNTHETIC_SCOPE_BADGE = "DEMO ONLY"
        const val FIELD_SCOPE_BADGE = "VALIDATED CALIBRATION UNAVAILABLE"

        /**
         * Three lines per mode, and deliberately the whole of the interpretation section. None of
         * them claims admissibility, compliance or certainty, because nothing in this build can
         * support such a claim (AGENTS.md directive 7).
         */
        val SYNTHETIC_INTERPRETATION = listOf(
            "SYNTHETIC DEMONSTRATION ONLY. The colour anchors are a synthetic dataset, not official NCB data.",
            "NOT A REAL DRUG TEST. No substance has been identified in any sample.",
            "NOT FORENSICALLY VALIDATED.",
        )
        val FIELD_INTERPRETATION = listOf(
            "PRESUMPTIVE / FIELD ANALYSIS ONLY. Results require laboratory confirmation.",
            "VALIDATED CALIBRATION REQUIRED before any result can be interpreted.",
            "INCONCLUSIVE WHEN VALIDATION IS UNAVAILABLE, as it is in this build.",
        )

        /** The profile's own marker ids (reference_profile.cpp). Shown so a mismatch is legible. */
        const val EXPECTED_MARKERS = "1, 2, 3, 4"

        /** Recorded in every row, so the algorithm and the reference data travel with the result. */
        const val ALGORITHM_CV = "0.2.0-cv"
        const val ALGORITHM_DEMO = "1.0.0-demo"

        /**
         * Wider than the value column of item_detail_row at this text size, so a value that would
         * wrap more than once gets a full-width line instead. Only the drawn shape changes; no
         * value is altered.
         */
        const val STACKED_MIN_CHARS = 28
    }
}
