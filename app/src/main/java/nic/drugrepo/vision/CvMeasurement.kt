package nic.drugrepo.vision

import java.io.Serializable

/**
 * Structured output of the C++ CV stage (`app/src/main/cpp/cv_pipeline.cpp`), built by
 * `VisionNative.nativeProcessImage`.
 *
 * Plain data only: scalars plus three int arrays and one [CvColorimetryMeasurement]. No OpenCV
 * type, Mat, JSON string or free text crosses the JNI boundary (architecture.md section 3), and
 * nothing here carries a scientific claim on its own - these are measured pixel statistics, and
 * whether they mean anything is stated by [colorimetry].
 *
 * The arrays are flat because that is what JNI fills cheaply, and the typed accessors below are
 * the only supported way to read them. `regions` is the one flat array on purpose: its index
 * layout is fixed by cv_pipeline.h.
 *
 * Not a `data class`: three of its properties are arrays, and generated equals() on arrays is
 * identity comparison, which would make two measurements of the same frame compare unequal.
 */
class CvMeasurement(
    /**
     * Colour stage output: reference and reaction RGB before and after correction, CIE L*a*b*,
     * CIEDE2000, the presumptive classification, and which profile produced them.
     *
     * Present even when [status] is a failure, so a record can state which profile was looking.
     * Check [isValid] before reading any measured value out of it.
     */
    val colorimetry: CvColorimetryMeasurement,
    /** CvStatus: 0 = measured, anything negative = the values below are meaningless. */
    val status: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    /** CvGeometrySource: which region strategy produced [referenceRect]. */
    val geometrySource: Int,
    /** Measured Laplacian variance (architecture.md section 4.2). Not yet gated. */
    val laplacianVariance: Double,
    /** Measured fraction of near-saturated pixels. Not yet gated. */
    val glareFraction: Double,
    val referenceMeanB: Double,
    val referenceMeanG: Double,
    val referenceMeanR: Double,
    val reactionMeanB: Double,
    val reactionMeanG: Double,
    val reactionMeanR: Double,
    /** One ArUco marker id per detected marker; empty when none were found. */
    private val markerIds: IntArray,
    /** Four (x, y) pairs per detected marker, in ArUco corner order, in image pixels. */
    private val markerCorners: IntArray,
    /** { referenceX, referenceY, referenceW, referenceH, reactionX, reactionY, reactionW, reactionH }. */
    private val regions: IntArray,
) : Serializable {

    val markerCount: Int get() = markerIds.size

    /** Measured, or false: the caller must not present these numbers as a result. */
    val isValid: Boolean get() = status == STATUS_OK

    val markerIdList: List<Int> get() = markerIds.toList()

    val referenceRect: CvRect get() = CvRect(regions[0], regions[1], regions[2], regions[3])

    val reactionRect: CvRect get() = CvRect(regions[4], regions[5], regions[6], regions[7])

    val referenceMean: RgbMean get() = RgbMean(referenceMeanR, referenceMeanG, referenceMeanB)

    val reactionMean: RgbMean get() = RgbMean(reactionMeanR, reactionMeanG, reactionMeanB)

    fun markerCorner(index: Int): CvPoint = CvPoint(markerCorners[index * 2], markerCorners[index * 2 + 1])

    /** Human-readable failure reason, derived from [status]. Never claims a measurement. */
    val statusLabel: String
        get() = when (status) {
            STATUS_OK -> "measured"
            STATUS_EMPTY_INPUT -> "no image data"
            STATUS_DECODE_FAILED -> "image could not be decoded"
            STATUS_INVALID_GEOMETRY -> "image geometry rejected"
            STATUS_NO_MARKERS -> "no reference card markers found in frame"
            STATUS_UNEXPECTED_MARKER_IDS -> "markers found, but not this card's markers"
            STATUS_INCOMPLETE_MARKER_SET -> "not all four reference card markers were visible"
            STATUS_INVALID_HOMOGRAPHY -> "reference card could not be rectified"
            STATUS_ROI_OUTSIDE_IMAGE -> "reference card layout falls outside its own card"
            else -> "native CV error ($status)"
        }

    companion object {
        const val STATUS_OK = 0
        const val STATUS_EMPTY_INPUT = -1
        const val STATUS_DECODE_FAILED = -2
        const val STATUS_INVALID_GEOMETRY = -3
        const val STATUS_NO_MARKERS = -4
        const val STATUS_UNEXPECTED_MARKER_IDS = -5
        const val STATUS_INCOMPLETE_MARKER_SET = -6
        const val STATUS_INVALID_HOMOGRAPHY = -7
        const val STATUS_ROI_OUTSIDE_IMAGE = -8
        const val STATUS_CV_EXCEPTION = -100

        const val GEOMETRY_NONE = 0
        const val GEOMETRY_ARUCO_FULL = 1
        const val GEOMETRY_ARUCO_PARTIAL = 2
        const val GEOMETRY_CENTRE_FALLBACK = 3
        const val GEOMETRY_HOMOGRAPHY_RECTIFIED = 4

        private const val serialVersionUID = 2L
    }
}

