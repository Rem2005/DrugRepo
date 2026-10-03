package nic.drugrepo

import nic.drugrepo.vision.CvColorimetryMeasurement
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
    fun abiVersionMatchesTheCxxHeader() {
        // 4 since the reference profile index was threaded through nativeProcessImage and the
        // colour stage began reporting ProfileKind. Kept as an explicit number rather than a regex
        // over the header so the JVM gate still runs where the C++ one cannot.
        assertEquals(4, VisionNative.VISION_ABI_VERSION)
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

        // Parameter types are declared per entry point: nativeProcessImage takes the JPEG bytes
        // and the reference profile index.
        val entryPoints = mapOf(
            "nativeOpenCvVersion" to emptyArray(),
            "nativeArucoSelfTest" to emptyArray(),
            "nativeProcessImage" to arrayOf(ByteArray::class.java, Int::class.javaPrimitiveType),
        )
        for ((name, parameterTypes) in entryPoints) {
            val method = type.getDeclaredMethod(name, *parameterTypes)
            assertFalse(
                "$name must stay an instance method for the same JNI mangling reason",
                java.lang.reflect.Modifier.isStatic(method.modifiers),
            )
        }
    }

    @Test
    fun colorimetryResultHasTheShapeTheJniConstructorExpects() {
        // The C++ side calls one constructor with an exact 30-argument descriptor
        // (kColorimetryCtorSignature). Reflection counts the same arguments here, so a Kotlin
        // signature change and the native descriptor cannot drift apart without a failure on the
        // JVM instead of NoSuchMethodError on a device mid-capture.
        val constructor = CvColorimetryMeasurement::class.java.constructors.single()
        val parameters = constructor.parameters
        assertEquals(30, parameters.size)
        // Seven ints: profileId, profileValidation, profileKind, classification, swatchCount,
        // rectifiedWidth, rectifiedHeight. A Double where an Int was declared would still compile
        // on both sides and only fail in JNI argument marshalling at runtime.
        for (index in 0..6) {
            assertEquals("parameter $index must be an Int", Int::class.java, parameters[index].type)
        }
        assertEquals(java.lang.Boolean.TYPE, parameters[7].type)
        // Nineteen doubles, then three strings.
        for (index in 8..26) {
            assertEquals(
                "parameter $index must be a Double",
                java.lang.Double.TYPE,
                parameters[index].type,
            )
        }
        for (index in 27..29) {
            assertEquals(
                "parameter $index must be a String",
                java.lang.String::class.java,
                parameters[index].type,
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
