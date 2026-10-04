package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import nic.drugrepo.databinding.ActivityHomeBinding
import nic.drugrepo.databinding.ItemRecordBinding
import nic.drugrepo.db.AuditableRecordDatabaseFactory
import nic.drugrepo.db.PresumptiveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewTest.setOnClickListener {
            startActivity(Intent(this, NewTestActivity::class.java))
        }

        binding.btnTestRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }

        binding.btnDemo.setOnClickListener {
            startActivity(Intent(this, DemoModeActivity::class.java))
        }

        loadRecent(binding)
    }

    /** Presentation only: the two most recent records, read through the existing DAO. */
    private fun loadRecent(binding: ActivityHomeBinding) {
        Thread {
            val records = try {
                val db = AuditableRecordDatabaseFactory.open(this)
                val all = runBlocking(Dispatchers.IO) { db.auditableRecordDao().getAllInSequenceOrder() }
                db.close()
                all.reversed().take(2)
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                if (records.isEmpty()) {
                    binding.tvRecentLabel.visibility = View.GONE
                    return@runOnUiThread
                }
                binding.tvRecentLabel.visibility = View.VISIBLE
                records.forEach { rec ->
                    val row = ItemRecordBinding.inflate(layoutInflater, binding.recentList, false)
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
                        startActivity(
                            Intent(this, DetailActivity::class.java).putExtra("record", rec.recordId)
                        )
                    }
                    binding.recentList.addView(row.root)
                }
            }
        }.start()
    }
}