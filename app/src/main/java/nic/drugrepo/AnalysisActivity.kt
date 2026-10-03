package nic.drugrepo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import nic.drugrepo.analysis.DemoTestAnalyzer
import nic.drugrepo.analysis.RealCvTestAnalyzer
import nic.drugrepo.analysis.TestAnalyzer
import nic.drugrepo.databinding.ActivityAnalysisBinding
import java.io.File

class AnalysisActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DrugRepo)
        val binding = ActivityAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imagePath = intent.getStringExtra("image")
        val badgeId = intent.getStringExtra("badgeId") ?: "DEMO-0001"
        // RealCvTestAnalyzer is the production path: the captured JPEG goes through C++/OpenCV.
        // DemoTestAnalyzer stays reachable explicitly ("demo") as the hash-based fallback analyzer.
        val analyzer: TestAnalyzer = if (intent.getStringExtra("analyzer") == "demo") {
            DemoTestAnalyzer()
        } else {
            RealCvTestAnalyzer()
        }

        Thread {
            val result = analyzer.analyze(File(imagePath ?: ""))
            runOnUiThread {
                val intent = Intent(this, ResultActivity::class.java)
                intent.putExtra("result", result)
                intent.putExtra("badgeId", badgeId)
                intent.putExtra("imagePath", imagePath)
                startActivity(intent)
                finish()
            }
        }.start()
    }
}
