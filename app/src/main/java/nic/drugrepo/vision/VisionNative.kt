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
     */
    const val VISION_ABI_VERSION = 1

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
}