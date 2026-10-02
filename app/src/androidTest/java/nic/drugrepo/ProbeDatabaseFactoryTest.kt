package nic.drugrepo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nic.drugrepo.db.DbProbe
import nic.drugrepo.db.ProbeDao
import nic.drugrepo.db.ProbeDatabase
import nic.drugrepo.db.ProbeDatabaseFactory
import nic.drugrepo.db.isEncryptedAtRest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TODO.md Phase 0 task 3 gate. Proves on a real device that Room codegen runs, that the DAO
 * round-trips, and that the database file on disk is genuinely encrypted rather than plain
 * SQLite. A dependency that resolved but never linked or never loaded would fail here.
 */
@RunWith(AndroidJUnit4::class)
class ProbeDatabaseFactoryTest {

    private lateinit var context: Context
    private lateinit var database: ProbeDatabase

    private val dao: ProbeDao get() = database.probeDao()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(ProbeDatabaseFactory.DB_NAME)
        database = ProbeDatabaseFactory.open(context)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(ProbeDatabaseFactory.DB_NAME)
    }

    @Test
    fun daoRoundTripsAProbeRow() {
        dao.put(DbProbe(value = 4242))
        assertEquals(4242, dao.readValue(DbProbe.PROBE_ID))
    }

    @Test
    fun databaseFileIsEncryptedAtRest() {
        // The write commits synchronously, so the file on disk exists by this point.
        dao.put(DbProbe(value = 1))

        assertTrue(
            "database file still carries the plaintext SQLite magic header",
            isEncryptedAtRest(context, ProbeDatabaseFactory.DB_NAME),
        )
    }

    @Test
    fun wrongPassphraseCannotReadTheDatabase() {
        dao.put(DbProbe(value = 99))
        database.close()

        val wrongPassphrase = ProbeDatabaseFactory.open(context, "not-the-passphrase".toByteArray())
        try {
            wrongPassphrase.probeDao().readValue(DbProbe.PROBE_ID)
            throw AssertionError("database opened and read with the wrong passphrase")
        } catch (expected: Exception) {
            // Correct outcome: SQLCipher refuses to open a file encrypted under another key.
        } finally {
            wrongPassphrase.close()
        }
    }
}
