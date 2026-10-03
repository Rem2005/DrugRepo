package nic.drugrepo.analysis

import nic.drugrepo.db.PresumptiveResult
import java.io.File
import java.security.MessageDigest

class DemoTestAnalyzer : TestAnalyzer {
    override fun analyze(imageFile: File): AnalysisResult {
        val bytes = if (imageFile.exists() && imageFile.length() > 0) {
            try {
                imageFile.readBytes()
            } catch (e: Exception) {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val valDigest = (digest[0].toInt() and 0xFF shl 8) or (digest[1].toInt() and 0xFF)
        val sel = (valDigest % 3)
        val selected = when (sel) {
            0 -> PresumptiveResult.NEGATIVE
            1 -> PresumptiveResult.INCONCLUSIVE
            else -> PresumptiveResult.POSITIVE
        }
        val deltaE = String.format("%.4f", 1.5 + (valDigest % 1000) / 1000.0 * 3.0)
        return AnalysisResult(
            presumptiveResult = selected,
            ciede2000Result = deltaE,
            demo = true,
            message = "DEMO/MOCK analysis - Presumptive only; laboratory confirmation required"
        )
    }
}
