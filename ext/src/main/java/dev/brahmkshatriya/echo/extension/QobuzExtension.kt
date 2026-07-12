package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.model.ImageSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class QobuzExtension : ExtensionClient, HomeFeedClient, SearchFeedClient,
    TrackClient, AlbumClient, PlaylistClient, ArtistClient {

    private lateinit var setting: Settings

    override suspend fun getSettingItems() = listOf(
        SettingTextInput(
            "Qobuz user_auth_token",
            "userAuthToken",
            "Enter your Qobuz user_auth_token to enable direct lossless streaming",
            ""
        ),
        SettingTextInput(
            "Qobuz Resolver URL",
            "resolverUrl",
            "Base URL for a Qobuz resolver proxy (like http://192.168.1.100:8000), leave empty for direct API",
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
    val api by lazy { QobuzApi().apply { userAuthToken = this@QobuzExtension.userAuthToken } }
    val httpClient = OkHttpClient()

    override suspend fun loadHomeFeed(cursor: String?): Feed<Shelf> {
        // Return a simple category feed to welcome the user and explain configuration
        val message = if (userAuthToken == null && resolverUrl == null) {
            "Please configure your Qobuz user_auth_token or a Resolver URL in Settings."
        } else {
            "Qobuz Extension is active! Use Search to find music."
        }
        return Feed(listOf(
            Shelf.Lists.Items(
                id = "welcome",
                title = "Qobuz Lossless Integration",
                subtitle = message,
                list = emptyList()
            )
        ))
    }

    override suspend fun search(
        query: String,
        context: SearchFeedClient.Context?,
    ): Feed<Shelf> {
        api.userAuthToken = userAuthToken
        if (context != null) {
            val tab = context.tab
            return Feed(listOf()) {
                val res = api.search(query).value
                when (tab?.id) {
                    "ALL" -> listOfNotNull(
                        res.tracks.items.toTrackShelf("TRACKS", imageSize),
                        res.albums.items.toAlbumShelf("ALBUMS", imageSize),
                        res.artists.items.toArtistShelf("ARTISTS", imageSize),
                        res.playlists.items.toPlaylistShelf("PLAYLISTS", imageSize)
                    ).toFeedData()
                    "TRACKS" -> api.searchTracks(query).value.tracks.items.toTrackShelf("TRACKS", imageSize).toFeedData()
                    "ARTISTS" -> api.searchArtists(query).value.artists.items.toArtistShelf("ARTISTS", imageSize).toFeedData()
                    "ALBUMS" -> api.searchAlbums(query).value.albums.items.toAlbumShelf("ALBUMS", imageSize).toFeedData()
                    "PLAYLISTS" -> api.searchPlaylists(query).value.playlists.items.toPlaylistShelf("PLAYLISTS", imageSize).toFeedData()
                    else -> throw Exception("Unknown tab id: ${tab?.id}")
                }
            }
        } else {
            val tabs = listOf(
                "ALL", "TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS"
            ).map { s ->
                Tab(s, s.lowercase().replaceFirstChar { it.uppercase() })
            }
            Feed(tabs) {
                when (it?.id) {
                    "ALL" -> {
                        val res = api.search(query).value
                        listOfNotNull(
                            res.tracks.items.toTrackShelf("TRACKS", imageSize),
                            res.albums.items.toAlbumShelf("ALBUMS", imageSize),
                            res.artists.items.toArtistShelf("ARTISTS", imageSize),
                            res.playlists.items.toPlaylistShelf("PLAYLISTS", imageSize)
                        ).toFeedData()
                    }
                    else -> searchFeedData(query, it!!.id)
                }
            }
        }
    }

    private fun searchFeedData(query: String, type: String) = PagedData.Continuous<Shelf> {
        val res = when (type) {
            "TRACKS" -> api.searchTracks(query).value.tracks.items.toTrackShelf(type, imageSize)
            "ALBUMS" -> api.searchAlbums(query).value.albums.items.toAlbumShelf(type, imageSize)
            "ARTISTS" -> api.searchArtists(query).value.artists.items.toArtistShelf(type, imageSize)
            "PLAYLISTS" -> api.searchPlaylists(query).value.playlists.items.toPlaylistShelf(type, imageSize)
            else -> null
        }
        val items = listOfNotNull(res)
        Page(items, null)
    }.toFeedData()

    override suspend fun loadTrack(
        track: Track, isDownload: Boolean,
    ): Track {
        api.userAuthToken = userAuthToken
        
        // Define available qualities (5 = MP3, 6 = CD FLAC, 27 = Hi-Res FLAC)
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
            api.userAuthToken = userAuthToken

            val streamUrl = if (resolverUrl != null) {
                // Route stream requests through custom resolver proxy
                // E.g. GET <resolverUrl>/<trackId>?quality=<formatId>
                val url = "${resolverUrl.trimEnd('/')}/$trackId?quality=$formatId"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Resolver failed: ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    parseStreamResult(body) ?: throw IllegalStateException("Could not parse stream URL")
                }
            } else {
                // Direct Qobuz API call
                val fileUrl = api.getFileUrl(trackId.toLong(), formatId).value
                fileUrl.url ?: throw IllegalStateException("Direct API returned empty URL")
            }

            streamUrl.toServerMedia()
        }

        else -> throw IllegalStateException("Unsupported media type")
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

    override suspend fun loadAlbum(album: Album): Album {
        api.userAuthToken = userAuthToken
        val response = api.album(album.id).value
        return response.toAlbum(ImageSize.XLARGE)
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        api.userAuthToken = userAuthToken
        val response = api.album(album.id).value
        val convertedAlbum = response.toAlbum(imageSize)
        return response.tracks.items.map {
            it.toTrack(convertedAlbum, imageSize)
        }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        api.userAuthToken = userAuthToken
        val response = api.playlist(playlist.id).value
        return response.toPlaylist(ImageSize.XLARGE)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track>? {
        api.userAuthToken = userAuthToken
        val response = api.playlist(playlist.id).value
        return response.tracks.items.map {
            it.toTrack(imageSize)
        }.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist {
        api.userAuthToken = userAuthToken
        val response = api.artist(artist.id.toLong()).value
        // Load first album info or search for artist metadata
        val name = response.albums.items.firstOrNull()?.artist?.name ?: artist.name
        return Artist(
            id = artist.id,
            name = name,
            cover = response.albums.items.firstOrNull()?.image.toImage(ImageSize.XLARGE)
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf>? {
        api.userAuthToken = userAuthToken
        val response = api.artist(artist.id.toLong()).value
        val shelf = response.albums.items.toAlbumShelf("ALBUMS", imageSize)
        return listOfNotNull(shelf).toFeed()
    }
}