package nic.drugrepo.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * TODO.md Phase 1 task 1: Room database for the auditable Test Record ledger
 * (architecture.md section 7).
 *
 * Open it through [AuditableRecordDatabaseFactory], which supplies the Keystore-wrapped
 * SQLCipher passphrase (TODO.md Phase 1 task 2). There is no passphrase here and no way to
 * open this database unencrypted.
 *
 * Still has no DAO accessor. That is TODO Phase 1 task 3, together with the insert-only rule;
 * adding it now would mean the insert and update/delete-rejection paths land in the same change.
 *
 * The chain is append-only. This class carries no update or delete path, and task 3 enforces
 * that in the DAO, so `clearAllTables()` is the one Room affordance still worth being suspicious
 * of (a future integrity check should assert on it).
 *
 * Version 1 is the first version of the real schema.
 */
@Database(entities = [AuditableRecord::class], version = 1, exportSchema = true)
abstract class AuditableRecordDatabase : RoomDatabase() {
    abstract fun auditableRecordDao(): AuditableRecordDao
}