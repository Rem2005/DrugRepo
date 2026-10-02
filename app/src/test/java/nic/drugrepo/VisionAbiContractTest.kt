package nic.drugrepo

import nic.drugrepo.vision.VisionNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TODO.md Phase 0 task 4: the pure-JVM half of the test scaffolding.
 *
 * A native library cannot be loaded on the JVM, so everything that genuinely needs a device
 * lives in the androidTest suite. What *is* checkable without a device is the shape of the
 * cross-language contract - and getting it wrong is exactly what broke this project once
 * already: a `@JvmStatic external` function inside a companion compiles to a static native
 * method, so JNI looks for `Java_..._nativeVersion__` with two trailing underscores and the
 * binding fails at runtime with UnsatisfiedLinkError. VisionNative is therefore an `object`,
 * whose members are instance methods and resolve as `Java_..._nativeVersion`.
 *
 * These reflection assertions pin that choice down, so a future refactor back to a companion
 * class fails on the JVM in seconds instead of on a phone in the field.
 */
class VisionAbiContractTest {

    @Test
    fun abiVersionIsOne() {
        assertEquals(1, VisionNative.VISION_ABI_VERSION)
    }

    @Test
    fun nativeEntryPointsAreInstanceMethodsOnTheObject() {
        val type = VisionNative::class.java

        val version = type.getDeclaredMethod("nativeVersion")
        assertFalse(
            "nativeVersion must stay an instance method; a static method (for example from " +
                "@JvmStatic in a companion) would need the JNI symbol ..._nativeVersion__",
            java.lang.reflect.Modifier.isStatic(version.modifiers),
        )

        for (name in listOf("nativeOpenCvVersion", "nativeArucoSelfTest")) {
            val method = type.getDeclaredMethod(name)
            assertFalse(
                "$name must stay an instance method for the same JNI mangling reason",
                java.lang.reflect.Modifier.isStatic(method.modifiers),
            )
        }
    }

    @Test
    fun visionNativeIsASingletonObjectNotAClassWithCompanion() {
        // An `object` exposes a static INSTANCE field; a plain class does not. This is the
        // structural difference the JNI symbol naming depends on.
        assertTrue(
            "VisionNative must remain a Kotlin object (it must expose INSTANCE)",
            VisionNative::class.java.declaredFields.any { it.name == "INSTANCE" },
        )
    }
}
