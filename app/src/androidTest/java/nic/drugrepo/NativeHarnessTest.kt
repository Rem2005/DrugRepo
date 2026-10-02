package nic.drugrepo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TODO.md Phase 0 task 4. Runs the native C++ assert harness on the device itself.
 *
 * The harness (app/src/main/cpp/vision_test.cpp) exercises the statically linked OpenCV
 * core/imgproc/objdetect-aruco path with plain assert()s, and is built by the app's CMake
 * configuration into libvision_native_test.so for Debug builds only.
 *
 * It runs in-process rather than as a staged executable: Android forbids apps targeting SDK
 * 29+ from executing files out of their own home directory, so execve of a harness staged in
 * app storage always fails with EACCES regardless of file mode. System.loadLibrary has no
 * such restriction and also works under extractNativeLibs=false, where the .so is mapped
 * straight out of the APK and never appears on the filesystem.
 *
 * A failing assert() calls abort(), which crashes the app process. That surfaces as this
 * test erroring rather than failing on the assert message: deliberately loud, not silent.
 */
@RunWith(AndroidJUnit4::class)
class NativeHarnessTest {

    @Test
    fun nativeHarnessPassesOnDevice() {
        System.loadLibrary(HARNESS_LIB)

        assertEquals("native harness reported a failed assertion", 0, runNativeHarness())
    }

    private companion object {
        const val HARNESS_LIB = "vision_native_test"

        @JvmStatic
        private external fun runNativeHarness(): Int
    }
}
