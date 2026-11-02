package tw.notivault.core

import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackReader
import java.io.Reader
import java.time.Instant

/** RFC 4180, including quoted multiline cells. The full payload remains unmodified. */
object CsvCodec {
    val header = listOf("postTime", "postTimeIso", "capturedAt", "eventId", "instanceId", "notificationKey", "version", "archiveDate", "archiveZoneId", "packageName", "appLabel", "title", "text", "sbnJson", "notificationJson", "rankingJson", "serializationErrorsJson", "schemaVersion")

    fun write(output: OutputStream, records: Sequence<NotificationRecord>) {
        output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val writer = output.writer(Charsets.UTF_8).buffered()
        fun row(values: List<String>) {
            writer.write(values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
            writer.write("\r\n")
        }
        row(header)
        records.forEach { record -> row(listOf(
            record.postTime.toString(), Instant.ofEpochMilli(record.postTime).toString(), record.capturedAt.toString(),
            record.eventId, record.instanceId, record.notificationKey, record.version.toString(), record.archiveDate,
            record.archiveZoneId, record.packageName, record.appLabel, record.title, record.text,
            record.sbnJson.toString(), record.notificationJson.toString(), record.rankingJson.toString(),
            VaultJson.encodeToString(record.serializationErrors), record.schemaVersion.toString(),
        )) }
        writer.flush() // caller owns the stream and fsync
    }

    fun read(input: InputStream, consume: (NotificationRecord) -> Unit) = input.reader(Charsets.UTF_8).use { reader ->
        val rows = rows(reader).iterator()
        require(rows.hasNext()) { "CSV 是空檔案" }
        val columns = rows.next().mapIndexed { i, value -> if (i == 0) value.removePrefix("\uFEFF") else value }
        require(columns.firstOrNull() == "postTime" && columns.size == header.size && columns.toSet() == header.toSet()) { "不支援的 CSV 欄位格式" }
        val positions = columns.withIndex().associate { it.value to it.index }
        while (rows.hasNext()) {
            val row = rows.next()
            require(row.size == columns.size) { "CSV 欄位數量不符，拒絕使用不完整檔案" }
            fun cell(name: String) = row[positions.getValue(name)]
            require(cell("schemaVersion") == "1") { "不支援的 CSV 版本" }
            fun obj(name: String) = VaultJson.parseToJsonElement(cell(name)) as JsonObject
            val record = NotificationRecord(
                cell("postTime").toLong(), cell("capturedAt").toLong(), cell("eventId"), cell("instanceId"),
                cell("notificationKey"), cell("version").toInt(), cell("archiveDate"), cell("archiveZoneId"),
                cell("packageName"), cell("appLabel"), cell("title"), cell("text"), obj("sbnJson"),
                obj("notificationJson"), obj("rankingJson"), VaultJson.decodeFromString(cell("serializationErrorsJson")),
            )
            require(record.eventId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "無效的事件 ID" }
            require(ArchiveRules.date(record.postTime, record.archiveZoneId).toString() == record.archiveDate) { "CSV 日期與 postTime 不符" }
            consume(record)
        }
    }

    internal fun rows(reader: Reader): Sequence<List<String>> = sequence {
        val input = PushbackReader(reader, 1)
        var quoted = false
        var afterQuote = false
        var started = false
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) {
                require(!quoted) { "CSV 引號未結束" }
                if (started || fields.isNotEmpty() || field.isNotEmpty()) { fields += field.toString(); yield(fields.toList()) }
                break
            }
            val char = next.toChar()
            if (!started && fields.isEmpty() && field.isEmpty() && char == '\uFEFF') continue
            started = true
            if (quoted) {
                if (char == '"') {
                    val lookahead = input.read()
                    if (lookahead == '"'.code) field.append('"') else {
                        quoted = false; afterQuote = true
                        if (lookahead != -1) input.unread(lookahead)
                    }
                } else field.append(char)
            } else when (char) {
                '"' -> { require(field.isEmpty() && !afterQuote) { "CSV 引號位置錯誤" }; quoted = true }
                ',' -> { fields += field.toString(); field.setLength(0); afterQuote = false }
                '\r', '\n' -> {
                    if (char == '\r') { val lookahead = input.read(); if (lookahead != '\n'.code && lookahead != -1) input.unread(lookahead) }
                    fields += field.toString(); yield(fields.toList()); fields.clear(); field.setLength(0); afterQuote = false; started = false
                }
                else -> { require(!afterQuote) { "CSV 結束引號後出現額外內容" }; field.append(char) }
            }
        }
    }
}
