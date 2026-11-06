package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.security.MessageDigest

private val stream = server("6", 100, "test", mapOf("id" to "42", "formatId" to "6"))
private fun resetPool() {
    for (name in listOf("lastAttempt", "lastSuccess", "preferredToken")) {
        TokenPool::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
    }
}
private fun client(block: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
    val (status, body) = block(chain.request())
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
        .message("simulated").body(body.toResponseBody()).build()
}.build()
private fun extension(client: OkHttpClient, token: String? = null) = QobuzExtension(client).apply {
    setSettings(object : Settings by MockedSettings() {
        override fun getString(key: String): String? = if (key == "userAuthToken") token else null
    })
}
private val pool = """[{"token":"fake-a","app_id":"app-a","app_secret":"secret-a"},{"token":"fake-b","app_id":"app-b","app_secret":"secret-b"}]"""
class QobuzRecoveryTest {
@org.junit.After
fun restoreSharedPool() {
    resetPool()
    val defaults = TokenPool::class.java.getDeclaredField("defaultTokens").apply { isAccessible = true }.get(null)
    TokenPool::class.java.getDeclaredField("activeTokens").apply { isAccessible = true }.set(null, defaults)
    TokenPool::class.java.getDeclaredField("refreshFailure").apply { isAccessible = true }.set(null, null)
}

@org.junit.Test
fun recoveryRegression() = runBlocking {
    var checks = 0
    check(QobuzExtension::class.java.getConstructor() != null); checks++
    val malformed = runCatching { Json.decode<dev.brahmkshatriya.echo.extension.model.QobuzFileUrl>("private-body") }.exceptionOrNull()!!
    check(!malformed.stackTraceToString().contains("private-body")); checks++
    resetPool()
    val fetches=AtomicInteger(); val attempts=CopyOnWriteArrayList<String>()
    val c = client { r -> when {
        r.url.encodedPath.endsWith("/shared") -> { fetches.incrementAndGet(); 200 to pool }
        r.url.encodedPath.endsWith("getFileUrl") -> {
            val token=r.header("X-User-Auth-Token")!!; attempts.add(token)
            val suffix=token.removePrefix("fake-")
            check(r.url.queryParameter("app_id") == "app-$suffix")
            check(r.header("X-App-Id") == "app-$suffix")
            val ts=r.url.queryParameter("request_ts")!!
            val text="trackgetFileUrlformat_id6intentstreamtrack_id42${ts}secret-$suffix"
            val expected=MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            check(r.url.queryParameter("request_sig") == expected)
            if(token=="fake-b") 200 to """{"sample":true,"url":"https://audio.test/sample"}"""
            else 200 to """{"sample":false,"url":"https://audio.test/full"}"""
        }
        else -> error("Unexpected request")
    } }
    val e=extension(c)
    e.loadStreamableMedia(stream,false)
    check(attempts.toList()==listOf("fake-b","fake-a"));checks++
    check(TokenPool.snapshot().first().token=="fake-a");checks++
    coroutineScope { (1..32).map { async(Dispatchers.Default) { e.loadStreamableMedia(stream,false) } }.awaitAll() }
    check(fetches.get()==1);checks++
    check(attempts.size==34);checks++
    // An expired pool is fetched again; concurrent refreshes share the result.
    TokenPool::class.java.getDeclaredField("lastAttempt").apply { isAccessible=true }.set(null, System.nanoTime()-31_000_000_000L)
    TokenPool::class.java.getDeclaredField("lastSuccess").apply { isAccessible=true }.set(null, System.nanoTime()-301_000_000_000L)
    coroutineScope { (1..12).map { async(Dispatchers.Default) { TokenPool.fetchPool(c) } }.awaitAll() }
    check(fetches.get()==2);checks++
    // Failed refresh keeps the last pool and does not trigger a request storm.
    resetPool(); val failures=AtomicInteger()
    val unavailable=client { failures.incrementAndGet(); 503 to "private-body" }
    val previous=TokenPool.snapshot()
    repeat(10) { TokenPool.fetchPool(unavailable) }
    check(failures.get()==1 && TokenPool.snapshot()==previous);checks++
    resetPool()
    val direct=AtomicInteger();val resolvers=AtomicInteger()
    val broken=client { r -> when {
        r.url.encodedPath.endsWith("/shared") -> 200 to pool
        r.url.encodedPath.endsWith("getFileUrl") -> { direct.incrementAndGet(); 200 to """{"sample":true}""" }
        else -> { resolvers.incrementAndGet(); 503 to "secret-response" }
    } }
    val error=runCatching { extension(broken).loadStreamableMedia(stream,false) }.exceptionOrNull()!!
    check(direct.get()==2 && resolvers.get()==2);checks++
    check(error.message!!.contains("resolver 1: HTTP 503") && error.message!!.contains("resolver 2: HTTP 503"));checks++
    check(error.suppressed.size==2 && error.cause!=null);checks++
    check(!error.stackTraceToString().contains("secret-response"));checks++
    // A successful second resolver recovers from a failed first one.
    val fallback=client { r -> when {
        r.url.encodedPath.endsWith("getFileUrl") -> 200 to """{"sample":true}"""
        r.url.host=="citegptapi.f5.si" -> 500 to "unavailable"
        else -> 200 to """{"data":{"url":"https://audio.test/fallback"}}"""
    } }
    extension(fallback,"personal-token").loadStreamableMedia(stream,false);checks++
    // Personal account bypasses the pool and always starts with default app credentials.
    val personalRequests=AtomicInteger()
    val personal=client { r ->
        personalRequests.incrementAndGet()
        check(r.header("X-User-Auth-Token")=="personal-token")
        check(r.header("X-App-Id")==QobuzApi.APP_ID)
        200 to """{"sample":false,"url":"https://audio.test/personal"}"""
    }
    extension(personal,"personal-token").loadStreamableMedia(stream,false)
    check(personalRequests.get()==1);checks++
    // Coroutine cancellation must never rotate accounts or fall back to resolvers.
    val cancelledRequests=AtomicInteger(); val started=CompletableDeferred<Unit>()
    val release=java.util.concurrent.CountDownLatch(1)
    val cancelled=client {
        cancelledRequests.incrementAndGet(); started.complete(Unit)
        release.await(5,java.util.concurrent.TimeUnit.SECONDS)
        200 to """{"sample":true}"""
    }
    val pending=launch { extension(cancelled,"personal-token").loadStreamableMedia(stream,false) }
    withTimeout(5000) { started.await() }
    pending.cancel(); release.countDown(); pending.join()
    check(pending.isCancelled && cancelledRequests.get()==1);checks++
    println("PASS: $checks recovery checks; 32 concurrent playback requests; no external network calls")
    listOf(c,unavailable,broken,fallback,personal,cancelled).forEach {
        it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll()
    }
}

}
