package nic.drugrepo.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import nic.drugrepo.analysis.RealCvTestAnalyzer
import nic.drugrepo.db.PresumptiveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Targeted checks for the real CV stage: the Kotlin/JNI boundary, the C++ pipeline behind it, and
 * the analyzer that wraps both.
 *
 * The fixture comes from the native harness: a card drawn in the profile's own card space and
 * warped into a 640x480 frame, carrying six flat grey swatches, a flat grey reaction window and
 * four generated ArUco markers. It is drawn in C++ because a valid ArUco marker can only be
 * rendered from the dictionary bits, and that renderer lives there.
 *
 * The fixture is a TEST FIXTURE for geometry and colour extraction. Its greys are arbitrary values
 * chosen to be distinguishable; they are not colours of any reagent, and the classification the
 * pipeline reports is a consequence of the provisional profile's boundary table, not a finding.
 *
 * Needs a device - the native library cannot load on the JVM - so this lives in androidTest. The
 * equivalent C++ asserts, including all 34 published CIEDE2000 pairs, run through NativeHarnessTest.
 */
@RunWith(AndroidJUnit4::class)
class NativeCvTest {

    // --- tests -----------------------------------------------------------------

    @Test
    fun capturedJpegIsRectifiedAndMeasured() {
        val measurement = NativeCv.processImage(syntheticCardJpeg())

        assertTrue("status was ${measurement.status} (${measurement.statusLabel})", measurement.isValid)
        assertEquals(640, measurement.imageWidth)
        assertEquals(480, measurement.imageHeight)

        // The homography path, not the old bounding box: the card was rectified to the profile's
        // own card size before anything was measured.
        assertEquals(
            "card should have been rectified by homography",
            CvMeasurement.GEOMETRY_HOMOGRAPHY_RECTIFIED,
            measurement.geometrySource,
        )
        assertEquals(listOf(1, 2, 3, 4), measurement.markerIdList.sorted())

        val colorimetry = measurement.colorimetry
        assertEquals(500, colorimetry.rectifiedWidth)
        assertEquals(500, colorimetry.rectifiedHeight)
        assertEquals(6, colorimetry.swatchCount)

        val reference = measurement.referenceRect
        val reactionRoi = measurement.reactionRect
        assertTrue("reference region was $reference", reference.width > 0 && reference.height > 0)
        assertTrue("reaction ROI was $reactionRoi", reactionRoi.width > 0 && reactionRoi.height > 0)
        // Regions are reported in rectified card pixels, so both must lie inside the card and must
        // not overlap: an overlapping reference would include the very sample being judged.
        val card = CvRect(0, 0, colorimetry.rectifiedWidth, colorimetry.rectifiedHeight)
        assertTrue("reference region must lie inside the rectified card", card.contains(reference))
        assertTrue("reaction ROI must lie inside the rectified card", card.contains(reactionRoi))
        assertTrue(
            "reference $reference must not overlap reaction ROI $reactionRoi",
            reference.x + reference.width <= reactionRoi.x ||
                reactionRoi.x + reactionRoi.width <= reference.x ||
                reference.y + reference.height <= reactionRoi.y ||
                reactionRoi.y + reactionRoi.height <= reference.y,
        )

        // The fixture's six swatches average to mid grey and its reaction window is flat mid grey,
        // both measured off the rectified card in the same pass.
        val reactionMean = measurement.reactionMean
        assertTrue("reaction mean was $reactionMean", reactionMean.r in 122.0..134.0)
        assertTrue(
            "reference mean ${measurement.referenceMean} should match the drawn swatch average",
            measurement.referenceMean.r in 124.0..136.0,
        )
    }

    @Test
    fun colorStageReportsLabAndDeltaE00FromThisFrame() {
        val colorimetry = NativeCv.processImage(syntheticCardJpeg()).colorimetry

        // Lab values, not placeholders: a mid grey sits near L*=54 under D65.
        assertTrue("L* was ${colorimetry.referenceLabL}", colorimetry.referenceLabL in 50.0..58.0)
        assertEquals("neutral grey must have zero chroma", 0.0, colorimetry.referenceLabA, 0.5)
        assertEquals("neutral grey must have zero chroma", 0.0, colorimetry.referenceLabB, 0.5)

        // The fixture's reaction window and swatch average are both mid grey, so dE00 must be
        // near zero rather than a number copied out of a table.
        assertTrue("dE00 was ${colorimetry.deltaE2000}", colorimetry.deltaE2000 < 5.0)

        // No colour correction matrix may be claimed while the profile has no authoritative
        // swatch colours.
        assertFalse(colorimetry.ccmApplied)
        assertEquals(colorimetry.reactionRawR, colorimetry.reactionR, 0.0)
    }

    @Test
    fun profileIdentityAndValidationTravelWithTheNumbers() {
        val colorimetry = NativeCv.processImage(syntheticCardJpeg()).colorimetry

        // A result is unreadable months later without these, and an unvalidated profile has to say
        // so in the version string, not only in a flag the UI might ignore.
        assertTrue(
            "version should be marked provisional: ${colorimetry.profileVersion}",
            colorimetry.profileVersion.contains("PROVISIONAL"),
        )
        assertFalse("profile must not claim validation", colorimetry.isValidatedProfile)
        assertTrue(
            "provenance must say where the numbers came from: ${colorimetry.profileProvenance}",
            colorimetry.profileProvenance.isNotBlank(),
        )
        assertEquals("MARQUIS", colorimetry.reagentType)
    }

