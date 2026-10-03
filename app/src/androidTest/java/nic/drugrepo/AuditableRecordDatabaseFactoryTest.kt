package nic.drugrepo

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nic.drugrepo.db.AuditableRecordDatabase
import nic.drugrepo.db.AuditableRecordDatabaseFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * TODO.md Phase 1 task 2 gate: the real ledger opens only through a Keystore-wrapped key and
 * is encrypted at rest (PRD.md FR-009).
 *
 * This replaces the deleted Phase 0 probe. The probe proved SQLCipher could be wired up with a
 * hard-coded test passphrase; this proves the same encryption now comes from the Keystore, that
 * the passphrase survives an app restart, and that a wrong key still cannot read the file.
 *
 * Room opens the file lazily on first query, so each test touches `openHelper.writableDatabase`
 * to force a real connection. With no DAO yet that first open is also what makes Room write its
 * schema, which is why the encrypted header is present by the time the file is inspected.
 */
@RunWith(AndroidJUnit4::class)
class AuditableRecordDatabaseFactoryTest {

    private lateinit var context: Context
    private var database: AuditableRecordDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTestState()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        deleteTestState()
    }

    @Test
    fun databaseFileIsEncryptedAtRest() {
        context.deleteDatabase(TEST_DB)
        database = openLedger()
        database!!.openHelper.writableDatabase

        assertTrue(
            "the ledger file still carries the plaintext SQLite magic header",
            isEncryptedAtRest(context, TEST_DB),
        )
    }

    @Test
    fun reopeningAfterCloseSucceedsWithThePersistedKey() {
        context.deleteDatabase(TEST_DB)
        database = openLedger()
        database!!.openHelper.writableDatabase
        database!!.close()
        database = null

        // A second open unwraps the stored key again. If it produced different bytes, SQLCipher
        // could not decrypt the existing file and this would fail - which is exactly the
        // "works offline across app restarts" requirement PRD.md FR-009 asks for.
        database = openLedger()
        database!!.openHelper.writableDatabase
    }

    @Test
    fun wrongKeyCannotOpenTheDatabase() {
        database = openLedger()
        database!!.openHelper.writableDatabase
        database!!.close()
        database = null

        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val impostor = Room.databaseBuilder(context.applicationContext, AuditableRecordDatabase::class.java, TEST_DB)
            .openHelperFactory(SupportOpenHelperFactory(wrongKey))
            .build()
        try {
            assertThrows(Exception::class.java) { impostor.openHelper.writableDatabase }
        } finally {
            impostor.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    @Test
    fun ledgerFileNameIsTheAgreedName() {
        // Renaming this would orphan every existing ledger on the device, so it is pinned here
        // rather than left to drift silently.
        assertEquals("drugrepo.db", AuditableRecordDatabaseFactory.DB_NAME)
    }

    private fun openLedger(): AuditableRecordDatabase =
        AuditableRecordDatabaseFactory.open(context, TEST_ALIAS, TEST_PREFS, TEST_DB)

    private fun deleteTestState() {
        context.deleteDatabase(TEST_DB)
        context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .takeIf { it.containsAlias(TEST_ALIAS) }
            ?.deleteEntry(TEST_ALIAS)
    }

    private companion object {
        /**
         * Test-only key material. Instrumentation shares this app's UID, so it must never touch
         * DatabaseKeyManager.KEY_ALIAS or the production database file.
         */
        const val TEST_ALIAS = "drugrepo-db-key-test"
        const val TEST_PREFS = "drugrepo_key_store_test"
        const val TEST_DB = "drugrepo-test.db"
    }
}

/**
 * A plaintext SQLite database always begins with this 16-byte string (the file format spec,
 * "SQLite format 3\000"). An encrypted one does not, which is how the on-device test proves
 * encryption rather than assuming it.
 *
 * Test-only, and therefore lives here rather than in main: the shipped APK must not carry test
 * assertions.
 */
private fun isEncryptedAtRest(context: Context, fileName: String): Boolean {
    val file = File(context.getDatabasePath(fileName).absolutePath)
    if (!file.isFile) return false
    val header = ByteArray(SQLITE_MAGIC.size)
    val read = file.inputStream().use { it.read(header) }
    if (read < header.size) return false
    return !header.contentEquals(SQLITE_MAGIC)
}

private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)