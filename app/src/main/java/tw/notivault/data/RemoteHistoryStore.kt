package tw.notivault.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tw.notivault.backup.WebDavClient
import tw.notivault.backup.webDavConfig
import tw.notivault.core.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Separate cache database: remote records can never affect local statistics or retention. */
class RemoteHistoryStore(private val context: Context) {
    private val root = File(context.cacheDir, "remote_history").apply { mkdirs() }
    private val mutex = Mutex()
    private var database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
    private var session: File? = null
    private var activeCsv: File? = null
    var sessionCount: Int = 0; private set

    suspend fun devices(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        WebDavClient(settings.webDavConfig()).list(emptyList()).filter { it.collection }.map { it.name }.sorted()
    }
    suspend fun dates(settings: AppSettings, device: String, month: YearMonth): Set<String> = withContext(Dispatchers.IO) {
        WebDavClient(settings.webDavConfig()).list(listOf(device, month.year.toString(), "%02d".format(month.monthValue)))
            .filter { !it.collection && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.csv")) }
            .map { it.name.removeSuffix(".csv") }.filter { runCatching { YearMonth.from(LocalDate.parse(it)) == month }.getOrDefault(false) }.toSet()
    }
    suspend fun load(settings: AppSettings, device: String, date: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val day = LocalDate.parse(date)
            val cacheId = sha256((settings.destinationId() + device + date).toByteArray())
            val csv = File(root, "$cacheId.csv")
            check(WebDavClient(settings.webDavConfig()).download(listOf(device, day.year.toString(), "%02d".format(day.monthValue), "$date.csv"), csv) != null) { "遠端日期檔案不存在" }
            csv.setLastModified(System.currentTimeMillis())
            val nextSession = File(root, "session-${UUID.randomUUID()}").apply { mkdirs() }
            val nextDatabase = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
            var count = 0
            try {
                val batch = mutableListOf<RecordIndex>()
                csv.inputStream().use { CsvCodec.read(it) { record ->
                    require(record.archiveDate == date) { "遠端檔案日期不符" }
                    val payload = File(nextSession, "${record.eventId}.json")
                    require(!payload.exists()) { "遠端檔案含重複事件 ID" }
                    payload.writeText(VaultJson.encodeToString(record), Charsets.UTF_8)
                    batch += record.index(); count++
                    if (batch.size >= 100) { nextDatabase.dao().insertBatch(batch); batch.clear() }
                } }
                if (batch.isNotEmpty()) nextDatabase.dao().insertBatch(batch)
                database.close(); session?.deleteRecursively()
                database = nextDatabase; session = nextSession; activeCsv = csv; sessionCount = count
                prune()
            } catch (failure: Exception) { nextDatabase.close(); nextSession.deleteRecursively(); throw failure }
        }
    }
    suspend fun page(date: String, app: String, search: String, offset: Int = 0): List<RecordIndex> = withContext(Dispatchers.IO) {
        mutex.withLock { database.dao().page(date, app, search, 50, offset) }
    }
    suspend fun read(id: String): NotificationRecord = withContext(Dispatchers.IO) {
        mutex.withLock { VaultJson.decodeFromString(File(checkNotNull(session) { "遠端暫存已清除" }, "$id.json").readText(Charsets.UTF_8)) }
    }
    suspend fun appChoices(date: String): List<AppChoice> = withContext(Dispatchers.IO) {
        mutex.withLock { database.dao().records(date).map { AppChoice(it.packageName, it.appLabel) }.distinctBy { it.packageName }.sortedBy { it.appLabel } }
    }
    suspend fun exportCopy(): File = withContext(Dispatchers.IO) { mutex.withLock {
        val source = checkNotNull(activeCsv) { "請先開啟遠端日期" }
        source.copyTo(File(context.cacheDir, "remote-export-${UUID.randomUUID()}.csv"), overwrite = true)
    } }
    suspend fun clear(evictAll: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            database.dao().clearRecords(); session?.deleteRecursively(); session = null; activeCsv = null; sessionCount = 0
            if (evictAll) root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            prune()
        }
    }
    private fun prune() {
        root.listFiles()?.filter { it.isDirectory && it != session }?.forEach { it.deleteRecursively() }
        val files = root.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        files.filter { it != activeCsv }.forEach { if (bytes > 50L * 1024 * 1024) { val length = it.length(); if (it.delete()) bytes -= length } }
        // A currently-open day may exceed the limit; it is evicted when the session closes.
    }
}
