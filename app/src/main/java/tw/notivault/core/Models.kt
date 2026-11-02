package tw.notivault.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val VaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
data class NotificationRecord(
    val postTime: Long,
    val capturedAt: Long,
    val eventId: String,
    val instanceId: String,
    val notificationKey: String,
    val version: Int,
    val archiveDate: String,
    val archiveZoneId: String = "Asia/Taipei",
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val sbnJson: JsonObject,
    val notificationJson: JsonObject,
    val rankingJson: JsonObject,
    val serializationErrors: List<String> = emptyList(),
    val schemaVersion: Int = 1,
)

data class CapturedNotification(
    val postTime: Long,
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val sbn: JsonObject,
    val notification: JsonObject,
    val ranking: JsonObject,
    val errors: List<String>,
    val capturedAt: Long = System.currentTimeMillis(),
)

object ArchiveRules {
    fun date(postTime: Long, zone: String) = Instant.ofEpochMilli(postTime).atZone(ZoneId.of(zone)).toLocalDate()
    fun expired(date: LocalDate, today: LocalDate, keepDays: Int) = date.isBefore(today.minusDays(keepDays.toLong() - 1))
    fun fingerprint(notification: JsonObject): String = sha256(canonical(notification).toByteArray(Charsets.UTF_8))
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${VaultJson.encodeToString(it.key)}:${canonical(it.value)}" }
        is kotlinx.serialization.json.JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun atomicWrite(file: File, write: (java.io.OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".pending")
    try {
        java.io.FileOutputStream(temporary).use { output -> write(output); output.flush(); output.fd.sync() }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}
