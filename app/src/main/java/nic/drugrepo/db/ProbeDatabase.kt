package nic.drugrepo.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * TODO.md Phase 0 task 3 integration probe.
 *
 * This exists ONLY to prove that Room codegen (KSP) and Room's SQLite integration work in
 * this project. It is deliberately not the auditable record model: architecture.md section 7
 * defines the real schema, which belongs to TODO Phase 1 along with the sealed insert-only
 * DAO rules. Delete this whole file in Phase 1.
 *
 * Keep it to a single trivial row so there is no chance of it being mistaken for real data.
 */
@Entity(tableName = "db_probe")
data class DbProbe(
    @PrimaryKey val id: Int = PROBE_ID,
    val value: Int,
) {
    companion object {
        const val PROBE_ID = 0
    }
}

@Dao
interface ProbeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(probe: DbProbe)

    @Query("SELECT value FROM db_probe WHERE id = :id")
    fun readValue(id: Int): Int?
}

@Database(entities = [DbProbe::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun probeDao(): ProbeDao
}
