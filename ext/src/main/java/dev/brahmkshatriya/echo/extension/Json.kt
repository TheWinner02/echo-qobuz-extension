package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.json.Json

object Json {
    val impl = Json { ignoreUnknownKeys = true }
    inline fun <reified T> decode(input: String) = runCatching {
        Decoded(impl.decodeFromString<T>(input), input)
    }.getOrElse {
        throw IllegalStateException("${it.message}\nINPUT: $input", it)
    }

    data class Decoded<T>(val value: T, val json: String)
}