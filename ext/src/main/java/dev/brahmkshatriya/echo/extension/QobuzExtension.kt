package dev.brahmkshatriya.echo.extension

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
import okhttp3.Request

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
    
    private var activeTokens = defaultTokens.toMutableList()
    private var currentIndex = 0
    private var isFetched = false

    val tokenCount: Int get() = activeTokens.size

    fun getActiveToken(): TokenInfo {
        if (activeTokens.isEmpty()) return defaultTokens[0]
        return activeTokens[currentIndex % activeTokens.size]
    }

    fun rotateToken() {
        if (activeTokens.isNotEmpty()) {
            currentIndex = (currentIndex + 1) % activeTokens.size
        }
    }

    suspend fun fetchPool(client: OkHttpClient) {
        if (isFetched) return
        try {
            val req = Request.Builder()
                .url("https://citegptapi.f5.si/webhook/qbdlx/shared")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Origin", "https://qbdlx.launchpd.cloud")
                .header("Referer", "https://qbdlx.launchpd.cloud/")
                .build()
            val response = client.newCall(req).await()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = Json { ignoreUnknownKeys = true }
                val array = json.parseToJsonElement(body).jsonArray
                val tokens = mutableListOf<TokenInfo>()
                for (element in array.reversed()) {
                    val obj = element.jsonObject
                    val token = obj["token"]?.jsonPrimitive?.contentOrNull
                    val appId = obj["app_id"]?.jsonPrimitive?.contentOrNull ?: "798273057"
                    val appSecret = obj["app_secret"]?.jsonPrimitive?.contentOrNull ?: if (appId == "312369995") "e79f8b9be485692b0e5f9dd895826368" else "abb21364945c0583309667d13ca3d93a"
                    if (!token.isNullOrBlank()) {
                        tokens.add(TokenInfo(token, appId, appSecret))
                    }
                }
                if (tokens.isNotEmpty()) {
                    activeTokens = tokens.distinctBy { it.token }.toMutableList()
                    currentIndex = 0
                    isFetched = true
                }
            }
        } catch (e: Exception) {
            // Fallback to default hardcoded pool
        }
    }
}

