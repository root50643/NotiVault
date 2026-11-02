package tw.notivault.backup

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import tw.notivault.core.atomicWrite
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class WebDavConfig(val url: String, val directory: String, val username: String, val password: String, val allowHttp: Boolean = false)
data class RemoteFile(val etag: String?, val size: Long)
data class DavEntry(val name: String, val collection: Boolean, val size: Long)
class DavException(val status: Int, message: String) : IOException(message)

class WebDavClient(configuration: WebDavConfig, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    private val base = configuration.url.trim().trimEnd('/').toHttpUrl().let {
        require(it.scheme == "https" || (configuration.allowHttp && it.scheme == "http")) { "請使用 HTTPS，或明確開啟 HTTP" }
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "網址不可包含帳密、查詢參數或 fragment" }
        it.newBuilder().addPathSegment("").build()
    }
    private val directory = configuration.directory.trim('/').split('/').filter { it.isNotEmpty() }.onEach(::validateSegment)
    private val authorization = Credentials.basic(configuration.username, configuration.password, Charsets.UTF_8)
    private fun url(path: List<String>, includeDirectory: Boolean = true): HttpUrl {
        val builder = base.newBuilder()
        (if (includeDirectory) directory + path else path).forEach { validateSegment(it); builder.addPathSegment(it) }
        return builder.build()
    }
    private fun builder(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization).header("Cache-Control", "no-cache")
    private fun execute(request: Request): Response = http.newCall(request).execute()
    private fun failure(response: Response): Nothing {
        val detail = when (response.code) {
            401, 403 -> "帳號、密碼或目錄存取權限不正確"
            301, 302, 307, 308 -> "伺服器重新導向；請填入最終 WebDAV 網址"
            409 -> "遠端目錄不存在或發生衝突"
            412 -> "遠端檔案已變更，已停止覆蓋"
            507 -> "WebDAV 空間不足"
            else -> "WebDAV 請求失敗"
        }
        throw DavException(response.code, "$detail（HTTP ${response.code}）")
    }
    fun ensureCollections(path: List<String>) {
        val parts = directory + path
        parts.indices.forEach { index -> execute(builder(url(parts.take(index + 1), false).newBuilder().addPathSegment("").build()).method("MKCOL", ByteArray(0).toRequestBody()).build()).use { response ->
            if (response.code !in listOf(201, 405)) failure(response)
        } }
    }
    fun download(path: List<String>, target: File): RemoteFile? = execute(builder(url(path)).get().build()).use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) failure(response)
        val body = checkNotNull(response.body) { "遠端沒有傳回檔案內容" }
        atomicWrite(target) { output -> body.byteStream().use { it.copyTo(output, 64 * 1024) } }
        RemoteFile(response.header("ETag"), target.length())
    }
    fun put(path: List<String>, source: File) {
        execute(builder(url(path)).header("If-None-Match", "*").put(source.asRequestBody("text/csv; charset=utf-8".toMediaType())).build()).use { if (!it.isSuccessful) failure(it) }
    }
    fun publish(temporary: List<String>, destination: List<String>, existing: RemoteFile?) {
        val request = builder(url(temporary)).header("Destination", url(destination).toString()).header("Overwrite", if (existing == null) "F" else "T")
        if (existing != null) {
            val etag = existing.etag
            require(!etag.isNullOrBlank() && !etag.startsWith("W/")) { "伺服器未提供強 ETag，無法安全更新既有備份" }
            require(etag.startsWith('"') && etag.endsWith('"') && !etag.contains('\n') && !etag.contains('\r')) { "伺服器 ETag 格式無效" }
            request.header("If", "<${url(destination)}> ([$etag])")
        }
        execute(request.method("MOVE", null).build()).use { if (!it.isSuccessful) failure(it) }
    }
    fun deleteTemporary(path: List<String>) { execute(builder(url(path)).delete().build()).use { if (!it.isSuccessful && it.code != 404) failure(it) } }
    fun list(path: List<String>): List<DavEntry> {
        val requested = url(path).newBuilder().addPathSegment("").build()
        val xml = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        return execute(builder(requested).header("Depth", "1").method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use { response ->
            if (response.code == 404) return emptyList()
            if (response.code != 207 && !response.isSuccessful) failure(response)
            val text = checkNotNull(response.body).charStream().use { reader ->
                val buffer = CharArray(8192); val output = StringBuilder()
                while (true) { val size = reader.read(buffer); if (size < 0) break; require(output.length + size <= 2_000_000) { "遠端目錄回應過大" }; output.append(buffer, 0, size) }
                output.toString()
            }
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "拒絕包含外部實體的 XML" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val document = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) } }
                .parse(org.xml.sax.InputSource(java.io.StringReader(text)))
            val nodes = document.getElementsByTagNameNS("DAV:", "response")
            val prefix = requested.pathSegments.filter { it.isNotEmpty() }
            (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as Element
                val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
                val resolved = requested.resolve(href) ?: return@mapNotNull null
                if (resolved.host != requested.host || resolved.port != requested.port || resolved.scheme != requested.scheme) return@mapNotNull null
                val segments = resolved.pathSegments.filter { it.isNotEmpty() }
                if (segments.size != prefix.size + 1 || segments.take(prefix.size) != prefix) return@mapNotNull null
                val properties = element.getElementsByTagNameNS("DAV:", "propstat")
                val successful = (0 until properties.length).map { properties.item(it) as Element }.filter {
                    it.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent?.contains(" 200 ") == true
                }
                if (successful.isEmpty()) return@mapNotNull null
                val collection = successful.any { it.getElementsByTagNameNS("DAV:", "collection").length > 0 }
                val size = successful.firstNotNullOfOrNull { it.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.toLongOrNull() } ?: 0
                val name = segments.last()
                if (!safeSegment(name)) return@mapNotNull null
                DavEntry(name, collection, size)
            }.distinctBy { it.name }
        }
    }
    companion object {
        fun safeSegment(value: String) = value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.code < 32 }
        private fun validateSegment(value: String) { require(safeSegment(value)) { "遠端路徑包含無效字元" } }
    }
}
