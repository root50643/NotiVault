package tw.notivault.backup

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavClientTest {
    @Test fun directoryBrowserUsesEndpointRootAndEncodesSelectedFolderNames() {
        val server = MockWebServer(); server.start()
        try {
            val folder = "/dav/%E5%82%99%E4%BB%BD%20%2B%20%E4%B8%AD%E6%96%87/"
            val children = """<d:response><d:href>$folder</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/file.csv</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
            server.enqueue(MockResponse().setResponseCode(207).setBody(collectionXml("/dav/", children)))
            server.enqueue(MockResponse().setResponseCode(207).setBody(collectionXml(folder)))
            val client = WebDavClient(WebDavConfig(server.url("/dav/").toString(), "old/backup", "u", "p", true))
            assertEquals(listOf(DavEntry("備份 + 中文", true, 0)), client.browse(emptyList()))
            assertTrue(client.browse(listOf("備份 + 中文")).isEmpty())
            assertEquals("/dav/", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals(listOf("dav", "備份 + 中文", ""), server.takeRequest().requestUrl!!.pathSegments)
        } finally { server.shutdown() }
    }
    @Test fun missingBrowserDirectoryIsAnErrorInsteadOfAnEmptySelectableFolder() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(404, assertThrows(DavException::class.java) { WebDavClient(WebDavConfig(server.url("/").toString(), "old", "", "", true)).browse(emptyList()) }.status)
        } finally { server.shutdown() }
    }
    @Test fun httpMustBeExplicitlyEnabled() {
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavConfig("http://localhost/", "vault", "u", "p")) }
    }
    @Test fun traversalSegmentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavConfig("https://example.com/", "vault/../outside", "u", "p")) }
    }
    @Test fun propfindHandlesRelativeUnicodeAndSuccessfulPropertiesOnly() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <x:multistatus xmlns:x="DAV:">
                <x:response><x:href>device-a/</x:href><x:propstat><x:prop><x:resourcetype><x:collection/></x:resourcetype></x:prop><x:status>HTTP/1.1 200 OK</x:status></x:propstat></x:response>
                <x:response><x:href>2026-11-02.csv</x:href><x:propstat><x:prop><x:getcontentlength>1234</x:getcontentlength></x:prop><x:status>HTTP/1.1 200 OK</x:status></x:propstat></x:response>
                <x:response><x:href>missing.csv</x:href><x:propstat><x:prop/><x:status>HTTP/1.1 404 Not Found</x:status></x:propstat></x:response>
                <x:response><x:href>../../escape/</x:href><x:propstat><x:prop/><x:status>HTTP/1.1 200 OK</x:status></x:propstat></x:response>
                <x:response><x:href>https://other.invalid/device/</x:href><x:propstat><x:prop/><x:status>HTTP/1.1 200 OK</x:status></x:propstat></x:response>
                </x:multistatus>
            """.trimIndent()))
            val result = WebDavClient(WebDavConfig(server.url("/dav/").toString(), "通知備份", "帳號", "password", true)).list(emptyList())
            assertEquals(listOf(DavEntry("device-a", true, 0), DavEntry("2026-11-02.csv", false, 1234)), result)
            val request = server.takeRequest()
            assertEquals("1", request.getHeader("Depth")); assertTrue(request.requestUrl!!.pathSegments.contains("通知備份"))
            assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
        } finally { server.shutdown() }
    }
    @Test fun xmlExternalEntitiesAreRejected() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(207).setBody("<!DOCTYPE x [<!ENTITY ext SYSTEM 'https://external.invalid/'>]><x>&ext;</x>"))
            val client = WebDavClient(WebDavConfig(server.url("/").toString(), "vault", "u", "p", true))
            assertThrows(IllegalArgumentException::class.java) { client.list(emptyList()) }
        } finally { server.shutdown() }
    }
    @Test fun redirectsDoNotForwardCredentials() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.invalid/"))
            val client = WebDavClient(WebDavConfig(server.url("/").toString(), "vault", "u", "p", true))
            assertEquals(302, assertThrows(DavException::class.java) { client.list(emptyList()) }.status)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
