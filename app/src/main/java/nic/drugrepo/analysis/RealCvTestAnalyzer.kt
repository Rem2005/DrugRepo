package nic.drugrepo.analysis

import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.vision.CvColorimetryMeasurement
import nic.drugrepo.vision.CvMeasurement
import nic.drugrepo.vision.NativeCv
import nic.drugrepo.vision.VisionNative
import java.io.File

/**
 * Production-shaped analyzer: the captured JPEG goes to the C++/OpenCV pipeline and the reported
 * numbers come back measured from that image.
 *
 * What is real here: the decoded geometry, the ArUco marker ids actually found, the homography
 * rectification, the two region rectangles actually used, their mean colours, CIE L*a*b*, the
 * CIEDE2000 difference between the reference colour and the reaction colour, and the presumptive
 * outcome the profile's boundary table gives for that difference.
 *
 * There is ONE analyzer and ONE pipeline. The [profileIndex] constructor argument selects which
 * reference DATA the same measurement is compared against, and with it which claim the result may
 * make:
 *
 *  - [VisionNative.PROFILE_INDEX_FIELD] (the default, and the field path) uses the source-backed
 *    NCB profile (`app/src/main/cpp/reference_profile.cpp`). Its official qualitative colour names
 *    are protocol data, not calibration data, and no official source publishes the RGB, Lab or
 *    dE00 values a finding would need. So this path returns INCONCLUSIVE with the reason stated,
 *    and says so on every result: [AnalysisResult.demo] stays true while the profile is
 *    unvalidated, [AnalysisResult.mode] is [AnalysisMode.FIELD], and the message names the profile
 *    version and the missing calibration.
 *
 *  - [VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO] uses DEMO_SYNTHETIC_PROFILE_V1: a synthetic colour
 *    dataset whose only purpose is to demonstrate that this same pipeline executes end to end. It
 *    may produce DEMONSTRATIVE POSITIVE or DEMONSTRATIVE NO MATCH, both of which are labelled
 *    SYNTHETIC everywhere they appear and are not presumptive findings.
 *
 * The field path can never reach the synthetic profile's data, and the synthetic path can never
 * produce a field claim; see docs/CV_PIPELINE.md.
 *
 * The SHA-256-derived MOCK behaviour lives in [DemoTestAnalyzer], which remains the explicit
 * fallback analyzer; this class never hashes anything.
 */
