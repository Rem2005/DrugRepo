package nic.drugrepo.analysis

import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.vision.CvColorimetryMeasurement
import nic.drugrepo.vision.CvMeasurement
import nic.drugrepo.vision.NativeCv
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
 * What is NOT real yet: the profile. The shipped card profile
 * (`app/src/main/cpp/reference_profile.cpp`) carries placeholder marker ids, swatch positions, a
 * placeholder reaction window and demonstration dE00 boundaries, all flagged UNVALIDATED. So the
 * numbers are measurements and the classification is provisional, and this class says so on every
 * result it returns: [AnalysisResult.demo] stays true while the profile is unvalidated, and the
 * message names the profile version.
 *
 * Replacing the placeholders with validated kit data turns demo off here and nowhere else.
 *
 * The SHA-256-derived demo behaviour lives in [DemoTestAnalyzer], which remains the explicit
 * fallback analyzer; this class never hashes anything.
 */
class RealCvTestAnalyzer : TestAnalyzer {

    override fun analyze(imageFile: File): AnalysisResult {
        val measurement = NativeCv.processImage(imageFile)
        val colorimetry = measurement.colorimetry
        return if (measurement.isValid) {
            AnalysisResult(
                presumptiveResult = toPresumptiveResult(colorimetry.classification),
                ciede2000Result = String.format("%.2f", colorimetry.deltaE2000),
                // Still demo while the profile is unvalidated: the dE00 is a real measurement but
                // the boundaries interpreting it are placeholders, so this must not read as a
                // validated result (AGENTS.md directive 7).
                demo = !colorimetry.isValidatedProfile,
                message = describe(measurement),
                cvMeasurement = measurement,
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
     * unvalidated so the two are never confused.
     */
    private fun describe(m: CvMeasurement): String {
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
        val validity = if (colorimetry.isValidatedProfile) {
            "profile ${colorimetry.profileVersion} is validated"
        } else {
            "profile ${colorimetry.profileVersion} is UNVALIDATED placeholder data"
        }
        return "Measured from the captured image (${m.imageWidth}x${m.imageHeight}). " +
            "Reference region ${m.referenceRect.width}x${m.referenceRect.height} mean " +
            "${colorimetry.referenceRaw.format()}; reaction ROI " +
            "${m.reactionRect.width}x${m.reactionRect.height} mean ${colorimetry.reactionRaw.format()}. " +
            "Reference Lab ${colorimetry.referenceLab.format()}; reaction Lab " +
            "${colorimetry.reactionLab.format()}; dE00 ${String.format("%.2f", colorimetry.deltaE2000)}. " +
            "Geometry: $geometry. Calibration: $calibration. " +
            "Outcome: ${colorimetry.classificationLabel}, presumptive only. $validity."
    }

    companion object {
        /**
         * Reported in the dE00 slot when the capture never reached the colour stage. A blank
         * string would read as a formatting glitch; this states the absence outright.
         */
        const val NOT_COMPUTED = "not computed"
    }
}