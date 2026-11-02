package tw.notivault.backup

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tw.notivault.core.*
import tw.notivault.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private fun csv(records: List<NotificationRecord>) = ByteArrayOutputStream().apply { CsvCodec.write(this, records.asSequence()) }.toByteArray()

class BackupEngineTest {
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDav
    private lateinit var access: FakeAccess
    private lateinit var client: WebDavClient
    private val date = "2026-10-20"
    private val destinationPath = "/vault/device/2026/10/$date.csv"

    @Before fun setup() {
        root = Files.createTempDirectory("backup-vault").toFile()
        server = MockWebServer(); dav = FakeDav(); server.dispatcher = dav; server.start()
        access = FakeAccess(root, listOf(fixture()))
        client = WebDavClient(WebDavConfig(server.url("/").toString(), "vault", "user", "secret", true))
    }
    @After fun teardown() { server.shutdown(); root.deleteRecursively() }
    private fun sync(keep: Int = 7) = runBlocking { BackupEngine(access, root).sync(client, "destination", "device", LocalDate.of(2026, 11, 2), keep) {} }

    @Test fun verifiedOldDayCanBeDeletedAndCsvIsComplete() {
        val result = sync()
        assertEquals(1, result.verified); assertEquals(1, result.deleted); assertTrue(result.failures.isEmpty())
        val records = mutableListOf<NotificationRecord>(); CsvCodec.read(dav.files.getValue(destinationPath).inputStream()) { records += it }
        assertEquals(listOf(fixture()), records)
        assertEquals(sha256(dav.files.getValue(destinationPath)), access.receipt?.remoteHash)
        assertTrue(dav.deletions.none { it.endsWith("$date.csv") })
    }
    @Test fun corruptedRemoteVerificationNeverDeletesLocalData() {
        dav.corruptVerification = true
        val result = sync()
        assertEquals(0, result.deleted); assertFalse(access.deleted); assertTrue(result.failures.single().contains("SHA-256"))
    }
    @Test fun failedUploadCanRetryEvenWhenRemoteDoesNotExistYet() {
        dav.failPut = true
        assertTrue(sync().failures.isNotEmpty()); assertFalse(access.deleted)
        dav.failPut = false
        assertEquals(1, sync().deleted)
    }
    @Test fun authenticationFailurePreservesLocalData() {
        dav.deny = true
        assertTrue(sync().failures.single().contains("401")); assertFalse(access.deleted)
    }
    @Test fun concurrentLocalRevisionPreventsCleanup() {
        dav.onPublish = { access.revision++ }
        val result = sync(); assertEquals(1, result.verified); assertEquals(0, result.deleted)
    }
    @Test fun changedDestinationAfterUploadPreventsCleanup() {
        dav.onPublish = { access.current = false }
        val result = sync(); assertEquals(0, result.deleted); assertFalse(access.deleted); assertTrue(result.failures.isNotEmpty())
    }
    @Test fun disabledRetentionStillBacksUpWithoutDeleting() {
        assertEquals(1, sync(keep = 0).verified); assertFalse(access.deleted)
    }
    @Test fun recentDayRemainsOnPhone() {
        access.records = listOf(fixture(date = "2026-11-02"))
        assertEquals(1, sync().verified); assertFalse(access.deleted)
    }
    @Test fun previouslyVerifiedButMissingRemoteIsNotReplacedWithPartialData() {
        access.receipt = BackupReceipt("destination", date, remoteHash = "known-old")
        assertTrue(sync().failures.single().contains("不存在")); assertFalse(access.deleted)
    }
    @Test fun externallyModifiedRemoteRaisesConflict() {
        dav.files[destinationPath] = csv(listOf(fixture("older")))
        access.receipt = BackupReceipt("destination", date, remoteHash = "different-hash")
        assertTrue(sync().failures.single().contains("其他程式")); assertFalse(access.deleted)
    }
    @Test fun lateArrivalMergesExistingRemoteRowsWithoutLosingThem() {
        val old = fixture("old-event")
        dav.files[destinationPath] = csv(listOf(old))
        access.receipt = BackupReceipt("destination", date, remoteHash = sha256(dav.files.getValue(destinationPath)))
        assertTrue(sync().failures.isEmpty())
        val restored = mutableListOf<NotificationRecord>(); CsvCodec.read(dav.files.getValue(destinationPath).inputStream()) { restored += it }
        assertEquals(setOf("old-event", "event-1"), restored.map { it.eventId }.toSet())
    }
    @Test fun duplicateEventWithDifferentPayloadIsRejected() {
        dav.files[destinationPath] = csv(listOf(fixture(text = "altered")))
        assertTrue(sync().failures.single().contains("事件 ID")); assertFalse(access.deleted)
    }
    @Test fun destinationETagChangeBetweenDownloadAndMovePreventsOverwrite() {
        val previous = csv(listOf(fixture("previous"))); dav.files[destinationPath] = previous
        dav.mutateBeforeMove = true
        assertTrue(sync().failures.single().contains("412")); assertFalse(access.deleted)
    }
    @Test fun unknownWeakETagPreventsUnsafeOverwrite() {
        dav.files[destinationPath] = csv(listOf(fixture("previous"))); dav.weakEtag = true
        assertTrue(sync().failures.single().contains("強 ETag")); assertFalse(access.deleted)
    }
    @Test fun knownPendingPublicationCanRecoverAfterProcessInterruption() {
        val value = csv(listOf(fixture())); dav.files[destinationPath] = value
        access.receipt = BackupReceipt("destination", date, pendingHash = sha256(value))
        assertEquals(1, sync().verified); assertEquals("", access.receipt?.pendingHash)
    }

