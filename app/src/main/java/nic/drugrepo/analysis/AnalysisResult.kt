package nic.drugrepo.analysis

import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.vision.CvMeasurement
import java.io.Serializable

data class AnalysisResult(
    val presumptiveResult: PresumptiveResult,
    val ciede2000Result: String,
    val demo: Boolean = true,
    val message: String = "DEMO/MOCK - Presumptive only",
    /**
     * The measured CV output, when the analysis actually came from an image
     * ([RealCvTestAnalyzer]). Null for [DemoTestAnalyzer].
     *
     * Optional and last so every existing construction site keeps compiling: this is the minimum
     * refactor needed to carry real measurements out of the analyzer without redesigning the
     * record schema or the Result screen around them.
     */
    val cvMeasurement: CvMeasurement? = null,
) : Serializable