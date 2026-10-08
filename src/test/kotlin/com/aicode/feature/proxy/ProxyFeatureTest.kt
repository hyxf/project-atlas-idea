package com.aicode.feature.proxy

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProxyFeatureTest {
    @Test fun `creates defaults only when configuration is absent`() {
        val dir = Files.createTempDirectory("atlas-proxy")
        val file = dir.resolve("proxy.json")
        val store = ProxyConfigStore(file)
        assertEquals(NamedProxy("Local", "http://127.0.0.1:1087"), store.read().single())
        Files.writeString(file, "{\"proxies\":[]}")
        assertTrue(store.read().isEmpty())
        assertEquals("{\"proxies\":[]}", Files.readString(file))
    }

    @Test fun `reads modern and legacy proxy formats and ignores unknown fields`() {
        val file = Files.createTempFile("proxy", ".json")
        Files.writeString(file, """{"extra":true,"proxies":[{"name":" Local ","url":" https://localhost/ ","other":3},"http://127.0.0.1:1087"]}""")
        assertEquals(listOf(NamedProxy("Local", "https://localhost"), NamedProxy("http://127.0.0.1:1087", "http://127.0.0.1:1087")), ProxyConfigStore(file).read())
        Files.writeString(file, """{"proxy":"http://localhost:8080/"}""")
        assertEquals(NamedProxy("http://localhost:8080", "http://localhost:8080"), ProxyConfigStore(file).read().single())
        Files.writeString(file, """{"proxies":["http://localhost:8080"]}""")
        assertEquals("http://localhost:8080", ProxyConfigStore(file).read().single().name)
    }

    @Test fun `validates URL protocol fields and names`() {
        assertEquals("https://example.com/path/", ProxyConfigStore.normalizeUrl(" https://example.com/path/ "))
        assertEquals("http://example.com", ProxyConfigStore.normalizeUrl("http://example.com/"))
        listOf("socks5://localhost:1080", "http:///missing-host", "not a url").forEach {
            assertThrows(IllegalArgumentException::class.java) { ProxyConfigStore.normalizeUrl(it) }
        }
        val file = Files.createTempFile("proxy", ".json")
        Files.writeString(file, """{"proxies":[{"name":" ","url":"http://localhost"}]}""")
        assertThrows(IllegalArgumentException::class.java) { ProxyConfigStore(file).read() }
        Files.writeString(file, """{"proxies":"bad"}""")
        assertThrows(IllegalArgumentException::class.java) { ProxyConfigStore(file).read() }
        Files.writeString(file, "{")
        assertThrows(IllegalArgumentException::class.java) { ProxyConfigStore(file).read() }
    }

    @Test fun `controller applies and reads actual proxy selection contract`() {
        val controller = FakeProxyController()
        assertNull(controller.read())
        controller.apply("http://proxy.local:8888/path")
        assertEquals(ProxyEndpoint("proxy.local", 8888), controller.read())
        controller.apply(null)
        assertNull(controller.read())
    }

    @Test fun `direct selection is distinct from an IDE managed system entry`() {
        assertFalse(ProxyChoice("Direct", null).preserveCurrent)
        assertTrue(ProxyChoice("system (IDE managed)", "", preserveCurrent = true).preserveCurrent)
    }

    @Test fun `checker performs TCP then request and returns response code`() {
        val events = mutableListOf<String>()
        val checker = ProxyChecker(
            tcpConnect = { host, port -> events += "tcp:$host:$port" },
            request = { host, port -> events += "https:$host:$port"; 204 },
        )
        assertEquals(204, checker.check("localhost", 8080))
        assertEquals(listOf("tcp:localhost:8080", "https:localhost:8080"), events)
        val failedTcp = ProxyChecker(tcpConnect = { _, _ -> error("refused") }, request = { _, _ -> fail("request should not run"); 0 })
        assertThrows(IllegalStateException::class.java) { failedTcp.check("localhost", 8080) }
    }

    private class FakeProxyController : ProxyController {
        private var endpoint: ProxyEndpoint? = null
        override fun read() = endpoint
        override fun apply(url: String?) {
            endpoint = url?.let {
                val uri = java.net.URI(ProxyConfigStore.normalizeUrl(it))
                ProxyEndpoint(uri.host, if (uri.port == -1) 80 else uri.port)
            }
        }
    }
}