/**
 * The colour stage (architecture.md section 4, steps 3 to 7): calibration, sRGB to linear light to
 * XYZ to CIE L*a*b*, CIEDE2000 against the profile's reference, and the boundary table's
 * presumptive outcome.
 *
 * Split out of [CvMeasurement] rather than flattened into it so the flat constructor stays
 * readable, and so the profile provenance travels with the numbers it describes.
 *
 * Every field here is a measurement or a status, never a claim. Whether the classification means
 * anything is [profileValidation]'s job, and [profileProvenance] exists so the UI can say where
 * the numbers came from instead of implying a validated kit.
 */
class CvColorimetryMeasurement(
    /** Index into the native profile table; see `reference_profile.cpp`. */
    val profileId: Int,
    /** ProfileValidation: 0 = UNVALIDATED placeholders, 1 = backed by authoritative kit data. */
    val profileValidation: Int,
    /**
     * ProfileKind: 0 = field data, 1 = SYNTHETIC demonstration data.
     *
     * This is the field a result's labelling keys off, and it is why it is an int on the JNI
     * boundary rather than something inferred from [profileVersion]: a synthetic profile's version
     * string could be edited to say something reassuring, whereas its kind is set once, in the
     * profile table, next to the data it applies to.
     */
    val profileKind: Int,
    /** CvClassification: 0 = inconclusive, 1 = presumptive positive, 2 = presumptive negative. */
    val classification: Int,
    /** Reference swatches actually measured in this frame. */
    val swatchCount: Int,
    /** Size of the rectified card the reference and reaction regions were measured on. */
    val rectifiedWidth: Int,
    val rectifiedHeight: Int,
    /**
     * SYNTHETIC DEMONSTRATION ONLY. True when the measured reaction colour sits within the
     * synthetic profile's demonstration tolerance of one of its demonstration anchors.
     *
     * Always false for a field profile, which holds no anchors to compare against, so this can never
     * be true for a casework result. It describes a colour in a synthetic dataset and nothing else.
     */
    val anchorMatch: Int,
    /** True only when a colour correction matrix was fitted and applied (architecture.md 4.4). */
    val ccmApplied: Boolean,
    /** CIEDE2000 with kL = kC = kH = 1, between the reference colour and the reaction colour. */
    val deltaE2000: Double,
    /** Reference sRGB means off the rectified card, before any correction. */
    val referenceRawR: Double,
    val referenceRawG: Double,
    val referenceRawB: Double,
    /** Reference sRGB means after correction. Equals the raw values when no CCM was applied. */
    val referenceR: Double,
    val referenceG: Double,
    val referenceB: Double,
    val reactionRawR: Double,
    val reactionRawG: Double,
    val reactionRawB: Double,
    val reactionR: Double,
    val reactionG: Double,
    val reactionB: Double,
    val referenceLabL: Double,
    val referenceLabA: Double,
    val referenceLabB: Double,
    val reactionLabL: Double,
    val reactionLabA: Double,
    val reactionLabB: Double,
    /**
     * SYNTHETIC DEMONSTRATION ONLY. CIEDE2000 from the measured reaction colour to the nearest
     * synthetic demonstration anchor. Zero for a field profile.
     *
     * This is distinct from [deltaE2000], which remains the comparison against the reference
     * patches measured in this same image and is what the field path uses. The anchor distance is
     * what a synthetic demonstration reports instead, and both are carried so neither is lost.
     */
    val anchorDeltaE2000: Double,
    /** Profile version string; becomes part of the record's reference_data_version. */
    val profileVersion: String,
    /** One sentence saying where the profile's numbers came from. */
    val profileProvenance: String,
    /** Reagent this card is designed for, e.g. MARQUIS. */
    val reagentType: String,
    /**
     * SYNTHETIC DEMONSTRATION ONLY. Which demonstration anchor the measured colour sits nearest,
     * e.g. a flow, a target name from the synthetic dataset and the expected colour phrase.
     * Empty for a field profile.
     *
     * A target name here identifies an entry in a synthetic demonstration dataset. It is not a
     * substance identified in a sample and must never be presented as one.
     */
    val anchorLabel: String,
) : Serializable {

    /** True when these numbers come from validated kit data rather than placeholders. */
    val isValidatedProfile: Boolean get() = profileValidation == VALIDATION_VALIDATED

    /**
     * True when this measurement was compared against SYNTHETIC demonstration anchors. Nothing
     * measured this way is a finding: it demonstrates that the pipeline runs, and every result
     * built on it must say so.
     */
    val isSyntheticProfile: Boolean get() = profileKind == PROFILE_KIND_SYNTHETIC_DEMO

    val referenceRaw: RgbMean get() = RgbMean(referenceRawR, referenceRawG, referenceRawB)

    val referenceCorrected: RgbMean get() = RgbMean(referenceR, referenceG, referenceB)

    val reactionRaw: RgbMean get() = RgbMean(reactionRawR, reactionRawG, reactionRawB)

    val reactionCorrected: RgbMean get() = RgbMean(reactionR, reactionG, reactionB)

    val referenceLab: LabValue get() = LabValue(referenceLabL, referenceLabA, referenceLabB)

    val reactionLab: LabValue get() = LabValue(reactionLabL, reactionLabA, reactionLabB)

    /** "POSITIVE", "NEGATIVE" or "INCONCLUSIVE". Always "presumptive" in user-facing text. */
    val classificationLabel: String
        get() = when (classification) {
            CLASSIFICATION_POSITIVE -> "PRESUMPTIVE POSITIVE"
            CLASSIFICATION_NEGATIVE -> "PRESUMPTIVE NEGATIVE"
            else -> "INCONCLUSIVE"
        }

    /**
     * True when the measured reaction colour sat within the synthetic profile's demonstration
     * tolerance of one of its demonstration anchors. Always false for a field profile.
     */
    val isDemonstrationAnchorMatch: Boolean get() = anchorMatch == 1

    /**
     * The demonstration outcome, for a synthetic profile only.
     *
     * Deliberately worded as DEMONSTRATIVE rather than PRESUMPTIVE: nothing measured against a
     * synthetic dataset is a presumptive finding, and a field profile returns an empty string so
     * this text cannot reach a casework screen at all.
     */
    val demonstrationLabel: String
        get() = when {
            !isSyntheticProfile -> ""
            anchorMatch == 1 -> "DEMONSTRATIVE MATCH"
            else -> "DEMONSTRATIVE NO MATCH"
        }

    companion object {
        const val VALIDATION_UNVALIDATED = 0
        const val VALIDATION_VALIDATED = 1

        /** ProfileKind.kField: source-backed reference data intended for real casework. */
        const val PROFILE_KIND_FIELD = 0

        /** ProfileKind.kSyntheticDemonstration: synthetic anchors, demonstration only. */
        const val PROFILE_KIND_SYNTHETIC_DEMO = 1

        const val CLASSIFICATION_INCONCLUSIVE = 0
        const val CLASSIFICATION_POSITIVE = 1
        const val CLASSIFICATION_NEGATIVE = 2

        private const val serialVersionUID = 1L
    }
}

/** Axis-aligned rectangle in image pixels. */
data class CvRect(val x: Int, val y: Int, val width: Int, val height: Int) : Serializable {
    /** True when this rectangle lies entirely inside [other]. */
    fun contains(other: CvRect): Boolean =
        other.x >= x && other.y >= y &&
            other.x + other.width <= x + width && other.y + other.height <= y + height
}

/** Single pixel coordinate. */
data class CvPoint(val x: Int, val y: Int) : Serializable

/** Mean of one region, in 8-bit sRGB channel units as decoded from the JPEG (0..255). */
data class RgbMean(val r: Double, val g: Double, val b: Double) : Serializable {
    /** Fixed 1-decimal formatting so a record never implies more precision than measured. */
    fun format(): String = String.format("R=%.1f G=%.1f B=%.1f", r, g, b)
}

/** CIE L*a*b* triple (D65). */
data class LabValue(val l: Double, val a: Double, val b: Double) : Serializable {
    /** Fixed 1-decimal formatting, matching [RgbMean.format]. */
    fun format(): String = String.format("L=%.1f a=%.1f b=%.1f", l, a, b)
}