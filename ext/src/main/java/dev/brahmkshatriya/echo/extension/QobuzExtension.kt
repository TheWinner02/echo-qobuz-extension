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

object TokenPool {
    private val defaultTokens = listOf(
        "jM-6F2QcDpfG7fj1RRPq7bAa7tBVCykt__5HD1K25v2yFq0c9_-SmXEhG-74moNpN5YQTmFFyyMq2F70h1G17A",
        "1aFowv-ylpS5sYZv2ifXwHVjES9RX752HUozlaDS6YqZ4Fugp3pfNb3_40h2IV0IzBzqpkTPpmUi5SHGNP6qIQ",
        "e5LOIO2m1Da_MCglsOH2I_gjKlmd3dOUguFe9btPlkeSe5vcwU-zUWVyJF272_n_XvIP7M-yAKIpbre_WTqRfw",
        "J1nl2UXyZ9Pd2SF5s_YjvyNORbwe1UwNjHchv-UgOcE_WgrVSQvCoFQdTxgjYyBYDqgWfHfOlVT5wGZlvINrHA",
        "79ilZ_slkk1p0ZEoyR0MbynH4m9W1AnDrSlP1wQjyVfzfsa14g5N__AJ2kngJT-j9pNqa6u_qDLiP4_SBapbyA"
    )
    
    private var activeTokens = defaultTokens.toMutableList()
    private var currentIndex = 0
    private var isFetched = false

    fun getActiveToken(): String {
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
                val tokens = mutableListOf<String>()
                for (element in array) {
                    val obj = element.jsonObject
                    val token = obj["token"]?.jsonPrimitive?.contentOrNull
                    val appId = obj["app_id"]?.jsonPrimitive?.contentOrNull ?: obj["app_id"]?.toString()
                    if (!token.isNullOrBlank() && appId == "798273057") {
                        tokens.add(token)
                    }
                }
                if (tokens.isNotEmpty()) {
                    activeTokens = tokens.distinct().toMutableList()
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
        val maxAttempts = TokenPool.getActiveToken().let { 5 }
        while (attempts < maxAttempts) {
            val token = TokenPool.getActiveToken()
            api.userAuthToken = token
            try {
                return block()
            } catch (e: Exception) {
                if (e.message?.contains("401") == true) {
                    TokenPool.rotateToken()
                    attempts++
                } else {
                    throw e
                }
            }
        }
        throw IllegalStateException("Qobuz Pool: All shared tokens failed authentication")
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
            listOf(5 to "320kbps MP3")
        } else {
            listOf(
                5 to "320kbps MP3",
                6 to "CD Lossless FLAC",
                27 to "Hi-Res FLAC"
            )
        }

        val servers = qualities.map { (formatId, label) ->
            server(
                formatId.toString(), formatId, label,
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