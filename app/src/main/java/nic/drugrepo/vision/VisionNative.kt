package nic.drugrepo.vision

/**
 * Thin JNI bridge to the C++ analysis pipeline.
 *
 * Deliberately narrow (architecture.md section 3): the pipeline takes a frame buffer and
 * returns a plain result struct. No OpenCV type ever crosses this boundary.
 *
 * Declared as a Kotlin `object`, NOT a class with a companion object. A `@JvmStatic external`
 * function in a companion compiles to a *static* native method, which JNI resolves as
 * `Java_..._nativeVersion__` (double trailing underscore). Every member of a Kotlin `object`
 * is an instance method of that class, so it resolves as `Java_..._nativeVersion` and matches
 * the single exported symbol in vision_jni.cpp. Do not "tidy" this into a companion.
 */
object VisionNative {

    /**
     * Must equal `kVisionAbiVersion` in `src/main/cpp/vision_jni.h`.
     * Bumped whenever the C++ result struct or calling convention changes.
     *
     * 3 adds the colour stage: perspective rectification, calibrated Lab, CIEDE2000, the
     * presumptive classification, and the reference card profile version and validation status
     * those numbers depend on.
     *
     * 4 adds the reference profile index to [nativeProcessImage] and `profileKind` to the colour
     * stage. There is still exactly one pipeline: the index selects which reference DATA the same
     * measurement is compared against, and the kind is what the labelling downstream keys off.
     */
    const val VISION_ABI_VERSION = 4

    /** Table index of the source-backed field profile. The default for real casework. */
    const val PROFILE_INDEX_FIELD = 0

    /**
     * Table index of the synthetic demonstration profile. SYNTHETIC: any result produced with it
     * is a demonstration of the workflow and is never a presumptive finding.
     */
    const val PROFILE_INDEX_SYNTHETIC_DEMO = 1

    init {
        System.loadLibrary("drugvision")
    }

    external fun nativeVersion(): Int

    /**
     * TODO.md Phase 0 task 2. Version string of the OpenCV statically linked into the
     * native library, checked on-device against the pinned release.
     */
    external fun nativeOpenCvVersion(): String

    /**
     * TODO.md Phase 0 task 2. Proves the aruco module is linked and executable.
     * Returns the generated marker side in pixels, negative on failure.
     */
    external fun nativeArucoSelfTest(): Int

    /**
     * The real CV stage: JPEG bytes in, [CvMeasurement] out.
     *
     * All decoding, marker detection, homography rectification, ROI selection, calibration,
     * colour conversion, CIEDE2000 and classification happen in C++/OpenCV
     * (cv_pipeline.cpp). The only JNI work is unpacking the byte array and filling the
     * [CvColorimetryMeasurement] and [CvMeasurement] constructors - deliberately no colour maths
     * on this side, so there is exactly one implementation to test and no chance of two
     * disagreeing.
     *
     * [profileIndex] is an index into the reference profile table
     * (`app/src/main/cpp/reference_profile.cpp`): [PROFILE_INDEX_FIELD] for the source-backed NCB
     * profile, [PROFILE_INDEX_SYNTHETIC_DEMO] for the synthetic demonstration profile. The
     * measurement code is identical either way - the profile is data, not an algorithm - so this
     * does NOT switch pipeline, and the synthetic profile can never be reached from the field
     * default. An index outside the table is not a fallback: the stage runs with the field
     * profile, and the field profile's result is INCONCLUSIVE, which is the conservative answer.
     */
    external fun nativeProcessImage(jpeg: ByteArray, profileIndex: Int): CvMeasurement
}
