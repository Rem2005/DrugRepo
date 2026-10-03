package nic.drugrepo.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * TODO.md Phase 1 task 2: opens the sealed-record ledger through SQLCipher, keyed by the
 * Keystore-wrapped key from [DatabaseKeyManager] (PRD.md FR-009, architecture.md section 5).
 */
object AuditableRecordDatabaseFactory {

    const val DB_NAME = "drugrepo.db"

    /**
     * [alias], [prefsName] and [dbName] are overridable only so instrumentation tests can work
     * on their own key and file. Instrumentation runs under this app's UID, so a test using the
     * production values could destroy a real ledger's key or overwrite its database.
     */
    fun open(
        context: Context,
        alias: String = DatabaseKeyManager.KEY_ALIAS,
        prefsName: String = DatabaseKeyManager.PREFS_NAME,
        dbName: String = DB_NAME,
    ): AuditableRecordDatabase {
        System.loadLibrary("sqlcipher")
        val databaseKey = DatabaseKeyManager.unwrappedKey(context, alias, prefsName)
        return Room
            .databaseBuilder(context.applicationContext, AuditableRecordDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(databaseKey))
            .build()
    }
}