package com.forta.chat.plugins.filetransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * File transfers under Tor go to the app's reverse proxy the way every other
 * request does: the target rides in the path. Addressed as an ordinary HTTP
 * proxy, the proxy is sent `CONNECT host:443` for an https target and refuses
 * it. Source-level: the plugin cannot run under JUnit.
 */
class TorFileProxyContractTest {

    private val plugin by lazy {
        val relative = "com/forta/chat/plugins/filetransfer/TorFilePlugin.kt"
        listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
            .map { File(it) }.firstOrNull { it.exists() }?.readText() ?: error("$relative not found")
    }

    @Test
    fun noRequestUsesTheLocalProxyAsAnHttpProxy() {
        assertFalse("TorFilePlugin must not open connections through java.net.Proxy", plugin.contains("Proxy("))
        assertFalse(plugin.contains("import java.net.Proxy"))
    }

    @Test
    fun uploadAndDownload_bothGoThroughTheReverseProxyForm() {
        assertTrue(plugin.contains("URL(CallHangupSignal.torUrl(targetUrl)).openConnection()"))
        assertEquals(1, Regex("openThroughTor\\(uploadUrl\\)").findAll(plugin).count())
        assertEquals(1, Regex("openThroughTor\\(url\\)").findAll(plugin).count())
    }
}
