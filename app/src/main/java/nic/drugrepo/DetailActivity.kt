package nic.drugrepo

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import nic.drugrepo.databinding.ActivityDetailBinding
import nic.drugrepo.db.AuditableRecord
import nic.drugrepo.db.AuditableRecordDatabaseFactory
import nic.drugrepo.db.PresumptiveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class DetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val recordId = intent.getStringExtra("record")
        val fallback = intent.getStringExtra("detail")

        binding.btnBack.setOnClickListener {
            finish()
        }
        binding.tvBack.setOnClickListener {
            finish()
        }

        // Read the stored record back through the existing DAO. The string passed by
        // RecordsActivity stays as the fallback so the screen still works if the read fails.
        Thread {
            val record: AuditableRecord? = try {
                if (recordId == null) null else {
                    val db = AuditableRecordDatabaseFactory.open(this)
                    val rec = runBlocking(Dispatchers.IO) { db.auditableRecordDao().getByRecordId(recordId) }
                    db.close()
                    rec
                }
            } catch (e: Exception) {
                null
            }

            runOnUiThread {
                if (record == null) {
                    binding.tvDetail.visibility = View.VISIBLE
                    binding.tvDetail.text = fallback ?: "No details"
                    return@runOnUiThread
                }
                bind(binding, record)
            }
        }.start()
    }

    private fun bind(binding: ActivityDetailBinding, r: AuditableRecord) {
        binding.tvResult.text = r.presumptiveResult.name
        binding.tvResult.setTextColor(
            getColor(
                when (r.presumptiveResult) {
                    PresumptiveResult.POSITIVE -> R.color.result_positive_text
                    PresumptiveResult.NEGATIVE -> R.color.result_negative_text
                    PresumptiveResult.INCONCLUSIVE -> R.color.result_inconclusive_text
                }
            )
        )
        rows(
            binding.containerResult,
            listOf(
                "CIEDE2000" to r.ciede2000Result,
                "Reference data" to r.referenceDataVersion,
                "Status" to r.analysisStatus.name,
            )
        )
        rows(
            binding.containerInfo,
            listOf(
                "Record ID" to r.recordId,
                "Officer" to r.officerBadgeId,
                "Time (UTC)" to r.timestampUtc,
                "Reagent" to r.reagentType,
            )
        )
        rows(
            binding.containerCapture,
            listOf(
                "Raw image SHA-256" to r.rawImageSha256,
                "Reaction ROI RGB" to r.reactionRoiColorMeasurements.rawRgb,
                "Reaction / reference Lab" to r.reactionRoiColorMeasurements.calibratedLab,
                "Sharpness (Laplacian)" to r.imageQualityMetrics.laplacianVariance,
                "ArUco detected" to r.calibrationData.arucoDetected.toString(),
                "Reference swatches" to r.calibrationData.swatchCount.toString(),
                "CCM applied" to r.calibrationData.ccmApplied.toString(),
                "Sequence" to "#${r.sequenceNumber}",
                "Previous record hash" to r.previousRecordHash,
                "GPS available" to r.gpsMetadata.available.toString(),
            ),
            mono = true
        )
        rows(
            binding.containerSecurity,
            listOf(
                "Record hash (MOCK)" to r.recordHash,
                "Signature (MOCK)" to r.digitalSignatureEcdsa,
            ),
            mono = true
        )
    }

    /**
     * One row shape for a short value, the other for a long one.
     *
     * A record id, a SHA-256 and a stored measurement string are all far too long to sit in the
     * right-hand column of a side-by-side row: they wrap into a narrow block that is hard to read
     * and harder to compare. Those get the stacked layout with the value on its own full-width
     * line. The threshold is length, not which section it is in, so a long value can never be the
     * one that gets squeezed.
     */
    private fun rows(container: LinearLayout, pairs: List<Pair<String, String>>, mono: Boolean = false) {
        container.removeAllViews()
        val pad = resources.getDimensionPixelSize(R.dimen.row_gap)
        pairs.forEach { (label, value) ->
            val stacked = value.length > STACKED_MIN_CHARS
            val row = LayoutInflater.from(this).inflate(
                if (stacked) R.layout.item_result_note else R.layout.item_detail_row,
                container,
                false,
            )
            row.findViewById<TextView>(R.id.tvRowLabel).text = label
            val valueView = row.findViewById<TextView>(R.id.tvRowValue)
            valueView.text = value
            if (mono) {
                valueView.typeface = Typeface.MONOSPACE
            }
            (row.layoutParams as LinearLayout.LayoutParams).topMargin = pad
            container.addView(row)
        }
    }

    private companion object {
        /**
         * Wider than the value column of item_detail_row at this text size, so a value that would
         * wrap more than once is given its own line instead. Not a display concern: no value is
         * altered, only which of the two existing row shapes it is drawn with.
         */
        const val STACKED_MIN_CHARS = 28
    }
}