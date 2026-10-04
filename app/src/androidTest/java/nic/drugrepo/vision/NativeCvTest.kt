package nic.drugrepo.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import nic.drugrepo.analysis.AnalysisMode
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
 * The fixtures come from the native harness: a card drawn in the profile's own card space and
 * warped into a 640x480 frame, carrying six flat swatches and four generated ArUco markers. It is
 * drawn in C++ because a valid ArUco marker can only be rendered from the dictionary bits, and
 * that renderer lives there.
 *
 * The swatches are flat greys, chosen to be distinguishable; they are not colours of any reagent.
 *
 * The reaction window comes in three variants, and which one a test uses is the whole point:
 *  - grey - reference and reaction differ by well under 2 dE00, which is what the colour-stage
 *    test needs. A grey reaction window CANNOT demonstrate an anchor decision, because no anchor in
 *    the dataset is grey.
 *  - #FFFF00 - sits on FLOW I / TEST A / Amphetamines / Start, so it must classify as a
 *    demonstration match.
 *  - #00B078 - nearest to every anchor at 37.1 dE00, so it must classify as a demonstration
 *    no-match.
 *
 * None of these is a finding. The colours are a synthetic demonstration dataset, and the
 * classification the pipeline reports is a consequence of that dataset, not of any substance.
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
            "version should be marked unvalidated: ${colorimetry.profileVersion}",
            colorimetry.profileVersion.contains("UNVALIDATED"),
        )
        assertFalse("profile must not claim validation", colorimetry.isValidatedProfile)
        assertTrue(
            "provenance must say where the numbers came from: ${colorimetry.profileProvenance}",
            colorimetry.profileProvenance.isNotBlank(),
        )
        // The default is the source-backed field profile, never the demonstration one.
        assertEquals(CvColorimetryMeasurement.PROFILE_KIND_FIELD, colorimetry.profileKind)
        assertFalse(colorimetry.isSyntheticProfile)
        assertEquals("NCB_STANDARD_NARCOTICS_DD_KIT", colorimetry.reagentType)
        // Official sources supply colour names and no numbers, so the numbers here are not from
        // them and the version string must not imply otherwise.
        assertTrue(
            "provenance must disclaim numerical calibration: ${colorimetry.profileProvenance}",
            colorimetry.profileProvenance.contains("NUMERICAL CALIBRATION: UNVALIDATED"),
        )
    }

    @Test
    fun theSamePipelineServesTheSyntheticProfileAndLabelsIt() {
        val measurement = NativeCv.processImage(
            syntheticCardJpeg(),
            VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO,
        )
        assertTrue("status was ${measurement.status}", measurement.isValid)
        val colorimetry = measurement.colorimetry

        // Same geometry, same colour maths, one pipeline. Only the profile and therefore the
        // labelling differs.
        val field = NativeCv.processImage(syntheticCardJpeg()).colorimetry
        assertEquals(field.rectifiedWidth, colorimetry.rectifiedWidth)
        assertEquals(field.swatchCount, colorimetry.swatchCount)
        assertEquals(field.deltaE2000, colorimetry.deltaE2000, 0.0)

        assertEquals(
            CvColorimetryMeasurement.PROFILE_KIND_SYNTHETIC_DEMO,
            colorimetry.profileKind,
        )
        assertTrue(colorimetry.isSyntheticProfile)
        assertFalse(colorimetry.isValidatedProfile)
        // The same bytes through the field profile: identical measurements, refused classification.
        // That single difference is the separation.
        assertEquals(CvColorimetryMeasurement.PROFILE_KIND_FIELD, field.profileKind)
        assertFalse(field.isSyntheticProfile)
        // The field path publishes no anchor at all, so there is nothing on a field measurement for
        // any screen to render as a demonstration. These three are the last line of defence: even if
        // a future UI read anchorLabel or anchorDeltaE2000 unconditionally, the field profile gives
        // it an empty string and a zero rather than a demonstration target.
        assertFalse(
            "the field profile holds no demonstration anchors, so it must report no match",
            field.isDemonstrationAnchorMatch,
        )
        assertEquals("", field.anchorLabel)
        assertEquals(0.0, field.anchorDeltaE2000, 0.0)
        assertEquals("", field.demonstrationLabel)
        assertEquals(
            "the field profile has no validated calibration, so it may only be INCONCLUSIVE",
            CvColorimetryMeasurement.CLASSIFICATION_INCONCLUSIVE,
            field.classification,
        )

        // These are the strings a sealed record and a screen both key off months later.
        assertEquals(
            "a demonstration profile must not name a reagent",
            "SYNTHETIC_DEMONSTRATION_ONLY",
            colorimetry.reagentType,
        )
        assertTrue(
            "version must say synthetic: ${colorimetry.profileVersion}",
            colorimetry.profileVersion.contains("SYNTHETIC_DEMONSTRATION_ONLY"),
        )
        // A demonstration profile exists to demonstrate classification. The fixture is grey on grey
        // so its reference-versus-reaction dE00 sits inside the demonstration match band, and the
        // same bytes through the field profile above were refused. Same pipeline, same maths,
        // different reference data - which is the whole point of the separation.
        assertTrue(
            "fixture dE00 ${colorimetry.deltaE2000} should be inside the demonstration match band",
            colorimetry.deltaE2000 <= DEMONSTRATION_MATCH_AT_OR_BELOW,
        )
        // The classification claim needs the chromatic fixture, not the grey one. No anchor in the
        // dataset is grey, so the nearest anchor to this fixture's grey reaction window sits far
        // outside the demonstration tolerance and NO MATCH is the correct outcome for it. Asserting
        // POSITIVE here would be asserting that the pipeline invents a match out of a colour that
        // belongs to nothing.
        val chromaticMeasurement = NativeCv.processImage(
            syntheticYellowCardJpeg(),
            VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO,
        )
        assertTrue(
            "chromatic fixture status was ${chromaticMeasurement.status}",
            chromaticMeasurement.isValid,
        )
        val chromatic = chromaticMeasurement.colorimetry
        assertEquals(
            "a reaction colour sitting on an anchor must report a demonstration match",
            1,
            chromatic.anchorMatch,
        )
        assertTrue(
            "the named anchor must be the one the painted colour belongs to: ${chromatic.anchorLabel}",
            chromatic.anchorLabel.contains("Amphetamines"),
        )
        assertEquals(
            CvColorimetryMeasurement.CLASSIFICATION_POSITIVE,
            chromatic.classification,
        )
        // No anchor distance bound is restated here. The tolerance lives in reference_profile.cpp as
        // syntheticAnchorMatchAtOrBelow; a second copy in Kotlin could disagree with it without
        // anything failing, which is exactly the wrong failure mode for a boundary a demonstration
        // result turns on. The C++ anchor test asserts the number itself; this asserts the wiring.
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
    fun fieldAnalyzerStaysInconclusiveAndSaysWhy() {
        val file = File.createTempFile("drugrepo-cv", ".jpg")
        file.writeBytes(syntheticCardJpeg())
        file.deleteOnExit()

        val result = RealCvTestAnalyzer().analyze(file)

        val measurement = result.cvMeasurement
        assertTrue("analyzer returned no measurement", measurement != null && measurement.isValid)
        val colorimetry = measurement!!.colorimetry

        // dE00 is computed, so it must be a number rather than the old "not computed".
        assertFalse(result.ciede2000Result == RealCvTestAnalyzer.NOT_COMPUTED)
        assertEquals(String.format("%.2f", colorimetry.deltaE2000), result.ciede2000Result)

        // The default analyzer is the field path, and the field profile has no validated numerical
        // calibration. So the outcome is INCONCLUSIVE even though the fixture's colour is inside
        // the pipeline's demonstration band - and the record is marked FIELD, not demo.
        assertEquals(AnalysisMode.FIELD, result.mode)
        assertEquals(
            "no validated calibration may yield a field finding",
            PresumptiveResult.INCONCLUSIVE,
            result.presumptiveResult,
        )
        // demo stays true here for a different reason than in demo mode: the measurement is real
        // but the boundaries interpreting it are placeholders, so the record must not be readable
        // as a validated finding. What it must NOT become is synthetic wording.
        assertTrue(
            "an unvalidated profile must keep the demo flag set",
            result.demo,
        )
        assertTrue("message must carry the measured means: ${result.message}", result.message.contains("mean"))
        assertTrue("message must state dE00: ${result.message}", result.message.contains("dE00"))
        assertTrue(
            "message must say the profile is unvalidated: ${result.message}",
            result.message.contains("UNVALIDATED"),
        )
        assertFalse(
            "the field path must not borrow synthetic wording: ${result.message}",
            result.message.contains("SYNTHETIC"),
        )
    }

    @Test
    fun demonstrationAnalyzerClassifiesAndLabelsEveryResultSynthetic() {
        // The chromatic fixture, not the grey one: this test is about the classification resolving,
        // and a grey reaction window belongs to no anchor, so it can only ever resolve to NO MATCH.
        val file = File.createTempFile("drugrepo-cv-demo", ".jpg")
        file.writeBytes(syntheticYellowCardJpeg())
        file.deleteOnExit()

        val result = RealCvTestAnalyzer(VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO).analyze(file)

        assertEquals(AnalysisMode.SYNTHETIC_DEMONSTRATION, result.mode)
        assertTrue("demo flag must be set", result.demo)
        // The point of the demonstration mode: the colour comparison actually resolves.
        assertEquals(
            "a synthetic match must be reported, not suppressed",
            PresumptiveResult.POSITIVE,
            result.presumptiveResult,
        )
        assertFalse(result.ciede2000Result == RealCvTestAnalyzer.NOT_COMPUTED)
        // Even a POSITIVE outcome may not read as a finding in any of the strings a record or a
        // certificate carries.
        assertTrue(
            "message must disclaim the demonstration: ${result.message}",
            result.message.contains("DEMONSTRATION"),
        )
        assertFalse(
            "the synthetic reagent field must never name a reagent: ${result.message}",
            result.message.contains("NCB_STANDARD_NARCOTICS_DD_KIT"),
        )
        // The intent is that the message makes no identification CLAIM. A substring test for the
        // bare word "identified" cannot tell a claim apart from the sentence that denies one, and
        // the analyzer does deny one outright ("not a substance identified in a sample"). So the
        // positive form is asserted instead: the denial must be present. Keeping the negative
        // would have pushed the fix into the message, deleting the disclaimer to satisfy a word
        // search - the wrong direction for a compliance string.
        assertTrue(
            "message must deny identification outright: ${result.message}",
            result.message.contains("not a substance identified in a sample"),
        )

        // The other half of "classifies": a reaction colour belonging to no anchor must come back
        // NO MATCH, and must carry exactly the same demonstration disclaimers. Without this the
        // test above would still pass on a pipeline that reported POSITIVE for every colour, which
        // is the failure mode the synthetic/field separation exists to make impossible.
        val offAnchorFile = File.createTempFile("drugrepo-cv-demo-nomatch", ".jpg")
        offAnchorFile.writeBytes(syntheticTealCardJpeg())
        offAnchorFile.deleteOnExit()

        val offAnchor = RealCvTestAnalyzer(VisionNative.PROFILE_INDEX_SYNTHETIC_DEMO)
            .analyze(offAnchorFile)

        assertEquals(AnalysisMode.SYNTHETIC_DEMONSTRATION, offAnchor.mode)
        assertEquals(
            "a colour belonging to no anchor must not be reported as a match",
            PresumptiveResult.NEGATIVE,
            offAnchor.presumptiveResult,
        )
        assertEquals(0, offAnchor.cvMeasurement!!.colorimetry.anchorMatch)
        assertTrue(
            "message must disclaim the demonstration: ${offAnchor.message}",
            offAnchor.message.contains("DEMONSTRATION"),
        )
        assertTrue(
            "a demonstration no-match must deny identification outright: ${offAnchor.message}",
            offAnchor.message.contains("not a substance identified in a sample"),
        )
    }

    @Test
    fun analyzerReportsAFailedCaptureAsAnHonestResult() {
        val file = File.createTempFile("drugrepo-cv-empty", ".jpg")
        file.writeBytes(blankFrameJpeg())
        file.deleteOnExit()

        val result = RealCvTestAnalyzer().analyze(file)

        assertEquals(PresumptiveResult.INCONCLUSIVE, result.presumptiveResult)
        assertEquals(AnalysisMode.FIELD, result.mode)
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

        /**
         * The same card with a real demonstration colour in the reaction window, and its
         * no-match counterpart. The grey fixture above cannot exercise an anchor decision, because
         * no anchor in the dataset is grey; these two can, and they are painted with the same
         * colours the C++ anchor tests use, so a Kotlin match and a C++ match are the same match.
         */
        @JvmStatic
        private external fun syntheticYellowCardJpeg(): ByteArray

        @JvmStatic
        private external fun syntheticTealCardJpeg(): ByteArray

        @JvmStatic
        private external fun blankFrameJpeg(): ByteArray

        /**
         * The demonstration profile's match band, restated so a test failure says which side of a
         * number moved. If the fixture's dE00 ever drifts above it, the synthetic-classification
         * assertion below must fail with that visible rather than as a bare classification mismatch.
         */
        private const val DEMONSTRATION_MATCH_AT_OR_BELOW = 2.0
    }
}