package io.github.akashgupta662642.clipdrop.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A PC this phone has paired with. [deviceId] is the PC's certificate fingerprint (PROTOCOL.md §3). */
@Entity(tableName = "paired_hosts")
data class PairedHost(
    @PrimaryKey val deviceId: String,
    val name: String,
    /** Where the PC was last reached: the fallback when mDNS finds nothing (§5). */
    val lastAddress: String,
    val lastPort: Int,
    val pairedAt: Long,
    val lastConnectedAt: Long?,
)

@Dao
interface PairedHostDao {
    @Query("SELECT * FROM paired_hosts ORDER BY pairedAt")
    fun observeAll(): Flow<List<PairedHost>>

    @Query("SELECT * FROM paired_hosts ORDER BY pairedAt")
    suspend fun all(): List<PairedHost>

    @Query("SELECT * FROM paired_hosts WHERE deviceId = :deviceId")
    suspend fun find(deviceId: String): PairedHost?

    @Upsert
    suspend fun upsert(host: PairedHost)

    @Query("DELETE FROM paired_hosts WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Database(entities = [PairedHost::class], version = 1, exportSchema = true)
abstract class ClipDropDatabase : RoomDatabase() {
    abstract fun pairedHosts(): PairedHostDao
}
