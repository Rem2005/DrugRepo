package nic.drugrepo.vision

import java.io.File

/**
 * The Kotlin side of the CV boundary: read the captured JPEG, hand the bytes to C++, return the
 * structured measurement.
 *
 * This is deliberately thin. It performs no image processing of its own - no decoding, no colour
 * maths, no thresholds - because the moment Kotlin starts measuring, there are two
 * implementations of the science and no single place to prove either one right
 * (architecture.md section 3).
 *
 * There is one measurement path, not two. [profileIndex] chooses which reference DATA the same
 * measurement is compared against, and it defaults to the source-backed field profile so that the
 * real casework path cannot reach synthetic anchors by forgetting an argument.
 *
 * Failures are values, not exceptions: a missing file, an unreadable file or a native error all
 * come back as an invalid [CvMeasurement] with a status code, so callers cannot accidentally
 * present an unmeasured frame as a result.
 */
object NativeCv {

    /**
     * Processes a captured JPEG file against the source-backed field profile - real casework.
     * Never throws.
     */
    fun processImage(file: File): CvMeasurement =
        processImage(file, VisionNative.PROFILE_INDEX_FIELD)

    /** Processes a captured JPEG file against a named reference profile. Never throws. */
    fun processImage(file: File, profileIndex: Int): CvMeasurement {
        val bytes = try {
            if (file.isFile && file.length() > 0) file.readBytes() else ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        } catch (e: OutOfMemoryError) {
            ByteArray(0)
        }
        return processImage(bytes, profileIndex)
    }

    /** Processes raw JPEG bytes against a named reference profile. Never throws. */
    fun processImage(
        bytes: ByteArray,
        profileIndex: Int = VisionNative.PROFILE_INDEX_FIELD,
    ): CvMeasurement = try {
        VisionNative.nativeProcessImage(bytes, profileIndex)
    } catch (e: UnsatisfiedLinkError) {
        failure(CvMeasurement.STATUS_CV_EXCEPTION)
    } catch (e: Exception) {
        failure(CvMeasurement.STATUS_DECODE_FAILED)
    }

    private fun failure(status: Int) = CvMeasurement(
        // The native layer never ran, so there is no profile version to report: an empty string
        // says "nothing was consulted", which is the truth, rather than naming a card that was
        // never looked at. Same for the kind - nothing was measured, so nothing claims to be a
        // demonstration either.
        colorimetry = CvColorimetryMeasurement(
            profileId = -1,
            profileValidation = CvColorimetryMeasurement.VALIDATION_UNVALIDATED,
            profileKind = CvColorimetryMeasurement.PROFILE_KIND_FIELD,
            classification = CvColorimetryMeasurement.CLASSIFICATION_INCONCLUSIVE,
            swatchCount = 0,
            rectifiedWidth = 0,
            rectifiedHeight = 0,
            ccmApplied = false,
            deltaE2000 = 0.0,
            referenceRawR = 0.0,
            referenceRawG = 0.0,
            referenceRawB = 0.0,
            referenceR = 0.0,
            referenceG = 0.0,
            referenceB = 0.0,
            reactionRawR = 0.0,
            reactionRawG = 0.0,
            reactionRawB = 0.0,
            reactionR = 0.0,
            reactionG = 0.0,
            reactionB = 0.0,
            referenceLabL = 0.0,
            referenceLabA = 0.0,
            referenceLabB = 0.0,
            reactionLabL = 0.0,
            reactionLabA = 0.0,
            reactionLabB = 0.0,
            profileVersion = "",
            profileProvenance = "native CV stage was not reached",
            reagentType = "",
        ),
        status = status,
        imageWidth = 0,
        imageHeight = 0,
        geometrySource = CvMeasurement.GEOMETRY_NONE,
        laplacianVariance = 0.0,
        glareFraction = 0.0,
        referenceMeanB = 0.0,
        referenceMeanG = 0.0,
        referenceMeanR = 0.0,
        reactionMeanB = 0.0,
        reactionMeanG = 0.0,
        reactionMeanR = 0.0,
        markerIds = IntArray(0),
        markerCorners = IntArray(0),
        regions = IntArray(8),
    )
}