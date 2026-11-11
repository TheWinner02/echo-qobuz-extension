package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.model.ImageSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TokenInfo(
    val token: String,
    val appId: String = "798273057",
    val appSecret: String = "abb21364945c0583309667d13ca3d93a"
)

object TokenPool {
    private val defaultTokens = listOf(
        TokenInfo("7W1P_bimeouwcLTVKjV1UGr_MJp4psetcljd1awOU9QksKi9AmHKvf6MtIdvzP0HuLLENvmM2pJErjSuE_3N3g", "312369995", "e79f8b9be485692b0e5f9dd895826368"),
        TokenInfo("O97vZDyvXmYhLPfbn07D8Y4SFN02DyRrYb3Pe3rQXWnw1HosDl9vfQTGRQEo2zkkcwP9ADc3L20nYNaI0l7E4g", "312369995", "e79f8b9be485692b0e5f9dd895826368"),
        TokenInfo("LXcX98uaBz8NPoRZsigyB6TfCemNotzeG7oVwy5XZHPKXNeLMXJUPyEGAM6c5qUpBWnkqmRaRUbJGup9oSsh_w", "312369995", "e79f8b9be485692b0e5f9dd895826368")
    )
    
    private val refreshMutex = Mutex()
    private val stateLock = Any()
    private var activeTokens = defaultTokens.toList()
    private var preferredToken: String? = null
    private var lastAttempt: Long? = null
    private var lastSuccess: Long? = null
    @Volatile var refreshFailure: String? = null
        private set
    private const val REFRESH_NS = 5L * 60 * 1_000_000_000
    private const val RETRY_NS = 30L * 1_000_000_000

    // Each operation gets a stable, distinct snapshot, independent of other requests.
    fun snapshot(): List<TokenInfo> = synchronized(stateLock) {
        val start = activeTokens.indexOfFirst { it.token == preferredToken }.coerceAtLeast(0)
        activeTokens.drop(start) + activeTokens.take(start)
    }

    fun prefer(token: TokenInfo) = synchronized(stateLock) { preferredToken = token.token }

