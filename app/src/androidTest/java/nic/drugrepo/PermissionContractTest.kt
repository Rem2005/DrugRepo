package nic.drugrepo

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TODO.md Phase 0 task 5. Enforces the permission contract that the manifest comments claim
 * but cannot themselves guarantee.
 *
 * This is deliberately a required/forbidden contract, NOT an assertion that the manifest
 * declares exactly some fixed set. A later phase legitimately adds permissions (INTERNET for
 * the optional sync in architecture.md section 3), and pinning the whole list here would just
 * mean editing this test every time. What must never come back are the telephony permissions:
 * IMEI is unreadable by a normal app on Android 10+ (PRD.md FR-011), so declaring one would
 * collect an identifier that is useless and not obtainable.
 */
@RunWith(AndroidJUnit4::class)
class PermissionContractTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION") // GET_PERMISSIONS works from minSdk 26 up; the API 33+ flag form does not.
    private fun declaredPermissions(): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.toSet().orEmpty()
    }

    @Test
    fun requiredPermissionsAreDeclared() {
        val declared = declaredPermissions()

        REQUIRED_PERMISSIONS.forEach { permission ->
            assertTrue(
                "$permission must be declared; app declares $declared",
                permission in declared,
            )
        }
    }

    @Test
    fun telephonyPermissionsAreNeverDeclared() {
        val declared = declaredPermissions()

        FORBIDDEN_PERMISSIONS.forEach { permission ->
            assertFalse(
                "$permission must never be declared (PRD.md FR-011); app declares $declared",
                permission in declared,
            )
        }
    }

    @Test
    fun rationaleStringsArePresentAndNonEmpty() {
        RATIONALE_STRINGS.forEach { resId ->
            val text = context.getString(resId)
            assertNotNull("rationale string $resId is missing", text)
            assertTrue("rationale string $resId is empty", text.isNotBlank())
        }
    }

    private companion object {
        /**
         * ACCESS_COARSE_LOCATION is listed because it is not optional: on targetSdk 31+ a
         * FINE-only request is ignored by the platform and grants neither permission.
         */
        val REQUIRED_PERMISSIONS = setOf(
            "android.permission.CAMERA",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        )

        val FORBIDDEN_PERMISSIONS = setOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
        )

        val RATIONALE_STRINGS = listOf(
            R.string.permission_camera_rationale,
            R.string.permission_location_rationale,
        )
    }
}
