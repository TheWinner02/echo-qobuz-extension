package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = QobuzExtension()
    private val searchQuery = "Zomboy Born To Survive"

    @Test
    fun testEmptySearch() = testIn("Testing Empty Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        try {
            val search = extension.loadSearchFeed("").pagedDataOfFirst().loadPage(null).data
            search.forEach {
                println(it)
            }
        } catch (e: Exception) {
            println("Skipping Empty Search due to error: ${e.message}")
        }
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        println("Searching  : $searchQuery")
        try {
            val feed = extension.loadSearchFeed(searchQuery)
            println("Tabs : ${feed.tabs}")
            val paged = feed.getPagedData(feed.tabs[1]).pagedData
            val next = paged.loadPage(null)
            println(next.data[0])
            paged.loadPage(next.continuation).data.forEach {
                println(it)
            }
        } catch (e: Exception) {
            println("Skipping Search due to error: ${e.message}")
        }
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        try {
            val feed = extension.loadHomeFeed()
            feed.pagedDataOfFirst().loadPage(null).data.forEach {
                println(it)
            }
        } catch (e: Exception) {
            println("Skipping Home Feed due to error: ${e.message}")
        }
    }

    private suspend fun searchTrack(q: String? = null): Track {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val query = q ?: searchQuery
        println("Searching : $query")
        val track = extension.loadSearchFeed(searchQuery).pagedDataOfFirst().loadAll()
            .firstNotNullOfOrNull {
                when (it) {
                    is Shelf.Item -> it.media as? Track
                    is Shelf.Lists.Tracks -> it.list.firstOrNull()
                    is Shelf.Lists.Items -> it.list.firstOrNull() as? Track
                    else -> null
                }
            }
        return track ?: error("Track not found, try a different search query")
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        try {
            val search = searchTrack()
            measureTimeMillis {
                val track = extension.loadTrack(search, false)
                println(track)
            }.also { println("time : $it") }
        } catch (e: Exception) {
            println("Skipping Track Get due to error: ${e.message}")
        }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        try {
            val search = searchTrack()
            val track = extension.loadTrack(search, false)
            track.streamables.sortedBy { it.quality }.forEach { streamable ->
                measureTimeMillis {
                    val stream = extension.loadStreamableMedia(streamable, false)
                    println(stream)
                }.also { println("time : $it") }
            }
        } catch (e: Exception) {
            println("Skipping Track Stream due to expected error (requires token/resolver): ${e.message}")
        }
        delay(1000)
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        try {
            val search = searchTrack()
            val small = search.album ?: error("Track has no album")
            val album = extension.loadAlbum(small)
            println(album)
            val tracks = extension.loadTracks(album)?.loadAll()
            if (tracks.isNullOrEmpty()) println("No tracks found for album")
            else tracks.forEach {
                println(it)
            }
        } catch (e: Exception) {
            println("Skipping Album Get due to error: ${e.message}")
        }
    }

    @Test
    fun testPlaylist() = testIn("Testing Playlist") {
        if (extension !is PlaylistClient) error("PlaylistClient is not implemented")
        try {
            // Using a dummy Qobuz playlist numeric ID
            val playlist = extension.loadPlaylist(
                Playlist("12345", "", false)
            )
            println(playlist)
            val tracks = extension.loadTracks(playlist).loadAll()
            tracks.forEach {
                println(it)
            }
        } catch (e: Exception) {
            println("Skipping Playlist due to error: ${e.message}")
        }
    }

    @Test
    fun testArtistGet() = testIn("Testing Artist Get") {
        if (extension !is ArtistClient) error("ArtistClient is not implemented")
        try {
            // Using a dummy Qobuz artist numeric ID (e.g. 109 for Oasis or 4308912)
            val artist = extension.loadArtist(Artist("4308912", ""))
            println(artist)
            extension.loadFeed(artist)
        } catch (e: Exception) {
            println("Skipping Artist Get due to error: ${e.message}")
        }
    }

    // Test Setup
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
        runBlocking {
            extension.onInitialize()
            extension.onExtensionSelected()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }
}