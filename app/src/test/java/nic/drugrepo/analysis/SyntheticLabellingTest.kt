package nic.drugrepo.analysis

import nic.drugrepo.vision.CvColorimetryMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The labelling contract, tested without a device.
 *
 * `CvColorimetryMeasurement` is a plain data holder - no OpenCV, no JNI - so every field the UI and
 * the sealed record read for wording can be exercised on the JVM. These are the strings that decide
 * whether a result reads as a demonstration or a finding, and they are the one part of the
 * synthetic work that has nothing to do with pixels. The pixels are covered by
 * `NativeCvTest`; what can go wrong here is a wording rule, and that is testable anywhere.
 */
class SyntheticLabellingTest {

    @Test
    fun fieldProfileCannotReportAnAnchorEvenWithAMatchableColour() {
        // The most dangerous shape this data can take: a colour that sits on a demonstration anchor,
        // in a profile that is not a demonstration. If classification and anchor fields were derived
        // from the colour rather than from the profile kind, this object would say MATCH and a field
        // result would read as an identification.
        val field = colorimetry(
            kind = CvColorimetryMeasurement.PROFILE_KIND_FIELD,
            classification = CvColorimetryMeasurement.CLASSIFICATION_INCONCLUSIVE,
            anchorMatch = 0,
            anchorLabel = "",
            anchorDeltaE2000 = 0.0,
        )

        assertFalse(field.isSyntheticProfile)
        assertFalse(field.isDemonstrationAnchorMatch)
        // No wording, so there is nothing for a screen to leak into a casework view.
        assertEquals("", field.demonstrationLabel)
        assertEquals("INCONCLUSIVE", field.classificationLabel)
        assertEquals(0.0, field.anchorDeltaE2000, 0.0)
    }

    @Test
    fun syntheticMatchIsWordedAsADemonstrationNotAPresumptiveResult() {
        val synthetic = colorimetry(
            kind = CvColorimetryMeasurement.PROFILE_KIND_SYNTHETIC_DEMO,
            classification = CvColorimetryMeasurement.CLASSIFICATION_POSITIVE,
            anchorMatch = 1,
            anchorLabel = "FLOW I / TEST A / Amphetamines / #FFFF00",
            anchorDeltaE2000 = 0.03,
        )

        assertTrue(synthetic.isSyntheticProfile)
        assertTrue(synthetic.isDemonstrationAnchorMatch)
        assertEquals("DEMONSTRATIVE MATCH", synthetic.demonstrationLabel)
        // The boundary-table classification is still reported, and still carries "presumptive":
        // it is a boundary table verdict, not a substance.
        assertEquals("PRESUMPTIVE POSITIVE", synthetic.classificationLabel)
        assertEquals(0.03, synthetic.anchorDeltaE2000, 1e-9)
    }

    @Test
    fun syntheticMissIsNeverReportedAsAPresumptiveNegative() {
        // No-match in the synthetic set is the absence of a demonstration match, not evidence of
        // anything. The classifier can say NEGATIVE; the demonstration label must not.
        val synthetic = colorimetry(
            kind = CvColorimetryMeasurement.PROFILE_KIND_SYNTHETIC_DEMO,
            classification = CvColorimetryMeasurement.CLASSIFICATION_NEGATIVE,
            anchorMatch = 0,
            anchorLabel = "",
            anchorDeltaE2000 = 37.14,
        )

        assertEquals("DEMONSTRATIVE NO MATCH", synthetic.demonstrationLabel)
        assertEquals("PRESUMPTIVE NEGATIVE", synthetic.classificationLabel)
    }

    @Test
    fun validatedProfileIsReportedOnlyWhenTheProfileSaysSo() {
        assertFalse(
            colorimetry(profileValidation = CvColorimetryMeasurement.VALIDATION_UNVALIDATED)
                .isValidatedProfile
        )
        assertTrue(
            colorimetry(profileValidation = CvColorimetryMeasurement.VALIDATION_VALIDATED)
                .isValidatedProfile
        )
    }

    /**
     * Only the fields the wording rules read vary here. The rest are held at the values a measured
     * frame would produce, so a change to a constructor argument breaks every case in this file at
     * once instead of silently shifting one unrelated assertion.
     */
    private fun colorimetry(
        kind: Int = CvColorimetryMeasurement.PROFILE_KIND_SYNTHETIC_DEMO,
        profileValidation: Int = CvColorimetryMeasurement.VALIDATION_UNVALIDATED,
        classification: Int = CvColorimetryMeasurement.CLASSIFICATION_INCONCLUSIVE,
        anchorMatch: Int = 0,
        anchorLabel: String = "",
        anchorDeltaE2000: Double = 0.0,
    ) = CvColorimetryMeasurement(
        profileId = 0,
        profileValidation = profileValidation,
        profileKind = kind,
        classification = classification,
        swatchCount = 6,
        rectifiedWidth = 500,
        rectifiedHeight = 500,
        anchorMatch = anchorMatch,
        ccmApplied = false,
        deltaE2000 = 12.34,
        referenceRawR = 100.0, referenceRawG = 100.0, referenceRawB = 100.0,
        referenceR = 100.0, referenceG = 100.0, referenceB = 100.0,
        reactionRawR = 254.0, reactionRawG = 254.0, reactionRawB = 1.0,
        reactionR = 254.0, reactionG = 254.0, reactionB = 1.0,
        referenceLabL = 50.0, referenceLabA = 0.0, referenceLabB = 0.0,
        reactionLabL = 97.12, reactionLabA = -21.54, reactionLabB = 94.35,
        anchorDeltaE2000 = anchorDeltaE2000,
        profileVersion = "SYNTHETIC_DEMONSTRATION_ONLY-v1",
        profileProvenance = "SYNTHETIC DEMONSTRATION ONLY",
        reagentType = "SYNTHETIC_DEMONSTRATION_ONLY",
        anchorLabel = anchorLabel,
    )
}