class QobuzExtension : ExtensionClient, HomeFeedClient, SearchFeedClient,
    TrackClient, AlbumClient, PlaylistClient, ArtistClient, LoginClient.CustomInput {

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
            api.userAuthToken = token
            val qUser = api.getUser().value
            User(
                id = "qobuz:${qUser.id ?: qUser.username ?: "me"}",
                name = qUser.displayName ?: qUser.username ?: "Qobuz User",
                cover = qUser.avatar?.let { it.toImageHolder() },
                extras = mapOf("userAuthToken" to token)
            )
        } catch (e: Exception) {
            null
        }
    }

    override val forms: List<LoginClient.Form> = listOf(
        LoginClient.Form(
            key = "credentials",
            label = "Login with Email & Password",
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Email,
                    key = "email",
                    label = "Email / Username",
                    isRequired = true
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "password",
                    label = "Password",
                    isRequired = true
                )
            )
        ),
        LoginClient.Form(
            key = "token",
            label = "Login with Auth Token",
            icon = LoginClient.InputField.Type.Misc,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Misc,
                    key = "userAuthToken",
                    label = "User Auth Token",
                    isRequired = true
                )
            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        return when (key) {
            "credentials" -> {
                val email = data["email"] ?: throw IllegalArgumentException("Email is required")
                val password = data["password"] ?: throw IllegalArgumentException("Password is required")
                val resp = api.login(email, password).value
                val token = resp.userAuthToken ?: throw IllegalStateException("No auth token returned")
                setting.putString("userAuthToken", token)
                api.userAuthToken = token
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
                val token = data["userAuthToken"] ?: throw IllegalArgumentException("Token is required")
                setting.putString("userAuthToken", token)
                api.userAuthToken = token
                val qUser = runCatching { api.getUser().value }.getOrNull()
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
            "Qobuz user_auth_token",
            "userAuthToken",
            "Enter your Qobuz user_auth_token (optional, defaults to shared rotating pool)",
            ""
        ),
        SettingTextInput(
            "Qobuz Resolver URL",
            "resolverUrl",
            "Base URL for a Qobuz resolver proxy, leave empty for direct API",
            ""
        ),
        SettingSwitch(
            "Always use 320kbps",
            "only320",
            "For streaming, always use 320kbps MP3 instead of FLAC",
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
    val api by lazy { QobuzApi() }
    val httpClient = OkHttpClient()

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        val customToken = userAuthToken
        if (customToken != null) {
            api.userAuthToken = customToken
            return block()
        }
        
        TokenPool.fetchPool(httpClient)
        
        var attempts = 0
        val maxAttempts = TokenPool.tokenCount.coerceAtLeast(5)
        var lastException: Exception? = null
        while (attempts < maxAttempts) {
            val tokenInfo = TokenPool.getActiveToken()
            api.userAuthToken = tokenInfo.token
            api.currentAppId = tokenInfo.appId
            api.currentAppSecret = tokenInfo.appSecret
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                TokenPool.rotateToken()
                attempts++
            }
        }
        throw (lastException ?: IllegalStateException("Qobuz Pool: All shared tokens failed"))
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val hasCustom = userAuthToken != null
        val message = if (hasCustom) {
            "Qobuz active with personal account!"
        } else {
            "Qobuz active with shared rotating token pool!"
        }
        val welcomeShelf = Shelf.Lists.Items(
            id = "welcome",
            title = "Qobuz Lossless Integration",
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
            Tab(s, s.lowercase().replaceFirstChar { it.uppercase() })
        }
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "ALL" -> {
                    val res = withRetry { api.search(query).value }
                    listOfNotNull(
                        res.tracks.items.toTrackShelf("TRACKS", imageSize),
                        res.albums.items.toAlbumShelf("ALBUMS", imageSize),
                        res.artists.items.toArtistShelf("ARTISTS", imageSize),
                        res.playlists.items.toPlaylistShelf("PLAYLISTS", imageSize)
                    ).toFeedData()
                }
                else -> searchFeedData(query, tab?.id ?: "ALL")
            }
        }
    }

    private fun searchFeedData(query: String, type: String) = PagedData.Continuous<Shelf> {
        val res = withRetry {
            when (type) {
                "TRACKS" -> api.searchTracks(query).value.tracks.items.toTrackShelf(type, imageSize)
                "ALBUMS" -> api.searchAlbums(query).value.albums.items.toAlbumShelf(type, imageSize)
                "ARTISTS" -> api.searchArtists(query).value.artists.items.toArtistShelf(type, imageSize)
                "PLAYLISTS" -> api.searchPlaylists(query).value.playlists.items.toPlaylistShelf(type, imageSize)
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
                runCatching {
                    withRetry {
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
                    var resolved: String? = null
                    for (rUrl in fallbackResolvers) {
                        resolved = runCatching { fetchFromResolver(rUrl) }.getOrNull()
                        if (resolved != null) break
                    }
                    resolved ?: throw e
                }
            }

            streamUrl.toServerMedia()
        }

        else -> throw IllegalStateException("Unsupported media type")
    }

    private suspend fun fetchFromResolver(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return httpClient.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Resolver failed: ${resp.code}")
            val body = resp.body?.string().orEmpty()
            parseStreamResult(body) ?: throw IllegalStateException("Could not parse stream URL")
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

    override suspend fun loadAlbum(album: Album): Album = withRetry {
        val response = api.album(album.id).value
        response.toAlbum(ImageSize.XLARGE)
    }

    override suspend fun loadTracks(album: Album): Feed<Track> = withRetry {
        val response = api.album(album.id).value
        val convertedAlbum = response.toAlbum(imageSize)
        response.tracks.items.map {
            it.toTrack(convertedAlbum, imageSize)
        }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = withRetry {
        val response = api.playlist(playlist.id).value
        response.toPlaylist(ImageSize.XLARGE)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = withRetry {
        val response = api.playlist(playlist.id).value
        response.tracks.items.map {
            it.toTrack(imageSize)
        }.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist = withRetry {
        val response = api.artist(artist.id).value
        val name = response.albums.items.firstOrNull()?.artist?.name ?: artist.name
        Artist(
            id = artist.id,
            name = name,
            cover = response.albums.items.firstOrNull()?.image.toImage(ImageSize.XLARGE)
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = withRetry {
        val response = api.artist(artist.id).value
        val shelf = response.albums.items.toAlbumShelf("ALBUMS", imageSize)
        listOfNotNull(shelf).toFeed()
    }
}