package com.forta.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `injectAllCssVars` runs from the insets listener and `onResume`, which on a cold start can
 * fire before the page has a document. The injected script then threw
 * `Cannot read properties of null (reading 'style')` into the console (Samsung, `reconn1`);
 * the 500 ms re-inject set the variables afterwards. The script lives in a Kotlin string that
 * no JVM test can execute, so this pins its guard in source.
 */
class SafeAreaInjectionContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            "src/main/java/com/forta/chat/MainActivity.kt",
            "android/app/src/main/java/com/forta/chat/MainActivity.kt",
        )
        val resolved = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found. Tried: $candidates from ${File(".").absolutePath}")
        resolved.readText()
    }

    @Test
    fun theInjectedScriptReturnsBeforeTouchingStyleWhenThereIsNoDocumentYet() {
        val start = source.indexOf("private fun injectAllCssVars()")
        assertTrue("injectAllCssVars not found in MainActivity.kt", start >= 0)
        val body = source.substring(start, source.indexOf("webView.post", start))
        val guardAt = body.indexOf("if (!d) return;")
        val styleAt = body.indexOf("d.style")
        assertTrue("the injected script must return when document.documentElement is null:\n$body", guardAt >= 0)
        assertTrue(
            "the null guard must come before the first d.style read (guard=$guardAt, style=$styleAt):\n$body",
            styleAt >= 0 && guardAt < styleAt,
        )
    }
}
