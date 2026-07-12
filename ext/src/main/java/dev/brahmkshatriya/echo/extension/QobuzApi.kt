package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.extension.model.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

class QobuzApi {
    companion object {
        const val BASE_URL = "https://www.qobuz.com/api.json/0.2"
        const val APP_ID = "798273057"
        const val APP_SECRET = "abb21364945c0583309667d13ca3d93a"
        const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }

    val client = OkHttpClient()
    var userAuthToken: String? = null

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun signGetFileUrl(ts: Long, trackId: Long, formatId: Int): String =
        md5("trackgetFileUrl" + "format_id$formatId" + "intentstream" + "track_id$trackId" + ts + APP_SECRET)

    private fun requestBuilder(path: String, params: Map<String, String> = mapOf()): Request.Builder {
        val urlBuilder = "$BASE_URL/$path".toHttpUrl().newBuilder()
            .addQueryParameter("app_id", APP_ID)
        params.forEach { (k, v) ->
            urlBuilder.addQueryParameter(k, v)
        }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("User-Agent", UA)
        
        userAuthToken?.let { token ->
            builder.header("X-App-Id", APP_ID)
            builder.header("X-User-Auth-Token", token)
        }
        return builder
    }

    suspend fun call(request: Request): String {
        val response = client.newCall(request).await()
        val body = response.body?.string().orEmpty()
        return if (response.isSuccessful) body
        else throw IllegalStateException("Qobuz Call Failed: ${response.code} $body")
    }

    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): Json.Decoded<QobuzSearchResponse> {
        val req = requestBuilder("catalog/search", mapOf(
            "query" to query,
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )).build()
        return Json.decode(call(req))
    }

    suspend fun searchTracks(query: String, limit: Int = 50, offset: Int = 0): Json.Decoded<QobuzSearchResponse> {
        val req = requestBuilder("catalog/search", mapOf(
            "query" to query,
            "type" to "tracks",
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )).build()
        return Json.decode(call(req))
    }

    suspend fun searchAlbums(query: String, limit: Int = 50, offset: Int = 0): Json.Decoded<QobuzSearchResponse> {
        val req = requestBuilder("catalog/search", mapOf(
            "query" to query,
            "type" to "albums",
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )).build()
        return Json.decode(call(req))
    }

    suspend fun searchArtists(query: String, limit: Int = 50, offset: Int = 0): Json.Decoded<QobuzSearchResponse> {
        val req = requestBuilder("catalog/search", mapOf(
            "query" to query,
            "type" to "artists",
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )).build()
        return Json.decode(call(req))
    }

    suspend fun searchPlaylists(query: String, limit: Int = 50, offset: Int = 0): Json.Decoded<QobuzSearchResponse> {
        val req = requestBuilder("catalog/search", mapOf(
            "query" to query,
            "type" to "playlists",
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )).build()
        return Json.decode(call(req))
    }

    suspend fun album(albumId: String): Json.Decoded<QobuzAlbumDetailResponse> {
        val req = requestBuilder("album/get", mapOf(
            "album_id" to albumId
        )).build()
        return Json.decode(call(req))
    }

    suspend fun artist(artistId: String): Json.Decoded<QobuzArtistAlbumsResponse> {
        val req = requestBuilder("artist/get", mapOf(
            "artist_id" to artistId,
            "extra" to "albums",
            "limit" to "100"
        )).build()
        return Json.decode(call(req))
    }

    suspend fun playlist(playlistId: String): Json.Decoded<QobuzPlaylistDetailResponse> {
        val req = requestBuilder("playlist/get", mapOf(
            "playlist_id" to playlistId,
            "extra" to "tracks"
        )).build()
        return Json.decode(call(req))
    }

    suspend fun getFileUrl(trackId: Long, formatId: Int): Json.Decoded<QobuzFileUrl> {
        val ts = System.currentTimeMillis() / 1000L
        val sig = signGetFileUrl(ts, trackId, formatId)
        val req = requestBuilder("track/getFileUrl", mapOf(
            "track_id" to trackId.toString(),
            "format_id" to formatId.toString(),
            "request_ts" to ts.toString(),
            "request_sig" to sig,
            "intent" to "stream"
        )).build()
        return Json.decode(call(req))
    }
}