    @Test
    fun aFrameWithNoCardFailsWithoutPublishingColours() {
        val measurement = NativeCv.processImage(blankFrameJpeg())

        assertFalse(measurement.isValid)
        assertEquals(CvMeasurement.STATUS_NO_MARKERS, measurement.status)
        assertEquals("no reference card markers found in frame", measurement.statusLabel)
        assertEquals(CvMeasurement.GEOMETRY_NONE, measurement.geometrySource)
        assertEquals(0.0, measurement.colorimetry.deltaE2000, 0.0)
        assertEquals(0.0, measurement.colorimetry.reactionRawR, 0.0)
    }

    @Test
    fun measurementsAreDeterministicForIdenticalBytes() {
        // AGENTS.md directive 6: same input bytes, same measurement.
        val jpeg = syntheticCardJpeg()
        val first = NativeCv.processImage(jpeg)
        val second = NativeCv.processImage(jpeg)

        assertTrue(first.isValid)
        assertEquals(first.imageWidth, second.imageWidth)
        assertEquals(first.imageHeight, second.imageHeight)
        assertEquals(first.markerIdList, second.markerIdList)
        assertEquals(first.referenceRect, second.referenceRect)
        assertEquals(first.reactionRect, second.reactionRect)
        assertEquals(first.referenceMean.format(), second.referenceMean.format())
        assertEquals(first.reactionMean.format(), second.reactionMean.format())
        assertEquals(first.colorimetry.deltaE2000, second.colorimetry.deltaE2000, 0.0)
        assertEquals(first.colorimetry.referenceLabL, second.colorimetry.referenceLabL, 0.0)
        assertEquals(first.colorimetry.classification, second.colorimetry.classification)
    }

    @Test
    fun invalidInputFailsSafelyWithoutThrowing() {
        val notAnImage = NativeCv.processImage(ByteArray(64) { 0x7 })
        assertFalse(notAnImage.isValid)
        assertEquals(CvMeasurement.STATUS_DECODE_FAILED, notAnImage.status)
        assertEquals(0, notAnImage.imageWidth)
        assertEquals(0.0, notAnImage.referenceMeanR, 0.0)

        assertEquals(CvMeasurement.STATUS_EMPTY_INPUT, NativeCv.processImage(ByteArray(0)).status)
        assertEquals(
            CvMeasurement.STATUS_EMPTY_INPUT,
            NativeCv.processImage(File("/does/not/exist.jpg")).status,
        )
    }

    @Test
    fun analyzerCarriesTheColourStageAndMarksTheProfileUnvalidated() {
        val file = File.createTempFile("drugrepo-cv", ".jpg")
        file.writeBytes(syntheticCardJpeg())
        file.deleteOnExit()

        val result = RealCvTestAnalyzer().analyze(file)

        val measurement = result.cvMeasurement
        assertTrue("analyzer returned no measurement", measurement != null && measurement.isValid)
        val colorimetry = measurement!!.colorimetry

        // dE00 is now computed, so it must be a number rather than the old "not computed".
        assertFalse(result.ciede2000Result == RealCvTestAnalyzer.NOT_COMPUTED)
        assertEquals(String.format("%.2f", colorimetry.deltaE2000), result.ciede2000Result)

        // The classification comes from the profile's table, so it is reported - but the profile
        // is placeholder data, so the result is still flagged demo and the outcome is only
        // presumptive.
        assertEquals(
            when (colorimetry.classification) {
                CvColorimetryMeasurement.CLASSIFICATION_POSITIVE -> PresumptiveResult.POSITIVE
                CvColorimetryMeasurement.CLASSIFICATION_NEGATIVE -> PresumptiveResult.NEGATIVE
                else -> PresumptiveResult.INCONCLUSIVE
            },
            result.presumptiveResult,
        )
        assertTrue("unvalidated profile must keep the demo flag set", result.demo)
        assertTrue("message must carry the measured means: ${result.message}", result.message.contains("mean"))
        assertTrue("message must state dE00: ${result.message}", result.message.contains("dE00"))
        assertTrue(
            "message must say the profile is unvalidated: ${result.message}",
            result.message.contains("UNVALIDATED"),
        )
    }

    @Test
    fun analyzerReportsAFailedCaptureAsAnHonestResult() {
        val file = File.createTempFile("drugrepo-cv-empty", ".jpg")
        file.writeBytes(blankFrameJpeg())
        file.deleteOnExit()

        val result = RealCvTestAnalyzer().analyze(file)

        assertEquals(PresumptiveResult.INCONCLUSIVE, result.presumptiveResult)
        assertEquals(RealCvTestAnalyzer.NOT_COMPUTED, result.ciede2000Result)
        assertTrue(result.message.contains("no reference card markers found in frame"))
    }

    /**
     * The fixture is rendered by the native harness rather than in Kotlin: a decodable ArUco
     * marker has to come from the dictionary bits, and duplicating that generator here would be a
     * second implementation that could silently disagree with the pipeline's own.
     *
     * The harness library only exists in debug builds (CMakeLists.txt, -DVISION_NATIVE_TEST=ON),
     * which is where instrumented tests run.
     */
    private companion object {
        init {
            System.loadLibrary("vision_native_test")
        }

        @JvmStatic
        private external fun syntheticCardJpeg(): ByteArray

        @JvmStatic
        private external fun blankFrameJpeg(): ByteArray
    }
}