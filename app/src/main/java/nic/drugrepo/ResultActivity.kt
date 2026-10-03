package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID

class ResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val result = intent.getSerializableExtra("result") as? AnalysisResult ?: AnalysisResult(
            nic.drugrepo.db.PresumptiveResult.INCONCLUSIVE,
            "0.0000",
            true,
            "DEMO/MOCK"
        )
        val badgeId = intent.getStringExtra("badgeId") ?: "DEMO-0001"
        val imagePath = intent.getStringExtra("imagePath")

        // The one thing this screen must never blur: a synthetic demonstration and a real field
        // result are different kinds of statement. Both headline and badge are derived from
        // AnalysisResult.mode, so no downstream screen can render a demonstration as a finding.
        val synthetic = result.mode == AnalysisMode.SYNTHETIC_DEMONSTRATION
        binding.tvResult.text = headline(result, synthetic)
        binding.tvResult.setTextColor(resultColor(result.presumptiveResult))
        binding.tvDemo.text = if (synthetic) SYNTHETIC_BADGE else FIELD_BADGE
        binding.tvDemo.setBackgroundTintList(
            ColorStateList.valueOf(
                getColor(if (synthetic) R.color.demo_bg else R.color.result_inconclusive_bg),
            ),
        )
        binding.tvDemo.setTextColor(
            getColor(if (synthetic) R.color.demo_text else R.color.result_inconclusive_text),
        )
        binding.tvOfficer.text = badgeId
        binding.tvDeltaE.text = "dE00 " + result.ciede2000Result
        binding.tvDetails.text = if (synthetic) {
            "$SYNTHETIC_WARNING\n\n${result.message}"
        } else {
            result.message
        }

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

    private fun createRecord(result: AnalysisResult, seq: Int, badgeId: String, imagePath: String?, prevHash: String): AuditableRecord {
        val deviceInfo = DeviceInformation(
            deviceId = UUID.randomUUID().toString(),
            imei = null,
            makeModel = "DemoDevice",
            osVersion = "Android 15",
            appVersion = "0.1.0-prototype",
            keySecurityLevel = try { AuditableRecordDatabaseFactory.open(this).let { db -> db.close(); KeySecurityLevel.TEE } } catch (e: Exception) { KeySecurityLevel.TEE },
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
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        return AuditableRecord(
            recordId = UUID.randomUUID().toString(),
            sequenceNumber = seq,
            officerBadgeId = badgeId,
            timestampUtc = now,
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
            algorithmVersion = if (result.cvMeasurement != null) "0.2.0-cv" else "1.0.0-demo",
            // The reference data version is the profile's own version, because the profile is
            // what the colour numbers came out of. The shipped one says PROVISIONAL in its string,
            // so a record can never imply a validated kit. The SHA demo path reads no reference
            // data at all and says MOCK-0.
            referenceDataVersion = colorimetry?.profileVersion ?: "MOCK-0",
            ciede2000Result = result.ciede2000Result,
            presumptiveResult = result.presumptiveResult,
            rawImageSha256 = try {
                java.security.MessageDigest.getInstance("SHA-256").let { md ->
                    val f = imagePath?.let { java.io.File(it) }
                    if (f != null && f.exists()) {
                        f.inputStream().use { ins ->
                            val buf = ByteArray(8192)
                            var r: Int
                            while (true) { r = ins.read(buf); if (r <= 0) break; md.update(buf, 0, r) }
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            } catch (e: Exception) { "0".repeat(64) },
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
                PresumptiveResult.POSITIVE -> "DEMONSTRATIVE POSITIVE"
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

    private companion object {
        /**
         * Badge text. Wording is fixed here rather than derived, so "SYNTHETIC" and "DEMONSTRATION
         * ONLY" cannot go missing from a synthetic result screen through a refactor.
         */
        const val SYNTHETIC_BADGE = "SYNTHETIC DEMONSTRATION - DEMONSTRATION ONLY"
        const val FIELD_BADGE = "FIELD / REAL MODE - UNVALIDATED"

        /** Prepended to the details of every synthetic result. Never shown for a field result. */
        const val SYNTHETIC_WARNING =
            "SYNTHETIC DATA. DEMONSTRATION ONLY. NOT A REAL DRUG TEST. " +
                "NOT FORENSICALLY VALIDATED. The colour anchors compared here are synthetic: they " +
                "are not measured from a physical NCB kit and are not official NCB data. This " +
                "result demonstrates that the CV colour-comparison workflow runs; it is not a " +
                "presumptive finding and no drug has been identified."
    }
}
