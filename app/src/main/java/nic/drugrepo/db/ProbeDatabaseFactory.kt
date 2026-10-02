package nic.drugrepo.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * TODO.md Phase 0 task 3. Opens the local database through SQLCipher, so the on-disk file is
 * encrypted at rest (architecture.md section 3, "Sealed records are insert-only").
 *
 * SQLCipher 4.18 exposes an `androidx.sqlite.SQLiteDriver` rather than the older
 * `SupportSQLiteOpenHelper.Factory`, so Room's `setDriver` is the correct entry point here.
 * It does not load its own native library, hence the explicit loadLibrary call.
 */
object ProbeDatabaseFactory {

    const val DB_NAME = "drugrepo-probe.db"

    /**
     * TEMPORARY, TEST-ONLY PASSPHRASE. This proves SQLCipher is wired up; it is not a
     * security design.
     *
     * TODO Phase 1 / Phase 6 must replace this with a random key generated in the Android
     * Keystore and wrapped for storage, per architecture.md section 6. A literal passphrase
     * must never ship. See ProbeDatabaseFactoryTest for the negative check.
     */
    private val TEST_PASSPHRASE: ByteArray = "drugrepo-phase0-integration-probe".toByteArray()

    fun open(context: Context, passphrase: ByteArray = TEST_PASSPHRASE): ProbeDatabase {
        System.loadLibrary("sqlcipher")
        val driver = SQLCipherDriver(passphrase, null, null)
        return Room
            .databaseBuilder(context.applicationContext, ProbeDatabase::class.java, DB_NAME)
            .setDriver(driver)
            .build()
    }
}

/** True when the SQLite driver honours the passphrase and the file is encrypted at rest. */
fun isEncryptedAtRest(context: Context, fileName: String): Boolean {
    context.applicationContext.getDatabasePath(fileName).inputStream().use { stream ->
        val header = ByteArray(SQLITE_MAGIC.size)
        val read = stream.read(header)
        if (read < header.size) return false
        return !header.contentEquals(SQLITE_MAGIC)
    }
}

/**
 * A plaintext SQLite database always begins with this 16-byte string (the file format
 * spec, "SQLite format 3\000"). A SQLCipher-encrypted file does not, which is how the
 * on-device test proves encryption rather than assuming it.
 */
private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
