package nic.drugrepo.analysis

import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.vision.VisionNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The real/synthetic separation, asserted where it can be asserted without a device.
 *
 * The scientific content of the separation lives in C++ (`reference_profile.cpp`,
 * `cv_pipeline.cpp`) and is gated by the native harness. What is gated here is the part a careless
 * Kotlin edit could quietly break: that the field path is the default, that an unrecognised mode
 * fails closed rather than open, and that a result carries which kind of statement it is.
 *
 * There is one analyzer and one pipeline. [AnalysisProfile] selects reference DATA; it does not
 * select an algorithm, and nothing here may grow into a second code path.
 */
class AnalysisProfileTest {

    @Test
    fun fieldProfileIsTheDefaultAndUnknownModesFailClosed() {
        // An absent extra - the real casework flow - must reach the source-backed profile.
        assertEquals(VisionNative.PROFILE_INDEX_FIELD, AnalysisProfile.indexOf(null))
        assertEquals(VisionNative.PROFILE_INDEX_FIELD, AnalysisProfile.indexOf(""))
        // Anything unrecognised too. Falling back to the synthetic profile instead would let a typo
        // turn a field analysis into a demonstration result, which is the one failure mode a mode
        // switch must not have.
        assertEquals(VisionNative.PROFILE_INDEX_FIELD, AnalysisProfile.indexOf("synthetic"))
        assertEquals(VisionNative.PROFILE_INDEX_FIELD, AnalysisProfile.indexOf("DEMO"))
        assertEquals(
            VisionNative.PROFILE_INDEX_FIELD,
            AnalysisProfile.indexOf(AnalysisProfile.SYNTHETIC_DEMO.uppercase()),
        )
    }

    @Test
    fun onlyTheExplicitDemoExtraReachesTheSyntheticProfile() {
        assertEquals(
            VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO,
            AnalysisProfile.indexOf(AnalysisProfile.SYNTHETIC_DEMO),
        )
        // Two distinct table entries, not two spellings of one profile.
        assertNotEquals(
            VisionNative.PROFILE_INDEX_FIELD,
            VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO,
        )
    }

    @Test
    fun analyzerDefaultsToTheFieldProfile() {
        // RealCvTestAnalyzer() with no argument is the field path, and every construction site
        // that predates the mode switch relies on that. Read through the instance so the default
        // cannot be changed without this failing.
        val field = RealCvTestAnalyzer::class.java.getDeclaredField("profileIndex")
        field.isAccessible = true
        assertEquals(VisionNative.PROFILE_INDEX_FIELD, field.getInt(RealCvTestAnalyzer()))
        assertEquals(
            VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO,
            field.getInt(RealCvTestAnalyzer(VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO)),
        )
    }

    @Test
    fun resultsCarryWhichKindOfStatementTheyAre() {
        // SYNTHETIC_DEMONSTRATION is the default so every construction site that predates the
        // field profile keeps its MOCK labelling. Only a measurement against source-backed
        // reference data may claim FIELD.
        assertEquals(
            AnalysisMode.SYNTHETIC_DEMONSTRATION,
            AnalysisResult(PresumptiveResult.INCONCLUSIVE, "0.00").mode,
        )
        assertEquals(
            AnalysisMode.FIELD,
            AnalysisResult(PresumptiveResult.INCONCLUSIVE, "0.00", mode = AnalysisMode.FIELD).mode,
        )
        assertFalse(
            // FIELD must not be what an unlabelled result defaults to: that would put a real-drug
            // claim on every result constructed before the mode switch existed.
            AnalysisResult(PresumptiveResult.INCONCLUSIVE, "0.00").mode == AnalysisMode.FIELD,
        )
    }
}
