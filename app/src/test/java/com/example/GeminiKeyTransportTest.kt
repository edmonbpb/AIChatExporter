package com.example

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key is the thing this app must not put where a log keeps it. A query string is kept by proxies,
 * by crash reporters that dump the request, and by any network view the owner opens; the provider
 * takes the same key in a header, so the address has no business carrying it.
 */
class GeminiKeyTransportTest {

    private val body = JSONObject("""{"contents":[]}""")

    @Test
    fun theAddressCarriesNoKey() {
        val request = generateContentRequest("gemini-3.5-flash", "secret-key-value", body)
        val url = request.url.toString()
        assertFalse("the address must not name the key: $url", url.contains("key="))
        assertFalse("and must not contain its value: $url", url.contains("secret-key-value"))
        assertNull(request.url.queryParameter("key"))
    }

    @Test
    fun theKeyTravelsInTheHeaderTheProviderDocuments() {
        val request = generateContentRequest("gemini-3.5-flash", "secret-key-value", body)
        assertEquals("secret-key-value", request.header("x-goog-api-key"))
    }

    @Test
    fun theAddressStillNamesTheModelAndTheOperation() {
        val request = generateContentRequest("gemini-2.5-flash", "k", body)
        assertTrue(
            request.url.toString().endsWith("/v1beta/models/gemini-2.5-flash:generateContent"),
            request.url.toString()
        )
        assertEquals("POST", request.method)
    }

    @Test
    fun theBodyIsSentAsJson() {
        val request = generateContentRequest("m", "k", body)
        assertEquals("application/json", request.body?.contentType()?.type + "/" + request.body?.contentType()?.subtype)
    }
}
