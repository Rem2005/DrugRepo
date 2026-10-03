package nic.drugrepo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nic.drugrepo.db.DatabaseKeyManager
import nic.drugrepo.db.DatabaseKeyUnavailableException
import nic.drugrepo.db.KeySecurityLevel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * TODO.md Phase 1 task 2 gate: the ledger key is Keystore-wrapped, never persisted in the
 * clear, never silently regenerated, and never software-backed.
 *
 * Needs a real device, because the Android Keystore does not exist on the JVM. Each test uses
 * its own alias and preferences file: instrumentation runs under this app's UID, so sharing the
 * production alias would delete a real officer's ledger key.
 *
 * Scope note, stated honestly: the test device has no StrongBox, so this exercises the TEE
 * fallback. The StrongBox branch is requested but is not claimed to be verified.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTestKeyMaterial()
    }

    @After
    fun tearDown() {
        deleteTestKeyMaterial()
    }

    @Test
    fun wrappedKeyIsStableAcrossManagerInstances() {
        val first = DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        val second = DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)

        assertEquals("the ledger key must be 256 bits", 32, first.size)
        assertArrayEquals(
            "the same wrapped key must unwrap to the same bytes, or the ledger becomes unreadable",
            first,
            second,
        )
    }

    @Test
    fun firstRunPersistsAWrappedBlobAndNoLaterRunRewritesIt() {
        assertTrue("nothing should be wrapped before the first run", wrappedKeyHex().isEmpty())

        DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        val afterFirstRun = wrappedKeyHex()

        // Version byte + 12-byte GCM IV + 32-byte key + 16-byte GCM tag, hex encoded.
        assertEquals("unexpected wrapped key layout", 2 * (1 + 12 + 32 + 16), afterFirstRun.length)
        assertTrue("the blob must start with the format version byte", afterFirstRun.startsWith("01"))

        DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        assertEquals(
            "an existing key must never be re-wrapped; a fresh blob would orphan the old one",
            afterFirstRun,
            wrappedKeyHex(),
        )
    }

    @Test
    fun wrappedKeyAndItsStoreContainNoPlaintextKey() {
        val key = DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)

        assertTrue(
            "the stored blob must not contain the plaintext key",
            indexOf(hexBytes(wrappedKeyHex()), key) < 0,
        )
        assertTrue(
            "the preferences file on disk must not contain the plaintext key",
            indexOf(prefsFileBytes(), key) < 0,
        )
    }

    @Test
    fun tamperedBlobFailsClosedAndLeavesTheStoredBlobAlone() {
        DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        val tampered = tamperLastByte(wrappedKeyHex())
        writeWrappedKeyHex(tampered)

        assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        }
        assertEquals(
            "a key that cannot be unwrapped must be reported, never silently replaced",
            tampered,
            wrappedKeyHex(),
        )
    }

    @Test
    fun unknownBlobVersionFailsClosed() {
        DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        writeWrappedKeyHex("02" + wrappedKeyHex().substring(2))

        assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)
        }
    }

    @Test
    fun wrappingKeyIsHardwareBacked() {
        DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)

        assertHardwareBacked(DatabaseKeyManager.achievedSecurityLevel(TEST_ALIAS))
    }

    @Test
    fun forgettingTheWrappedKeyLeavesTheKeystoreKeyAlone() {
        DatabaseKeyManager.unwrappedKey(context, TEST_ALIAS, TEST_PREFS)

        DatabaseKeyManager.forgetWrappedKey(context, TEST_PREFS)

        assertTrue("the blob must be gone", !DatabaseKeyManager.hasWrappedKey(context, TEST_PREFS))
        assertHardwareBacked(
            DatabaseKeyManager.achievedSecurityLevel(TEST_ALIAS),
        )
    }

    private fun assertHardwareBacked(level: KeySecurityLevel) {
        assertTrue(
            "architecture.md section 5 allows only StrongBox or TEE, got $level",
            level == KeySecurityLevel.STRONGBOX || level == KeySecurityLevel.TEE,
        )
    }

    private fun wrappedKeyHex(): String = prefs().getString(WRAPPED_KEY_PREF, "").orEmpty()

    private fun writeWrappedKeyHex(value: String) {
        assertTrue(prefs().edit().putString(WRAPPED_KEY_PREF, value).commit())
    }

    private fun prefs() = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)

    private fun prefsFileBytes(): ByteArray {
        val file = File(context.filesDir, "shared_prefs/$TEST_PREFS.xml")
        return if (file.exists()) file.readBytes() else ByteArray(0)
    }

    private fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Flips the last byte, which sits inside the GCM tag, so the tag check must reject it. */
    private fun tamperLastByte(hex: String): String {
        val last = hex.takeLast(2).toInt(16)
        return hex.dropLast(2) + "%02x".format(last xor 0xFF)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        return (0..haystack.size - needle.size).firstOrNull { start ->
            needle.indices.all { i -> haystack[start + i] == needle[i] }
        } ?: -1
    }

    private fun deleteTestKeyMaterial() {
        prefs().edit().clear().commit()
        keyStore().takeIf { it.containsAlias(TEST_ALIAS) }?.deleteEntry(TEST_ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        /**
         * Deliberately different from DatabaseKeyManager.KEY_ALIAS and PREFS_NAME: these tests
         * delete the key material in tearDown, which must never touch a real officer's ledger.
         */
        const val TEST_ALIAS = "drugrepo-db-key-test"
        const val TEST_PREFS = "drugrepo_key_store_test"

        /** Must match the key DatabaseKeyManager writes; its layout test depends on it. */
        const val WRAPPED_KEY_PREF = "wrapped_database_key_v1"
    }
}