package com.aicode.feature.proxy

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.util.net.HttpConfigurable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IntelliJProxyControllerTest {
    private lateinit var fixture: CodeInsightTestFixture

    @Before fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(factory.createFixtureBuilder("IntelliJProxyControllerTest").fixture)
        fixture.setUp()
    }

    @After fun tearDown() = fixture.tearDown()

    @Test fun `updates and reads IDE global HTTP proxy settings`() {
        val config = HttpConfigurable.getInstance()
        val oldEnabled = config.USE_HTTP_PROXY
        val oldPac = config.USE_PROXY_PAC
        val oldSocks = config.PROXY_TYPE_IS_SOCKS
        val oldHost = config.PROXY_HOST
        val oldPort = config.PROXY_PORT
        try {
            val controller = IntelliJProxyController()
            controller.apply("https://proxy.example:8443/")
            assertTrue(config.USE_HTTP_PROXY)
            assertFalse(config.USE_PROXY_PAC)
            assertFalse(config.PROXY_TYPE_IS_SOCKS)
            assertEquals("proxy.example", config.PROXY_HOST)
            assertEquals(8443, config.PROXY_PORT)
            assertEquals(ProxyEndpoint("proxy.example", 8443), controller.read())
            controller.apply(null)
            assertFalse(config.USE_HTTP_PROXY)
            assertNull(controller.read())
        } finally {
            config.USE_HTTP_PROXY = oldEnabled
            config.USE_PROXY_PAC = oldPac
            config.PROXY_TYPE_IS_SOCKS = oldSocks
            config.PROXY_HOST = oldHost
            config.PROXY_PORT = oldPort
        }
    }
}