    private class FakeAccess(val root: File, var records: List<NotificationRecord>) : BackupAccess {
        var revision = 1L; var current = true; var deleted = false; var receipt: BackupReceipt? = null
        override suspend fun dates() = records.map { it.archiveDate }.distinct()
        override suspend fun snapshot(date: String): LocalSnapshot {
            val file = File(root, "local-${System.nanoTime()}.csv"); file.writeBytes(csv(records.filter { it.archiveDate == date }))
            return LocalSnapshot(date, revision, file)
        }
        override suspend fun receipt(destination: String, date: String) = receipt
        override suspend fun saveReceipt(receipt: BackupReceipt) { this.receipt = receipt }
        override suspend fun stillCurrent(destination: String) = current
        override suspend fun deleteVerified(date: String, revision: Long, destination: String): Boolean {
            if (!current || revision != this.revision) return false
            deleted = true; return true
        }
    }
    private class FakeDav : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val deletions = mutableListOf<String>()
        var failPut = false; var deny = false; var corruptVerification = false; var published = false
        var mutateBeforeMove = false; var weakEtag = false; var onPublish: (() -> Unit)? = null
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (deny) return MockResponse().setResponseCode(401)
            val path = request.requestUrl!!.encodedPath
            fun tag(bytes: ByteArray) = "\"${sha256(bytes)}\""
            return when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "GET" -> files[path]?.let { bytes -> MockResponse().setResponseCode(200).setHeader("ETag", (if (weakEtag) "W/" else "") + tag(bytes)).setBody(okio.Buffer().write(if (corruptVerification && published && path.endsWith(".csv")) "corrupted".toByteArray() else bytes)) } ?: MockResponse().setResponseCode(404)
                "PUT" -> if (failPut) MockResponse().setResponseCode(503) else {
                    if (files.containsKey(path)) MockResponse().setResponseCode(412) else { files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                }
                "MOVE" -> {
                    val destination = request.getHeader("Destination")!!.toHttpUrl().encodedPath
                    if (mutateBeforeMove) files[destination] = "external change".toByteArray()
                    val previous = files[destination]
                    if (previous != null && (request.getHeader("Overwrite") == "F" || request.getHeader("If")?.contains("[${tag(previous)}]") != true)) MockResponse().setResponseCode(412)
                    else { files[destination] = files.remove(path) ?: return MockResponse().setResponseCode(404); published = true; onPublish?.invoke(); MockResponse().setResponseCode(201) }
                }
                "DELETE" -> { deletions += path; files.remove(path); MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }
}
