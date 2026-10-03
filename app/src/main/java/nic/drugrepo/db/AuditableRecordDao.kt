package nic.drugrepo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * TODO.md Phase 1 task 3: DAO for the sealed record ledger.
 *
 * The chain is append-only and sealed. This DAO only exposes operations that
 * create and read records; no UPDATE and no DELETE are declared. Room cannot
 * perform operations that are not declared on the DAO, so this explicitly
 * blocks destructive mutations at the DAO boundary.
 */
@Dao
interface AuditableRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AuditableRecord)

    @Query("SELECT * FROM records WHERE record_id = :recordId")
    suspend fun getByRecordId(recordId: String): AuditableRecord?

    @Query("SELECT * FROM records WHERE sequence_number = :sequenceNumber LIMIT 1")
    suspend fun getBySequenceNumber(sequenceNumber: Int): AuditableRecord?

    @Query("SELECT * FROM records ORDER BY sequence_number ASC")
    suspend fun getAllInSequenceOrder(): List<AuditableRecord>

    @Query("SELECT * FROM records ORDER BY sequence_number DESC LIMIT 1")
    suspend fun getLatest(): AuditableRecord?

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Long
}