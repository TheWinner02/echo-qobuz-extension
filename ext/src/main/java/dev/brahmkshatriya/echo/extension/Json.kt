package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.Json

object Json {
    val impl = Json { ignoreUnknownKeys = true }
    inline fun <reified T> decode(input: String) = runCatching {
        Decoded(impl.decodeFromString<T>(input), input)
    }.getOrElse {
        // Responses may contain auth tokens or signed playback URLs.
        throw IllegalStateException("Invalid Qobuz response (${it.javaClass.simpleName})")
    }

    data class Decoded<T>(val value: T, val json: String)
}