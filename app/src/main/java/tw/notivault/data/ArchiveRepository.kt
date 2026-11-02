package tw.notivault.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.notivault.core.*
import java.io.File
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

data class LocalSnapshot(val date: String, val revision: Long, val file: File)

class ArchiveRepository(private val context: Context, val database: VaultDatabase, private val settings: SettingsStore, scope: CoroutineScope) {
    val dao = database.dao()
    private val mutex = Mutex()
    private val payloads = File(context.filesDir, "snapshots").apply { mkdirs() }
    val csvDirectory = File(context.filesDir, "daily_csv").apply { mkdirs() }
    val counts = dao.observeCounts()
    val apps = dao.observeApps()
    val error = MutableStateFlow<String?>(null)
    private val exportSignals = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                mutex.withLock {
                    dao.days().filter { it.deleting }.forEach { finishDeletion(it) }
                    val known = dao.allIds().toHashSet()
                    payloads.listFiles()?.filter { it.nameWithoutExtension !in known }?.forEach { it.delete() }
                    csvDirectory.listFiles()?.filter { it.name.endsWith(".pending") }?.forEach { it.delete() }
                }
                ready.complete(Unit)
                exportSignals.trySend(Unit)
            } catch (failure: Exception) { error.value = failure.message; ready.completeExceptionally(failure) }
        }
        scope.launch {
            ready.await()
            for (signal in exportSignals) {
                delay(1200)
                runCatching { exportDirty() }.onFailure { error.value = "CSV 產生失敗：${it.message}" }
            }
        }
    }

    suspend fun capture(capture: CapturedNotification) {
        ready.await()
        mutex.withLock {
            val fingerprint = ArchiveRules.fingerprint(capture.notification)
            val previous = dao.active(capture.key)
            if (previous?.fingerprint == fingerprint) return@withLock
            val configuration = settings.get()
            val date = ArchiveRules.date(capture.postTime, configuration.zoneId).toString()
            dao.day(date)?.takeIf { it.deleting }?.let { finishDeletion(it) }
            val record = NotificationRecord(capture.postTime, capture.capturedAt, UUID.randomUUID().toString(),
                previous?.instanceId ?: UUID.randomUUID().toString(), capture.key, (previous?.version ?: 0) + 1,
                date, configuration.zoneId, capture.packageName, capture.appLabel, capture.title, capture.text,
                capture.sbn, capture.notification, capture.ranking, capture.errors)
            atomicWrite(payloadFile(record.eventId)) { it.write(VaultJson.encodeToString(record).toByteArray(Charsets.UTF_8)) }
            database.withTransaction {
                dao.insert(record.index())
                dao.saveActive(ActiveNotification(record.notificationKey, record.instanceId, record.version, fingerprint))
                val day = dao.day(date) ?: ArchiveDay(date)
                dao.saveDay(day.copy(revision = day.revision + 1, deleting = false))
            }
            exportSignals.trySend(Unit)
        }
    }

    suspend fun removed(key: String) { ready.await(); mutex.withLock { dao.removeActive(key) } }
    suspend fun reconcileActive(keys: Set<String>) { ready.await(); mutex.withLock { dao.activeKeys().filter { it !in keys }.forEach { dao.removeActive(it) } } }
    suspend fun read(eventId: String): NotificationRecord = withContext(Dispatchers.IO) { readRecord(payloadFile(eventId)) }
    suspend fun page(date: String, app: String = "", search: String = "", offset: Int = 0) = dao.page(date, app, search, 50, offset)
    suspend fun exportDirty() { ready.await(); mutex.withLock { dao.days().filter { it.exportedRevision != it.revision && !it.deleting }.forEach { exportLocked(it) } } }
    suspend fun snapshot(date: String): LocalSnapshot? {
        ready.await()
        return mutex.withLock {
            val day = dao.day(date) ?: return@withLock null
            if (dao.records(date).isEmpty()) return@withLock null
            exportLocked(day)
            val file = File(context.cacheDir, "backup-local-${UUID.randomUUID()}.csv")
            File(csvDirectory, "$date.csv").copyTo(file, overwrite = true)
            LocalSnapshot(date, day.revision, file)
        }
    }

    private suspend fun exportLocked(day: ArchiveDay) {
        val records = dao.records(day.date)
        val csv = File(csvDirectory, "${day.date}.csv")
        if (records.isEmpty()) csv.delete() else atomicWrite(csv) { out -> CsvCodec.write(out, records.asSequence().map { readRecord(payloadFile(it.eventId)) }) }
        dao.saveDay(day.copy(exportedRevision = day.revision))
    }

    /** Only the caller that has just verified the remote candidate may request deletion. */
    suspend fun deleteVerified(date: String, revision: Long, destination: String): Boolean {
        ready.await()
        return mutex.withLock {
            val day = dao.day(date) ?: return@withLock false
            val receipt = dao.receipt(destination, date) ?: return@withLock false
            val current = settings.get()
            if (day.revision != revision || receipt.verifiedRevision != revision || receipt.remoteHash.isBlank() || current.destinationId() != destination) return@withLock false
            if (current.keepDays == 0 || !ArchiveRules.expired(LocalDate.parse(date), LocalDate.now(ZoneId.of(current.zoneId)), current.keepDays)) return@withLock false
            dao.saveDay(day.copy(deleting = true))
            finishDeletion(day.copy(deleting = true))
            true
        }
    }

    private suspend fun finishDeletion(day: ArchiveDay) {
        val records = dao.records(day.date)
        records.forEach { check(!payloadFile(it.eventId).exists() || payloadFile(it.eventId).delete()) { "無法刪除本機快照" } }
        val csv = File(csvDirectory, "${day.date}.csv")
        check(!csv.exists() || csv.delete()) { "無法刪除本機 CSV" }
        database.withTransaction {
            dao.deleteRecords(day.date)
            dao.saveDay(day.copy(revision = day.revision + 1, exportedRevision = day.revision + 1, deleting = false))
        }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        database.openHelper.writableDatabase.execSQL("PRAGMA incremental_vacuum(256)")
    }

    fun storageBytes(): Long = listOf(context.filesDir, context.getDatabasePath("vault.db").parentFile).filterNotNull()
        .sumOf { directory -> directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    private fun payloadFile(id: String) = File(payloads, "$id.json")
    private fun readRecord(file: File): NotificationRecord = file.reader(Charsets.UTF_8).use { VaultJson.decodeFromString(it.readText()) }
}

fun NotificationRecord.index() = RecordIndex(eventId, archiveDate, postTime, capturedAt, packageName, appLabel, title, text, version)
