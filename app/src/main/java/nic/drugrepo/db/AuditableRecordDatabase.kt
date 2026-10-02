package nic.drugrepo.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * TODO.md Phase 1 task 1: Room database for the auditable Test Record ledger
 * (architecture.md section 7).
 *
 * Deliberately has no DAO accessor yet. Opening this database needs a SQLCipher passphrase,
 * and that passphrase must be Keystore-wrapped rather than a literal (architecture.md section
 * 5, PRD.md FR-009) - which is TODO Phase 1 task 2. Adding an `abstract fun recordDao()` now
 * would only be callable through the temporary Phase 0 test passphrase, which would spread a
 * throwaway credential towards production code.
 *
 * The chain is append-only. This class carries no update or delete path, and TODO Phase 1
 * task 3 enforces that in the DAO, so `clearAllTables()` is the one Room affordance still
 * worth being suspicious of (a future integrity check should assert on it).
 *
 * Version 1 is the first version of the real schema; the Phase 0 probe database is a different
 * database with its own version 1 and shares no schema with this one.
 */
@Database(entities = [AuditableRecord::class], version = 1, exportSchema = true)
abstract class AuditableRecordDatabase : RoomDatabase()
