package nic.drugrepo.analysis

import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.vision.CvMeasurement
import nic.drugrepo.vision.VisionNative
import java.io.Serializable

/**
 * Which reference profile the shared CV pipeline runs against.
 *
 * This is the whole of the real/synthetic separation on the Kotlin side: there is one pipeline and
 * one analyzer, and this selects which reference DATA the measurement is compared against. It is an
 * intent extra rather than a settings screen, because a mode selector the officer can flip is a
 * mode selector that will eventually be flipped in casework.
 *
 * Anything unrecognised - including an absent extra - resolves to [VisionNative.PROFILE_INDEX_FIELD].
 * The field path is the conservative one: it can only ever return INCONCLUSIVE, whereas a fallback
 * to synthetic would let a typo turn a field analysis into a demonstration result.
 */
object AnalysisProfile {

    /** Intent extra carrying [FIELD] or [SYNTHETIC_DEMO]. */
    const val EXTRA = "profile"

    const val FIELD = "field"
    const val SYNTHETIC_DEMO = "demo"

    /** Maps an [EXTRA] value to a `VisionNative` profile index. Unrecognised means field. */
    fun indexOf(profile: String?): Int = when (profile) {
        SYNTHETIC_DEMO -> VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO
        else -> VisionNative.PROFILE_INDEX_FIELD
    }
}

/**
 * What kind of analysis produced a result, and therefore what the result is allowed to claim.
 *
 * The two modes are the same pipeline run against different reference data, not two applications.
 * The distinction lives here rather than in the UI because it is the thing that must not be lost:
 * a demonstration result and a field result are different kinds of statement, and every screen,
 * record and certificate that renders one has to say which kind it is.
 */
enum class AnalysisMode {
    /**
     * REAL / FIELD MODE. Reference data is source-backed. Without validated numerical calibration
     * the outcome is INCONCLUSIVE and no real drug identification is performed.
     */
    FIELD,

    /**
     * SYNTHETIC DEMONSTRATION. Reference data is a synthetic colour dataset. May report
     * DEMONSTRATIVE POSITIVE or DEMONSTRATIVE NO MATCH, which is not a finding and must never be
     * written as one.
     */
    SYNTHETIC_DEMONSTRATION,
}

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
    /**
     * Defaults to [AnalysisMode.SYNTHETIC_DEMONSTRATION] so every construction site that predates
     * the field profile keeps its existing MOCK labelling. Only an analyzer that measured against
     * source-backed reference data may report [AnalysisMode.FIELD].
     */
    val mode: AnalysisMode = AnalysisMode.SYNTHETIC_DEMONSTRATION,
) : Serializable
