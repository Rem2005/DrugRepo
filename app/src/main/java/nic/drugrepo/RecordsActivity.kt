package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import nic.drugrepo.databinding.ActivityRecordsBinding
import nic.drugrepo.databinding.ItemRecordBinding
import nic.drugrepo.db.AuditableRecord
import nic.drugrepo.db.AuditableRecordDatabaseFactory
import nic.drugrepo.db.PresumptiveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class RecordsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Thread {
            try {
                val db = AuditableRecordDatabaseFactory.open(this)
                val records = runBlocking(Dispatchers.IO) { db.auditableRecordDao().getAllInSequenceOrder() }
                db.close()
                runOnUiThread {
                    if (records.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.listScroll.visibility = View.GONE
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.listScroll.visibility = View.VISIBLE
                        records.forEach { rec -> addRow(binding, rec) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.listScroll.visibility = View.GONE
                    binding.tvEmpty.text = "Error loading records (DEMO/MOCK)"
                }
            }
        }.start()

        binding.btnHome.setOnClickListener {
            finish()
        }

        binding.tvBack.setOnClickListener {
            finish()
        }
    }

    private fun addRow(binding: ActivityRecordsBinding, rec: AuditableRecord) {
        val row = ItemRecordBinding.inflate(layoutInflater, binding.recordList, false)
        row.tvRecordTitle.text = "Test #${rec.sequenceNumber}"
        row.tvRecordOfficer.text = rec.officerBadgeId
        row.tvRecordTime.text = rec.timestampUtc
        row.tvRecordResult.text = rec.presumptiveResult.name
        row.tvRecordResult.setTextColor(
            getColor(
                when (rec.presumptiveResult) {
                    PresumptiveResult.POSITIVE -> R.color.result_positive_text
                    PresumptiveResult.NEGATIVE -> R.color.result_negative_text
                    PresumptiveResult.INCONCLUSIVE -> R.color.result_inconclusive_text
                }
            )
        )
        row.root.setOnClickListener {
            val intent = Intent(this, DetailActivity::class.java)
            intent.putExtra("record", rec.recordId)
            intent.putExtra("detail", formatRecord(rec))
            startActivity(intent)
        }
        binding.recordList.addView(row.root)
    }

    private fun formatRecord(r: AuditableRecord): String {
        return "Record ID: ${r.recordId}\nSeq: ${r.sequenceNumber}\nOfficer: ${r.officerBadgeId}\n" +
            "Time: ${r.timestampUtc}\nStatus: ${r.analysisStatus}\nReagent: ${r.reagentType}\n" +
            "DeltaE00: ${r.ciede2000Result}\nResult: ${r.presumptiveResult}\n" +
            "Reference data: ${r.referenceDataVersion}\n" +
            "Raw image SHA-256: ${r.rawImageSha256}\n" +
            "Previous record hash: ${r.previousRecordHash}\n" +
            "Record hash: ${r.recordHash} (MOCK)\nSignature: ${r.digitalSignatureEcdsa} (MOCK)\n" +
            "GPS available: ${r.gpsMetadata.available}"
    }
}