    suspend fun fetchPool(client: OkHttpClient, force: Boolean = false) = refreshMutex.withLock {
        val now = System.nanoTime()
        if (lastAttempt?.let { now - it < RETRY_NS } == true) return@withLock
        if (!force && lastSuccess?.let { now - it < REFRESH_NS } == true) return@withLock
        lastAttempt = now
        try {
            val req = Request.Builder()
                .url("https://citegptapi.f5.si/webhook/qbdlx/shared")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Origin", "https://qbdlx.launchpd.cloud")
                .header("Referer", "https://qbdlx.launchpd.cloud/")
                .build()
            val tokens = client.newCall(req).await().use { response ->
                if (!response.isSuccessful) {
                    refreshFailure = "HTTP ${response.code}"
                    return@withLock
                }
                val json = Json { ignoreUnknownKeys = true }
                json.parseToJsonElement(response.body?.string().orEmpty()).jsonArray.reversed().mapNotNull { element ->
                    val obj = element.jsonObject
                    val token = obj["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val appId = obj["app_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: QobuzApi.APP_ID
                    val secret = obj["app_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: if (appId == "312369995") "e79f8b9be485692b0e5f9dd895826368" else QobuzApi.APP_SECRET
                    TokenInfo(token, appId, secret)
                }.distinctBy { it.token }
            }
            if (tokens.isNotEmpty()) {
                synchronized(stateLock) { activeTokens = tokens }
                lastSuccess = System.nanoTime()
                refreshFailure = null
            } else {
                refreshFailure = "Empty account list"
            }
        } catch (cancelled: CancellationException) {
            lastAttempt = null
            throw cancelled
        } catch (error: Exception) {
            refreshFailure = error.javaClass.simpleName
            // Keep the last usable pool; failed refreshes have a bounded retry cooldown.
        }
    }

}

class QobuzExtension(val httpClient: OkHttpClient = OkHttpClient()) : ExtensionClient, HomeFeedClient, SearchFeedClient,
    TrackClient, AlbumClient, PlaylistClient, ArtistClient, LoginClient.CustomInput, GlobalSettingsProvider {
    override fun setGlobalSettings(globalSettings: Settings) {
        ExtensionText.settings = globalSettings
    }


    private lateinit var setting: Settings
    private var loginUser: User? = null

    override fun setLoginUser(user: User?) {
        loginUser = user
        if (user != null) {
            user.extras["userAuthToken"]?.let {
                setting.putString("userAuthToken", it)
            }
        }
    }

    override suspend fun getCurrentUser(): User? {
        val token = userAuthToken ?: return null
        return try {
            val qUser = authenticatedApi(token).getUser().value
            User(
                id = "qobuz:${qUser.id ?: qUser.username ?: "me"}",
                name = qUser.displayName ?: qUser.username ?: "Qobuz User",
                cover = qUser.avatar?.let { it.toImageHolder() },
                extras = mapOf("userAuthToken" to token)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        }
    }

    override val forms: List<LoginClient.Form> get() = listOf(
        LoginClient.Form(
            key = "credentials",
            label = ExtensionText.get("providers.login_with_email_password_117819", "Login with Email & Password"),
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Email,
                    key = "email",
                    label = ExtensionText.get("providers.email_username_2de642", "Email / Username"),
                    isRequired = true
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "password",
                    label = ExtensionText.get("providers.password_e7cf3e", "Password"),
                    isRequired = true
                )
            )
        ),
        LoginClient.Form(
            key = "token",
            label = ExtensionText.get("providers.login_with_auth_token_5923a6", "Login with Auth Token"),
            icon = LoginClient.InputField.Type.Misc,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Misc,
                    key = "userAuthToken",
                    label = ExtensionText.get("providers.user_auth_token_9b3ca7", "User Auth Token"),
                    isRequired = true
                )
            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        return when (key) {
            "credentials" -> {
                val email = data["email"] ?: throw IllegalArgumentException(ExtensionText.get("providers.email_is_required_aae929", "Email is required"))
                val password = data["password"] ?: throw IllegalArgumentException(ExtensionText.get("providers.password_is_required_7aeae1", "Password is required"))
                val resp = api.login(email, password).value
                val token = resp.userAuthToken ?: throw IllegalStateException(ExtensionText.get("providers.no_auth_token_returned_8b4816", "No auth token returned"))
                setting.putString("userAuthToken", token)
                val qUser = resp.user
                val user = User(
                    id = "qobuz:${qUser?.id ?: qUser?.username ?: email}",
                    name = qUser?.displayName ?: qUser?.username ?: email,
                    cover = qUser?.avatar?.let { it.toImageHolder() },
                    extras = mapOf("userAuthToken" to token)
                )
                listOf(user)
            }
            "token" -> {
                val token = data["userAuthToken"] ?: throw IllegalArgumentException(ExtensionText.get("providers.token_is_required_6c7181", "Token is required"))
                setting.putString("userAuthToken", token)
                val qUser = cancellableAttempt { authenticatedApi(token).getUser().value }.getOrNull()
                val user = User(
                    id = "qobuz:${qUser?.id ?: "token_user"}",
                    name = qUser?.displayName ?: qUser?.username ?: "Qobuz User",
                    cover = qUser?.avatar?.let { it.toImageHolder() },
                    extras = mapOf("userAuthToken" to token)
                )
                listOf(user)
            }
            else -> emptyList()
        }
    }

    override suspend fun getSettingItems() = listOf(
        SettingTextInput(
            ExtensionText.get("providers.qobuz_user_auth_token_b60d2b", "Qobuz user_auth_token"),
            "userAuthToken",
            ExtensionText.get("providers.enter_your_qobuz_user_auth_token_optional_defaul_316887", "Enter your Qobuz user_auth_token (optional, defaults to shared rotating pool)"),
            ""
        ),
        SettingTextInput(
            ExtensionText.get("providers.qobuz_resolver_url_898593", "Qobuz Resolver URL"),
            "resolverUrl",
            ExtensionText.get("providers.base_url_for_a_qobuz_resolver_proxy_leave_empty__4bbf44", "Base URL for a Qobuz resolver proxy, leave empty for direct API"),
            ""
        ),
        SettingSwitch(
            ExtensionText.get("providers.always_use_320kbps_64f0bb", "Always use 320kbps"),
            "only320",
            ExtensionText.get("providers.for_streaming_always_use_320kbps_mp3_instead_of__8f53fe", "For streaming, always use 320kbps MP3 instead of FLAC"),
            false
        )
    )

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    val userAuthToken get() = setting.getString("userAuthToken")?.takeIf { it.isNotBlank() }
    val resolverUrl get() = setting.getString("resolverUrl")?.takeIf { it.isNotBlank() }
    val only320 get() = setting.getBoolean("only320") ?: false

    val imageSize by lazy { ImageSize.MEDIUM }
    val api by lazy { QobuzApi(client = httpClient) }

    private fun authenticatedApi(token: String) = QobuzApi(userAuthToken = token, client = httpClient)

    private suspend fun <T> cancellableAttempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun <T> withRetry(block: suspend (QobuzApi) -> T): T {
        userAuthToken?.let { return block(authenticatedApi(it)) }
        TokenPool.fetchPool(httpClient)
        val attempted = mutableSetOf<String>()
        var lastException: Exception? = null
        // Refresh once after exhaustion; do not repeatedly try identical credentials.
        repeat(2) { pass ->
            for (credentials in TokenPool.snapshot()) {
                if (!attempted.add(credentials.token)) continue
                val requestApi = QobuzApi(credentials.token, credentials.appId, credentials.appSecret, httpClient)
                try {
                    val result = block(requestApi)
                    TokenPool.prefer(credentials)
                    return result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastException = error
                }
            }
            if (pass == 0) TokenPool.fetchPool(httpClient, force = true)
        }
        val refresh = TokenPool.refreshFailure?.let { "; pool refresh failed: $it" }.orEmpty()
        throw IllegalStateException("Qobuz direct API: all ${attempted.size} pool accounts failed$refresh", lastException)
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val hasCustom = userAuthToken != null
        val message = if (hasCustom) {
            ExtensionText.get("providers.qobuz_active_with_personal_account_8022b2", "Qobuz active with personal account!")
        } else {
            ExtensionText.get("providers.qobuz_active_with_shared_rotating_token_pool_5e7997", "Qobuz active with shared rotating token pool!")
        }
        val welcomeShelf = Shelf.Lists.Items(
            id = "welcome",
            title = ExtensionText.get("providers.qobuz_lossless_integration_f5f868", "Qobuz Lossless Integration"),
            subtitle = message,
            list = emptyList()
        )
        return Feed(listOf()) {
            listOf(welcomeShelf).toFeedData()
        }
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val tabs = listOf(
            "ALL", "TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS"
        ).map { s ->
            Tab(s, ExtensionText.category(s))
        }
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "ALL" -> {
                    val res = withRetry { api -> api.search(query).value }
                    listOfNotNull(
                        res.tracks.items.toTrackShelf(ExtensionText.category("TRACKS"), imageSize),
                        res.albums.items.toAlbumShelf(ExtensionText.category("ALBUMS"), imageSize),
                        res.artists.items.toArtistShelf(ExtensionText.category("ARTISTS"), imageSize),
                        res.playlists.items.toPlaylistShelf(ExtensionText.category("PLAYLISTS"), imageSize)
                    ).toFeedData()
                }
                else -> searchFeedData(query, tab?.id ?: "ALL")
            }
        }
    }

    private fun searchFeedData(query: String, type: String) = PagedData.Continuous<Shelf> {
        val res = withRetry { api ->
            when (type) {
                "TRACKS" -> api.searchTracks(query).value.tracks.items.toTrackShelf(ExtensionText.category(type), imageSize)
                "ALBUMS" -> api.searchAlbums(query).value.albums.items.toAlbumShelf(ExtensionText.category(type), imageSize)
                "ARTISTS" -> api.searchArtists(query).value.artists.items.toArtistShelf(ExtensionText.category(type), imageSize)
                "PLAYLISTS" -> api.searchPlaylists(query).value.playlists.items.toPlaylistShelf(ExtensionText.category(type), imageSize)
                else -> null
            }
        }
        val items = listOfNotNull(res)
        Page(items, null)
    }.toFeedData()

    override suspend fun loadTrack(
        track: Track, isDownload: Boolean,
    ): Track {
        val qualities = if (only320) {
            listOf(Triple(5, 320, "320kbps MP3"))
        } else {
            listOf(
                Triple(5, 320, "320kbps MP3"),
                Triple(6, 1411, "CD Lossless FLAC"),
                Triple(27, 4608, "Hi-Res FLAC")
            )
        }

        val servers = qualities.map { (formatId, qualityBitrate, label) ->
            server(
                formatId.toString(), qualityBitrate, label,
                mapOf("id" to track.id, "formatId" to formatId.toString())
            )
        }

        return track.copy(streamables = servers)
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ) = when (streamable.type) {
        Streamable.MediaType.Server -> {
            val trackId = streamable.extras["id"] ?: error("Track Id not found")
            val formatId = streamable.extras["formatId"]?.toInt() ?: 6

            val customResolver = resolverUrl
            val streamUrl = if (customResolver != null) {
                fetchFromResolver("${customResolver.trimEnd('/')}/$trackId?quality=$formatId")
            } else {
                cancellableAttempt {
                    withRetry { api ->
                        val fileUrl = api.getFileUrl(trackId.toLong(), formatId).value
                        if (fileUrl.sample) {
                            throw IllegalStateException("Direct API returned 30s sample")
                        }
                        fileUrl.url ?: throw IllegalStateException("Direct API returned empty URL")
                    }
                }.getOrElse { e ->
                    val fallbackResolvers = listOf(
                        "https://citegptapi.f5.si/webhook/qbdlx/stream/$trackId?quality=$formatId",
                        "https://qbdlx.launchpd.cloud/api/stream/$trackId?quality=$formatId"
                    )
                    val failures = mutableListOf<Throwable>()
                    var resolved: String? = null
                    for ((index, rUrl) in fallbackResolvers.withIndex()) {
                        val attempt = cancellableAttempt { fetchFromResolver(rUrl) }
                        resolved = attempt.getOrNull()
                        if (resolved != null) break
                        val failure = attempt.exceptionOrNull()!!
                        // Never include response bodies, credentials or signed URLs in diagnostics.
                        val reason = (failure as? ResolverFailure)?.message ?: failure.javaClass.simpleName
                        failures.add(IllegalStateException("Qobuz resolver ${index + 1}: $reason"))
                    }
                    resolved ?: throw IllegalStateException(
                        "Qobuz playback unavailable: direct API and ${failures.size} resolvers failed; " +
                            failures.joinToString("; ") { it.message.orEmpty() }, e
                    ).apply { failures.forEach { addSuppressed(it) } }
                }
            }

            streamUrl.toServerMedia()
        }

        else -> throw IllegalStateException("Unsupported media type")
    }

    private class ResolverFailure(message: String) : IllegalStateException(message)

    private suspend fun fetchFromResolver(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return httpClient.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw ResolverFailure("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val value = parseStreamResult(body)
            val url = value?.toHttpUrlOrNull()
            url?.toString() ?: throw ResolverFailure("No valid stream URL in response")
        }
    }

    private fun parseStreamResult(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(trimmed).jsonObject
            val obj = (root["data"] as? JsonObject) ?: root
            (obj["url"] ?: obj["streamUrl"] ?: obj["downloadUrl"])
                ?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    override suspend fun loadAlbum(album: Album): Album = withRetry { api ->
        val response = api.album(album.id).value
        response.toAlbum(ImageSize.XLARGE)
    }

    override suspend fun loadTracks(album: Album): Feed<Track> = withRetry { api ->
        val response = api.album(album.id).value
        val convertedAlbum = response.toAlbum(imageSize)
        response.tracks.items.map {
            it.toTrack(convertedAlbum, imageSize)
        }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = withRetry { api ->
        val response = api.playlist(playlist.id).value
        response.toPlaylist(ImageSize.XLARGE)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = withRetry { api ->
        val response = api.playlist(playlist.id).value
        response.tracks.items.map {
            it.toTrack(imageSize)
        }.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist = withRetry { api ->
        val response = api.artist(artist.id).value
        val name = response.albums.items.firstOrNull()?.artist?.name ?: artist.name
        Artist(
            id = artist.id,
            name = name,
            cover = response.albums.items.firstOrNull()?.image.toImage(ImageSize.XLARGE)
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = withRetry { api ->
        val response = api.artist(artist.id).value
        val shelf = response.albums.items.toAlbumShelf(ExtensionText.category("ALBUMS"), imageSize)
        listOfNotNull(shelf).toFeed()
    }
}
