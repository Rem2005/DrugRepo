package nic.drugrepo

import androidx.test.ext.junit.runners.AndroidJUnit4
import nic.drugrepo.vision.VisionNative
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real proof of TODO.md Phase 0 tasks 1 and 2: a CMake-built shared object exists, is
 * packaged into the APK, and its exported JNI entry points are reachable from Kotlin.
 *
 * This is a cross-language contract test. It fails if:
 *   - the .so is missing, or the ABI filters do not cover the test device,
 *   - the .so fails to load (missing dependency, wrong STL, missing symbol),
 *   - the Kotlin class/method is renamed so a JNI symbol no longer resolves,
 *   - the Kotlin and C++ ABI version constants drift apart,
 *   - OpenCV is not linked, or a different OpenCV release was fetched than the one pinned
 *     in scripts/fetch-opencv.ps1 and vision_jni.h.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibLoadTest {

    @Test
    fun nativeLibraryLoadsAndAgreesOnAbiVersion() {
        assertEquals(VisionNative.VISION_ABI_VERSION, VisionNative.nativeVersion())
    }

    @Test
    fun linkedOpenCvIsThePinnedRelease() {
        assertEquals(EXPECTED_OPENCV_VERSION, VisionNative.nativeOpenCvVersion())
    }

    @Test
    fun arucoModuleIsLinkedAndExecutable() {
        val markerSide = VisionNative.nativeArucoSelfTest()
        // A negative result is the failure code from the C++ side: -1 empty dictionary,
        // -2 marker not rendered, -3 detector not built, -100/-101 exceptions.
        assertEquals("aruco self-test returned $markerSide", EXPECTED_MARKER_SIDE, markerSide)
    }

    private companion object {
        const val EXPECTED_OPENCV_VERSION = "4.14.0"
        const val EXPECTED_MARKER_SIDE = 64
    }
}