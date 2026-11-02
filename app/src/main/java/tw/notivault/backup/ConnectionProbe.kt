package tw.notivault.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tw.notivault.core.sha256
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes the in-flight socket, including a response body still being read. */
suspend fun <T> withInteractiveDav(configuration: WebDavConfig, timing: (DavRequestTiming) -> Unit = {}, block: (WebDavClient) -> T): T = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        val client = WebDavClient.interactive(configuration, timing)
        continuation.invokeOnCancellation { client.cancel() }
        try {
            val result = block(client)
            if (continuation.isActive) continuation.resume(result)
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}

/** Authentication comes first. Cleanup is delegated so it never delays the result. */
class ConnectionProbe(private val cache: File) {
    fun run(client: WebDavClient, installationId: String, progress: (String) -> Unit = {}, cleanup: (List<List<String>>) -> Unit = {}): String {
        val directory = listOf(installationId)
        val source = File(cache, "probe-${UUID.randomUUID()}.txt")
        val downloaded = File(cache, "probe-download-${UUID.randomUUID()}.txt")
        val temporary = directory + ".probe-${UUID.randomUUID()}.upload"
        val destination = directory + ".probe-${UUID.randomUUID()}.txt"
        val owned = linkedSetOf<List<String>>()
        try {
            progress("驗證帳密與 WebDAV 端點")
            client.checkAccess()
            progress("確認備份目錄")
            client.ensureCollections(directory)
            source.writeText("NotiVault connection verification ${UUID.randomUUID()}")
            progress("測試上傳與發布")
            client.put(temporary, source); owned += temporary
            client.publish(temporary, destination, null); owned -= temporary; owned += destination
            progress("下載並驗證內容")
            val first = checkNotNull(client.download(destination, downloaded)) { "測試檔不存在" }
            check(sha256(source) == sha256(downloaded)) { "下載內容校驗失敗" }
            source.appendText("\nconditional overwrite")
            progress("驗證條件式更新")
            client.put(temporary, source); owned += temporary
            client.publish(temporary, destination, first); owned -= temporary
            check(client.download(destination, downloaded) != null && sha256(source) == sha256(downloaded)) { "更新內容校驗失敗" }
            return "連線成功：目錄、上傳、條件式 MOVE、下載及 SHA-256 皆正常"
        } finally {
            source.delete(); downloaded.delete()
            if (owned.isNotEmpty()) cleanup(owned.toList())
        }
    }
}
