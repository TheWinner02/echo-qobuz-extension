package dev.brahmkshatriya.echo.extension

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class QobuzLoginTest {
    private class Memory : dev.brahmkshatriya.echo.common.settings.Settings by MockedSettings() {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
    private val clients = mutableListOf<OkHttpClient>()
    @org.junit.After fun closeClients() {
        clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
    }
    private fun fixture(code: Int, body: String, beforeRequest: () -> Unit = {}): Pair<QobuzExtension, Memory> {
        val prefs = Memory().apply { putString("userAuthToken", "previous") }
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            beforeRequest()
            check(prefs.getString("userAuthToken") == "previous")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        clients += network
        return QobuzExtension(network).also { it.setSettings(prefs) } to prefs
    }
    @Test fun validTokenIsSavedOnlyAfterAccountVerification() = runBlocking {
        val (extension, prefs) = fixture(200, """{"id":42,"display_name":"Fixture"}""")
        val users = extension.onLogin("token", mapOf("userAuthToken" to " new-token "))
        assertEquals("qobuz:42", users.single().id)
        assertEquals("new-token", prefs.getString("userAuthToken"))
    }
    @Test fun rejectedTokenDoesNotOverwritePreviousCredentials() = runBlocking {
        val (extension, prefs) = fixture(401, "{}")
        assertTrue(runCatching { extension.onLogin("token", mapOf("userAuthToken" to "invalid")) }.isFailure)
        assertEquals("previous", prefs.getString("userAuthToken"))
    }
    @Test fun successWithoutAnAccountIdDoesNotConnect() = runBlocking {
        val (extension, prefs) = fixture(200, "{}")
        assertTrue(runCatching { extension.onLogin("token", mapOf("userAuthToken" to "invalid")) }.isFailure)
        assertEquals("previous", prefs.getString("userAuthToken"))
    }
    @Test fun blankTokenDoesNotReachTheNetwork() = runBlocking {
        val (extension, prefs) = fixture(200, "{}") { error("No request expected") }
        assertTrue(runCatching { extension.onLogin("token", mapOf("userAuthToken" to " ")) }.isFailure)
        assertEquals("previous", prefs.getString("userAuthToken"))
    }
}
