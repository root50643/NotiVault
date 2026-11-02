package tw.notivault.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "records", indices = [Index("archiveDate"), Index("postTime"), Index("packageName")])
data class RecordIndex(
    @PrimaryKey val eventId: String,
    val archiveDate: String,
    val postTime: Long,
    val capturedAt: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val version: Int,
)
@Entity(tableName = "active_notifications")
data class ActiveNotification(@PrimaryKey val notificationKey: String, val instanceId: String, val version: Int, val fingerprint: String)
@Entity(tableName = "archive_days")
data class ArchiveDay(@PrimaryKey val date: String, val revision: Long = 0, val exportedRevision: Long = -1, val deleting: Boolean = false)
@Entity(tableName = "receipts", primaryKeys = ["destination", "date"])
data class BackupReceipt(val destination: String, val date: String, val remoteHash: String = "", val pendingHash: String = "", val verifiedRevision: Long = -1, val verifiedAt: Long = 0)
data class DayCount(val date: String, val count: Long)
data class AppChoice(val packageName: String, val appLabel: String)

@Dao
interface VaultDao {
    @Query("SELECT archiveDate AS date, COUNT(*) AS count FROM records GROUP BY archiveDate ORDER BY archiveDate")
    fun observeCounts(): Flow<List<DayCount>>
    @Query("SELECT DISTINCT packageName, appLabel FROM records ORDER BY appLabel")
    fun observeApps(): Flow<List<AppChoice>>
    @Query("SELECT * FROM records WHERE archiveDate = :date AND (:app = '' OR packageName = :app) AND (:search = '' OR instr(lower(title || ' ' || text || ' ' || appLabel), lower(:search)) > 0) ORDER BY postTime DESC, capturedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(date: String, app: String, search: String, limit: Int, offset: Int): List<RecordIndex>
    @Query("SELECT * FROM records WHERE archiveDate = :date ORDER BY postTime, capturedAt, eventId")
    suspend fun records(date: String): List<RecordIndex>
    @Query("SELECT eventId FROM records") suspend fun allIds(): List<String>
    @Insert suspend fun insert(record: RecordIndex)
    @Insert fun insertBatch(records: List<RecordIndex>)
    @Query("DELETE FROM records") suspend fun clearRecords()
    @Query("DELETE FROM records WHERE archiveDate = :date") suspend fun deleteRecords(date: String)
    @Query("SELECT * FROM active_notifications WHERE notificationKey = :key") suspend fun active(key: String): ActiveNotification?
    @Query("SELECT notificationKey FROM active_notifications") suspend fun activeKeys(): List<String>
    @Upsert suspend fun saveActive(active: ActiveNotification)
    @Query("DELETE FROM active_notifications WHERE notificationKey = :key") suspend fun removeActive(key: String)
    @Query("SELECT * FROM archive_days WHERE date = :date") suspend fun day(date: String): ArchiveDay?
    @Query("SELECT * FROM archive_days ORDER BY date") suspend fun days(): List<ArchiveDay>
    @Upsert suspend fun saveDay(day: ArchiveDay)
    @Query("SELECT * FROM receipts WHERE destination = :destination AND date = :date") suspend fun receipt(destination: String, date: String): BackupReceipt?
    @Upsert suspend fun saveReceipt(receipt: BackupReceipt)
}

@Database(entities = [RecordIndex::class, ActiveNotification::class, ArchiveDay::class, BackupReceipt::class], version = 1, exportSchema = true)
abstract class VaultDatabase : RoomDatabase() { abstract fun dao(): VaultDao }