class RealCvTestAnalyzer(
    private val profileIndex: Int = VisionNative.PROFILE_INDEX_FIELD,
) : TestAnalyzer {

    override fun analyze(imageFile: File): AnalysisResult {
        val measurement = NativeCv.processImage(imageFile, profileIndex)
        val colorimetry = measurement.colorimetry
        val synthetic = colorimetry.isSyntheticProfile
        return if (measurement.isValid) {
            AnalysisResult(
                presumptiveResult = toPresumptiveResult(colorimetry.classification),
                ciede2000Result = String.format("%.2f", colorimetry.deltaE2000),
                // Still demo while the profile is unvalidated: the dE00 is a real measurement but
                // the boundaries interpreting it are placeholders, so this must not read as a
                // validated result (AGENTS.md directive 7).
                demo = !colorimetry.isValidatedProfile,
                message = describe(measurement, synthetic),
                cvMeasurement = measurement,
                mode = if (synthetic) AnalysisMode.SYNTHETIC_DEMONSTRATION else AnalysisMode.FIELD,
            )
        } else {
            // No measurement is still an honest result: the officer is told the capture could
            // not be measured rather than being shown a colour that was never sampled.
            AnalysisResult(
                presumptiveResult = PresumptiveResult.INCONCLUSIVE,
                ciede2000Result = NOT_COMPUTED,
                demo = true,
                message = "CV measurement failed (${measurement.statusLabel}); " +
                    "no colour values were recorded",
                cvMeasurement = measurement,
                mode = if (synthetic) AnalysisMode.SYNTHETIC_DEMONSTRATION else AnalysisMode.FIELD,
            )
        }
    }

    private fun toPresumptiveResult(classification: Int): PresumptiveResult = when (classification) {
        CvColorimetryMeasurement.CLASSIFICATION_POSITIVE -> PresumptiveResult.POSITIVE
        CvColorimetryMeasurement.CLASSIFICATION_NEGATIVE -> PresumptiveResult.NEGATIVE
        else -> PresumptiveResult.INCONCLUSIVE
    }

    /**
     * Measured numbers, then the claim they support and the claim they do not. Everything before
     * the classification is read straight off the measurement; everything after it is labelled
     * unvalidated or synthetic so the two are never confused.
     */
    private fun describe(m: CvMeasurement, synthetic: Boolean): String {
        val colorimetry = m.colorimetry
        val geometry = when (m.geometrySource) {
            CvMeasurement.GEOMETRY_HOMOGRAPHY_RECTIFIED ->
                "rectified to ${colorimetry.rectifiedWidth}x${colorimetry.rectifiedHeight} " +
                    "from ArUco markers ${m.markerIdList}"
            CvMeasurement.GEOMETRY_ARUCO_FULL -> "4 ArUco markers ${m.markerIdList}"
            CvMeasurement.GEOMETRY_ARUCO_PARTIAL ->
                "${m.markerCount} ArUco markers ${m.markerIdList} (partial)"
            CvMeasurement.GEOMETRY_CENTRE_FALLBACK -> "no ArUco marker, centre fallback region"
            else -> "no reference region"
        }
        val calibration = if (colorimetry.ccmApplied) {
            "colour correction matrix applied"
        } else {
            "no colour correction (profile has no authoritative patch colours)"
        }
        val measurements = "Measured from the captured image (${m.imageWidth}x${m.imageHeight}). " +
            "Reference region ${m.referenceRect.width}x${m.referenceRect.height} mean " +
            "${colorimetry.referenceRaw.format()}; reaction ROI " +
            "${m.reactionRect.width}x${m.reactionRect.height} mean ${colorimetry.reactionRaw.format()}. " +
            "Reference Lab ${colorimetry.referenceLab.format()}; reaction Lab " +
            "${colorimetry.reactionLab.format()}; dE00 ${String.format("%.2f", colorimetry.deltaE2000)}. " +
            "Geometry: $geometry. Calibration: $calibration. "
        return if (synthetic) {
            measurements + SYNTHETIC_CLAIM.format(colorimetry.classificationLabel) +
                " Profile ${colorimetry.profileVersion} is SYNTHETIC DEMONSTRATION DATA: " +
                "mathematically derived from synthetic colour anchors, not measured from a " +
                "physical kit, not official NCB data, not forensically validated."
        } else {
            // The field path's conclusion is fixed by the science, not by this frame: with no
            // validated numerical calibration there is nothing to compare a dE00 against, so the
            // only defensible outcome is INCONCLUSIVE and the reason has to travel with it.
            measurements + "Outcome: ${colorimetry.classificationLabel}. Profile " +
                "${colorimetry.profileVersion} is UNVALIDATED: validated numerical calibration " +
                "unavailable, so NO REAL DRUG IDENTIFICATION WAS PERFORMED. " +
                "Reference data is source-backed qualitative protocol data only."
        }
    }

    companion object {
        /**
         * Reported in the dE00 slot when the capture never reached the colour stage. A blank
         * string would read as a formatting glitch; this states the absence outright.
         */
        const val NOT_COMPUTED = "not computed"

        /**
         * Opening the synthetic mode's claim sentence. SYNTHETIC and DEMONSTRATION ONLY appear in
         * every non-inconclusive synthetic message, so the wording cannot drift between screens.
         */
        private const val SYNTHETIC_CLAIM =
            "SYNTHETIC DEMONSTRATION ONLY - outcome: %s. NOT A REAL DRUG TEST. "
    }
}
