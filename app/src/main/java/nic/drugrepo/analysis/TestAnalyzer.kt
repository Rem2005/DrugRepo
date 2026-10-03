package nic.drugrepo.analysis

import java.io.File

interface TestAnalyzer {
    fun analyze(imageFile: File): AnalysisResult
}
