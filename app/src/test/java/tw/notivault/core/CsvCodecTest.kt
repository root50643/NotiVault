package tw.notivault.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

fun fixture(id: String = "event-1", date: String = "2026-10-20", text: String = "中文、emoji 🙂\n多行\r\n逗號,與\"引號\""): NotificationRecord {
    val time = LocalDate.parse(date).atTime(12, 34, 56).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
    return NotificationRecord(time, time + 100, id, "instance-1", "pkg|1", 1, date, packageName = "test.pkg", appLabel = "測試 App", title = "訊息 \"標題\"", text = text,
        sbnJson = buildJsonObject { put("id", 1) }, notificationJson = buildJsonObject { put("extras", buildJsonObject { put("android.text", text); put("nested", JsonArray(listOf(JsonNull, JsonPrimitive(42), buildJsonObject { put("encoding", "base64"); put("data", "iVBORw0KGgo=" ) }))) }) }, rankingJson = buildJsonObject { put("rank", 4) }, serializationErrors = listOf("RemoteViews: 公開 API 限制"))
}

class CsvCodecTest {
    private fun encoded(record: NotificationRecord = fixture()) = ByteArrayOutputStream().apply { CsvCodec.write(this, sequenceOf(record)) }.toByteArray()
    @Test fun roundTripPreservesUnicodeMultilineQuotesAndNestedData() {
        val bytes = encoded()
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3).toByteArray())
        assertTrue(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").startsWith("\"postTime\","))
        val restored = mutableListOf<NotificationRecord>(); CsvCodec.read(bytes.inputStream()) { restored += it }
        assertEquals(listOf(fixture()), restored)
    }
    @Test fun largeBase64CellIsNotTruncated() {
        val value = "A".repeat(200_000)
        val record = fixture().copy(notificationJson = buildJsonObject { put("image", buildJsonObject { put("encoding", "base64"); put("data", value) }) })
        var result: NotificationRecord? = null
        CsvCodec.read(encoded(record).inputStream()) { result = it }
        assertEquals(record, result)
    }
    @Test fun truncatedQuotedRowIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.rows("\"abc".reader()).toList() }
    }
    @Test fun malformedColumnCountIsRejected() {
        val value = encoded().toString(Charsets.UTF_8) + "\"extra\"\r\n"
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.read(value.byteInputStream()) {} }
    }
    @Test fun invalidArchiveDateIsRejected() {
        val bytes = encoded(fixture().copy(archiveDate = "2026-10-21"))
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.read(bytes.inputStream()) {} }
    }
    @Test fun unknownSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.read(encoded(fixture().copy(schemaVersion = 99)).inputStream()) {} }
    }
    @Test fun dangerousEventPathIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvCodec.read(encoded(fixture("../outside")).inputStream()) {} }
    }
    @Test fun canonicalFingerprintIgnoresObjectKeyOrderingAndPreservesReturnsToPreviousContent() {
        val a = buildJsonObject { put("title", "A"); put("number", 1) }
        val reordered = buildJsonObject { put("number", 1); put("title", "A") }
        val b = buildJsonObject { put("title", "B"); put("number", 1) }
        assertEquals(ArchiveRules.fingerprint(a), ArchiveRules.fingerprint(reordered))
        assertNotEquals(ArchiveRules.fingerprint(a), ArchiveRules.fingerprint(b))
        assertNotEquals(ArchiveRules.fingerprint(b), ArchiveRules.fingerprint(a))
    }
    @Test fun sevenDaysMeansTodayAndPreviousSixDates() {
        val today = LocalDate.of(2026, 11, 2)
        assertFalse(ArchiveRules.expired(today.minusDays(6), today, 7))
        assertTrue(ArchiveRules.expired(today.minusDays(7), today, 7))
        assertFalse(ArchiveRules.expired(today, today, 1))
        assertTrue(ArchiveRules.expired(today.minusDays(1), today, 1))
    }
    @Test fun dateUsesPostTimeAndFixedTaipeiZone() {
        val time = java.time.Instant.parse("2026-11-01T16:00:00Z").toEpochMilli()
        assertEquals("2026-11-02", ArchiveRules.date(time, "Asia/Taipei").toString())
    }
    @Test fun interruptedAtomicWriteRetainsPreviousFile() {
        val directory = Files.createTempDirectory("atomic-vault").toFile()
        try {
            val file = java.io.File(directory, "day.csv"); file.writeText("original")
            assertThrows(IllegalStateException::class.java) { atomicWrite(file) { it.write("partial".toByteArray()); error("interrupted") } }
            assertEquals("original", file.readText()); assertFalse(java.io.File(directory, "day.csv.pending").exists())
        } finally { directory.deleteRecursively() }
    }
}
