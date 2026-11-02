package tw.notivault.backup

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import tw.notivault.core.sha256
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal fun collectionXml(path: String, children: String = "") = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>$children</d:multistatus>"""

class ConnectionProbeTest {
    @Test fun immediate405FailsAfterOneReadOnlyRequestWithoutCleanupOrRetry() {
        val server = MockWebServer(); server.start()
        val root = Files.createTempDirectory("probe-405").toFile()
        try {
            server.enqueue(MockResponse().setResponseCode(405).setBody("Method not allowed").setBodyDelay(2, TimeUnit.SECONDS))
            val requests = mutableListOf<DavRequestTiming>()
            val client = WebDavClient.interactive(WebDavConfig(server.url("/").toString(), "vault", "wrong", "wrong", true)) { requests += it }
            var cleanupCalled = false
            val start = System.nanoTime()
            val failure = assertThrows(DavException::class.java) { ConnectionProbe(root).run(client, "device", cleanup = { cleanupCalled = true }) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(405, failure.status); assertTrue(failure.message!!.contains("PROPFIND"))
            assertEquals(1, server.requestCount); assertEquals("PROPFIND", server.takeRequest().method)
            assertFalse(cleanupCalled); assertTrue("Fast headers must not incur cleanup/read timeouts: $elapsed ms", elapsed < 1500)
            assertEquals(405, requests.single().status)
            println("Immediate 405: HTTP headers ${requests.single().elapsedMillis} ms; result $elapsed ms; requests ${server.requestCount}; cleanup $cleanupCalled")
        } finally { server.shutdown(); root.deleteRecursively() }
    }
    @Test fun responseTimingExposesActualDelayedHeaders() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(405).setHeadersDelay(400, TimeUnit.MILLISECONDS))
            val timings = mutableListOf<DavRequestTiming>()
            val client = WebDavClient.interactive(WebDavConfig(server.url("/").toString(), "", "", "", true)) { timings += it }
            assertThrows(DavException::class.java) { client.checkAccess() }
            assertTrue(timings.single().elapsedMillis >= 300)
            println("Delayed 405: HTTP headers ${timings.single().elapsedMillis} ms")
        } finally { server.shutdown() }
    }
    @Test fun mkcol405MustBeFollowedBySuccessfulCollectionVerification() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(207).setBody(collectionXml("/vault/")))
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(207).setBody(collectionXml("/vault/device/")))
            WebDavClient(WebDavConfig(server.url("/").toString(), "vault", "u", "p", true)).ensureCollections(listOf("device"))
            assertEquals(listOf("MKCOL", "PROPFIND", "MKCOL", "PROPFIND"), (0..3).map { server.takeRequest().method })
        } finally { server.shutdown() }
    }
    @Test fun mkcol405WithInvalidCredentialsStopsBeforeCreatingChildren() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(405)); server.enqueue(MockResponse().setResponseCode(401))
            val failure = assertThrows(DavException::class.java) { WebDavClient(WebDavConfig(server.url("/").toString(), "vault", "wrong", "wrong", true)).ensureCollections(listOf("device")) }
            assertEquals(401, failure.status); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun cancellationInterruptsAnUnresponsiveServer() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) { withInteractiveDav(WebDavConfig(server.url("/").toString(), "", "", "", true)) { it.checkAccess() } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            val start = System.nanoTime(); job.cancel(); withTimeout(1500) { job.join() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1200)
        } finally { server.shutdown() }
    }
    @Test fun successfulProbeDelegatesOnlyItsExistingTestFileForBackgroundCleanup() {
        val server = MockWebServer()
        val files = mutableMapOf<String, ByteArray>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when (request.method) {
                    "PROPFIND" -> MockResponse().setResponseCode(207).setBody(collectionXml(path))
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "PUT" -> { files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "MOVE" -> { files[request.getHeader("Destination")!!.toHttpUrl().encodedPath] = files.remove(path)!!; MockResponse().setResponseCode(201) }
                    "GET" -> files[path]?.let { MockResponse().setBody(okio.Buffer().write(it)).setHeader("ETag", "\"${sha256(it)}\"") } ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        val root = Files.createTempDirectory("probe-success").toFile()
        try {
            var cleanup = emptyList<List<String>>()
            val result = ConnectionProbe(root).run(WebDavClient.interactive(WebDavConfig(server.url("/").toString(), "vault", "u", "p", true)), "device", cleanup = { cleanup = it })
            assertTrue(result.startsWith("連線成功")); assertEquals(1, cleanup.size)
            assertTrue(cleanup.single().last().startsWith(".probe-")); assertEquals(1, files.size)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}
