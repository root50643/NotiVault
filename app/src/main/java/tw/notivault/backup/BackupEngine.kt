package tw.notivault.backup

import kotlinx.coroutines.CancellationException
import tw.notivault.core.*
import tw.notivault.data.BackupReceipt
import tw.notivault.data.LocalSnapshot
import java.io.File
import java.time.LocalDate
import java.util.UUID

interface BackupAccess {
    suspend fun dates(): List<String>
    suspend fun snapshot(date: String): LocalSnapshot?
    suspend fun receipt(destination: String, date: String): BackupReceipt?
    suspend fun saveReceipt(receipt: BackupReceipt)
    suspend fun stillCurrent(destination: String): Boolean
    suspend fun deleteVerified(date: String, revision: Long, destination: String): Boolean
}

data class BackupResult(val verified: Int, val deleted: Int, val failures: List<String>)

/** Disk-backed merge. Only a single notification payload is decoded at a time. */
object ArchiveMerge {
    fun merge(date: String, remote: File?, local: File, output: File, scratch: File) {
        scratch.mkdirs()
        data class Row(val id: String, val postTime: Long, val capturedAt: Long, val file: File)
        val rows = linkedMapOf<String, Row>()
        try {
            fun add(record: NotificationRecord) {
                require(record.archiveDate == date) { "遠端 CSV 包含其他日期" }
                val existing = rows[record.eventId]
                if (existing != null) {
                    val previous = VaultJson.decodeFromString<NotificationRecord>(existing.file.readText(Charsets.UTF_8))
                    require(previous == record) { "相同事件 ID 的內容不一致，已停止合併" }
                } else {
                    val file = File(scratch, "${record.eventId}.json")
                    file.writeText(VaultJson.encodeToString(record), Charsets.UTF_8)
                    rows[record.eventId] = Row(record.eventId, record.postTime, record.capturedAt, file)
                }
            }
            remote?.inputStream()?.use { CsvCodec.read(it, ::add) }
            local.inputStream().use { CsvCodec.read(it, ::add) }
            val ordered = rows.values.sortedWith(compareBy<Row> { it.postTime }.thenBy { it.capturedAt }.thenBy { it.id })
            atomicWrite(output) { stream -> CsvCodec.write(stream, ordered.asSequence().map { VaultJson.decodeFromString(it.file.readText(Charsets.UTF_8)) }) }
        } finally {
            // scratch is a newly-created, caller-owned UUID directory; never accept a remote path here.
            scratch.listFiles()?.forEach { it.delete() }; scratch.delete()
        }
    }
}

class BackupEngine(private val access: BackupAccess, private val cache: File) {
    suspend fun sync(client: WebDavClient, destination: String, installationId: String, today: LocalDate, keepDays: Int, progress: suspend (String) -> Unit): BackupResult {
        require(WebDavClient.safeSegment(installationId))
        var verified = 0; var deleted = 0
        val failures = mutableListOf<String>()
        val dates = access.dates()
        for ((index, date) in dates.withIndex()) {
            val temporaryFiles = mutableListOf<File>()
            var remoteTemporary: List<String>? = null
            try {
                check(access.stillCurrent(destination)) { "備份設定已變更，請重新執行" }
                progress("備份 ${index + 1}/${dates.size} · $date")
                val snapshot = access.snapshot(date) ?: continue
                temporaryFiles += snapshot.file
                val parsedDate = LocalDate.parse(date)
                val directory = listOf(installationId, parsedDate.year.toString(), "%02d".format(parsedDate.monthValue))
                val path = directory + "$date.csv"
                client.ensureCollections(directory)
                val old = File(cache, "remote-old-${UUID.randomUUID()}.csv").also { temporaryFiles += it }
                val existing = client.download(path, old)
                val receipt = access.receipt(destination, date) ?: BackupReceipt(destination, date)
                val oldHash = if (existing == null) "" else sha256(old)
                if (receipt.remoteHash.isNotBlank()) {
                    check(existing != null) { "原本的遠端備份已不存在，保留本機資料" }
                }
                if (existing != null && (receipt.remoteHash.isNotBlank() || receipt.pendingHash.isNotBlank())) {
                    check(oldHash == receipt.remoteHash || oldHash == receipt.pendingHash) { "遠端備份已被其他程式變更，保留本機資料" }
                }
                val candidate = File(cache, "candidate-${UUID.randomUUID()}.csv").also { temporaryFiles += it }
                ArchiveMerge.merge(date, old.takeIf { existing != null }, snapshot.file, candidate, File(cache, "merge-${UUID.randomUUID()}"))
                val expectedHash = sha256(candidate)
                access.saveReceipt(receipt.copy(remoteHash = oldHash, pendingHash = expectedHash))
                if (oldHash != expectedHash) {
                    val upload = directory + ".$date.${UUID.randomUUID()}.upload"
                    remoteTemporary = upload
                    client.put(upload, candidate)
                    check(access.stillCurrent(destination)) { "備份設定已變更，請重新執行" }
                    client.publish(upload, path, existing)
                    remoteTemporary = null
                }
                progress("驗證遠端內容 · $date")
                val verification = File(cache, "verification-${UUID.randomUUID()}.csv").also { temporaryFiles += it }
                check(client.download(path, verification) != null) { "上傳後遠端檔案不存在" }
                check(sha256(verification) == expectedHash) { "遠端 SHA-256 不符，保留本機資料" }
                check(access.stillCurrent(destination)) { "備份設定已變更，保留本機資料" }
                access.saveReceipt(receipt.copy(remoteHash = expectedHash, pendingHash = "", verifiedRevision = snapshot.revision, verifiedAt = System.currentTimeMillis()))
                verified++
                if (keepDays > 0 && ArchiveRules.expired(parsedDate, today, keepDays)) {
                    progress("清理已驗證的本機資料 · $date")
                    if (access.deleteVerified(date, snapshot.revision, destination)) deleted++
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += "$date：${failure.message ?: failure.javaClass.simpleName}" }
            finally {
                remoteTemporary?.let { runCatching { client.deleteTemporary(it) } }
                temporaryFiles.forEach { it.delete() }
            }
        }
        return BackupResult(verified, deleted, failures)
    }
}
