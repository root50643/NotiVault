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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory

data class WebDavConfig(val url: String, val directory: String, val username: String, val password: String, val allowHttp: Boolean = false)
data class RemoteFile(val etag: String?, val size: Long)
data class DavEntry(val name: String, val collection: Boolean, val size: Long)
data class DavRequestTiming(val method: String, val status: Int?, val elapsedMillis: Long, val responseMillis: Long? = null) {
    fun summary() = "$method · ${status?.let { "HTTP $it" } ?: "未收到 HTTP 回應"} · %.2f 秒".format(java.util.Locale.ROOT, elapsedMillis / 1000.0)
}
class DavException(val status: Int, message: String) : IOException(message)

class WebDavClient(configuration: WebDavConfig, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build(), private val onRequest: (DavRequestTiming) -> Unit = {}) {
    private val cancelled = AtomicBoolean(false)
    private val currentCall = AtomicReference<Call?>()
    private data class RequestStart(val nanos: Long)
    private data class Resource(val segments: List<String>, val collection: Boolean, val size: Long)
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
    fun cancel() { cancelled.set(true); currentCall.get()?.cancel(); http.dispatcher.cancelAll() }
    private fun execute(request: Request): Response {
        if (cancelled.get()) throw IOException("請求已取消")
        val start = System.nanoTime()
        val timed = request.newBuilder().tag(RequestStart::class.java, RequestStart(start)).build()
        val call = http.newCall(timed)
        currentCall.set(call)
        if (cancelled.get()) call.cancel()
        try {
            return call.execute().also { response -> onRequest(DavRequestTiming(request.method, response.code,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                (response.receivedResponseAtMillis - response.sentRequestAtMillis).coerceAtLeast(0))) }
        } catch (failure: IOException) {
            onRequest(DavRequestTiming(request.method, null, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)))
            throw failure
        }
    }
    private fun failure(response: Response): Nothing {
        val detail = when (response.code) {
            401, 403 -> "帳號、密碼或目錄存取權限不正確"
            301, 302, 307, 308 -> "伺服器重新導向；請填入最終 WebDAV 網址"
            404 -> "遠端目錄或 WebDAV 端點不存在"
            405 -> "伺服器拒絕此 WebDAV 方法；請檢查端點、帳密及伺服器設定"
            409 -> "遠端目錄不存在或發生衝突"
            412 -> "遠端檔案已變更，已停止覆蓋"
            507 -> "WebDAV 空間不足"
            else -> "WebDAV 請求失敗"
        }
        val elapsed = response.request.tag(RequestStart::class.java)?.let { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it.nanos) } ?: 0
        throw DavException(response.code, "$detail（${response.request.method} HTTP ${response.code}，%.2f 秒）".format(java.util.Locale.ROOT, elapsed / 1000.0))
    }
    fun ensureCollections(path: List<String>) {
        val parts = directory + path
        parts.indices.forEach { index ->
            val target = url(parts.take(index + 1), false).newBuilder().addPathSegment("").build()
            val created = execute(builder(target).method("MKCOL", ByteArray(0).toRequestBody()).build()).use { response ->
                when (response.code) { 201 -> true; 405 -> false; else -> failure(response) }
            }
            // MKCOL 405 can mean "already exists", but does not prove access or collection type.
            if (!created) requireCollection(target)
        }
    }
    fun checkAccess() { requireCollection(url(emptyList(), false)) }
    private fun requireCollection(target: HttpUrl) {
        val prefix = target.pathSegments.filter { it.isNotEmpty() }
        check(propfind(target.newBuilder().addPathSegment("").build(), "0", false)?.any { it.segments == prefix && it.collection } == true) { "伺服器未確認這是可讀取的 WebDAV 目錄" }
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
        return children(requested, propfind(requested, "1", true).orEmpty())
    }
    /** Paths are relative to the configured endpoint, not the backup subdirectory. */
    fun browse(path: List<String>): List<DavEntry> {
        val requested = url(path, false).newBuilder().addPathSegment("").build()
        val resources = propfind(requested, "1", false).orEmpty()
        val prefix = requested.pathSegments.filter { it.isNotEmpty() }
        check(resources.any { it.segments == prefix && it.collection }) { "伺服器未確認所選路徑是可讀取的目錄" }
        return children(requested, resources).filter { it.collection }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }
    private fun children(requested: HttpUrl, resources: List<Resource>): List<DavEntry> {
        val prefix = requested.pathSegments.filter { it.isNotEmpty() }
        return resources.filter { it.segments.size == prefix.size + 1 && it.segments.take(prefix.size) == prefix }
            .map { DavEntry(it.segments.last(), it.collection, it.size) }.distinctBy { it.name }
    }
    private fun propfind(requested: HttpUrl, depth: String, missingIsEmpty: Boolean): List<Resource>? {
        val xml = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        return execute(builder(requested).header("Depth", depth).method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use { response ->
            if (response.code == 404 && missingIsEmpty) return null
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
            require(document.documentElement.namespaceURI == "DAV:" && document.documentElement.localName == "multistatus") { "伺服器未回傳 WebDAV 目錄格式" }
            val nodes = document.getElementsByTagNameNS("DAV:", "response")
            (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as Element
                val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
                val resolved = requested.resolve(href) ?: return@mapNotNull null
                if (resolved.host != requested.host || resolved.port != requested.port || resolved.scheme != requested.scheme) return@mapNotNull null
                val segments = resolved.pathSegments.filter { it.isNotEmpty() }
                val endpointPrefix = base.pathSegments.filter { it.isNotEmpty() }
                if (segments.take(endpointPrefix.size) != endpointPrefix || segments.any { !safeSegment(it) }) return@mapNotNull null
                val properties = element.getElementsByTagNameNS("DAV:", "propstat")
                val successful = (0 until properties.length).map { properties.item(it) as Element }.filter {
                    it.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent?.let { status -> Regex("\\s200(?:\\s|$)").containsMatchIn(status) } == true
                }
                if (successful.isEmpty()) return@mapNotNull null
                val collection = successful.any { it.getElementsByTagNameNS("DAV:", "collection").length > 0 }
                val size = successful.firstNotNullOfOrNull { it.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.toLongOrNull() } ?: 0
                Resource(segments, collection, size)
            }.distinctBy { it.segments }
        }
    }
    companion object {
        fun interactive(configuration: WebDavConfig, onRequest: (DavRequestTiming) -> Unit = {}) = WebDavClient(configuration,
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(), onRequest)
        fun cleanup(configuration: WebDavConfig) = WebDavClient(configuration,
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build())
        fun safeSegment(value: String) = value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.code < 32 }
        private fun validateSegment(value: String) { require(safeSegment(value)) { "遠端路徑包含無效字元" } }
    }
}
