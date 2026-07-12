package dev.brahmkshatriya.echo.extension.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── catalog/search ─────────────────────────────────────────────────────
@Serializable
data class QobuzSearchResponse(
    val tracks: QobuzTrackList = QobuzTrackList(),
    val albums: QobuzAlbumList = QobuzAlbumList(),
    val artists: QobuzArtistList = QobuzArtistList(),
    val playlists: QobuzPlaylistList = QobuzPlaylistList()
)

@Serializable
data class QobuzTrackList(val items: List<QobuzTrack> = emptyList())

@Serializable
data class QobuzTrack(
    val id: Long = 0,
    val title: String = "",
    val isrc: String? = null,
    val duration: Int = 0,                        // seconds
    val streamable: Boolean = true,
    val performer: QobuzPerformer? = null,
    @SerialName("maximum_bit_depth") val maximumBitDepth: Int = 0,
    @SerialName("maximum_sampling_rate") val maximumSamplingRate: Float = 0f,  // kHz
    val album: QobuzAlbum? = null,
)

@Serializable data class QobuzPerformer(val name: String = "")
@Serializable data class QobuzAlbum(
    val id: String = "",
    val title: String = "",
    val image: QobuzImage? = null
)
@Serializable data class QobuzImage(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
    val thumbnail: String? = null
)

// ── track/getFileUrl ───────────────────────────────────────────────────
@Serializable
data class QobuzFileUrl(
    val url: String? = null,
    @SerialName("format_id") val formatId: Int = 0,
    @SerialName("bit_depth") val bitDepth: Int = 0,
    @SerialName("sampling_rate") val samplingRate: Float = 0f, // kHz
    val sample: Boolean = false,
    val restrictions: List<QobuzRestriction> = emptyList(),
)

@Serializable data class QobuzRestriction(val code: String = "")

// ── catalog/search?type=artists ────────────────────────────────────────
@Serializable
data class QobuzArtistSearchResponse(val artists: QobuzArtistList = QobuzArtistList())

@Serializable
data class QobuzArtistList(val items: List<QobuzArtistItem> = emptyList())

@Serializable
data class QobuzArtistItem(
    val id: Long = 0,
    val name: String = "",
    val image: QobuzImage? = null
)

// ── artist/get?extra=albums ────────────────────────────────────────────
@Serializable
data class QobuzArtistAlbumsResponse(val albums: QobuzAlbumList = QobuzAlbumList())

@Serializable
data class QobuzAlbumList(val items: List<QobuzAlbumItem> = emptyList())

@Serializable
data class QobuzAlbumItem(
    val id: String = "",                          // string slug, e.g. "qf6qfzou4fwrb"
    val title: String = "",
    val artist: QobuzPerformer? = null,           // object with { name }
    val image: QobuzImage? = null,
    val release_date_original: String? = null,    // "YYYY-MM-DD"
    val tracks_count: Int = 0,
)

// ── album/get ──────────────────────────────────────────────────────────
@Serializable
data class QobuzAlbumDetailResponse(
    val id: String = "",
    val title: String = "",
    val artist: QobuzPerformer? = null,
    val image: QobuzImage? = null,
    val release_date_original: String? = null,
    val tracks: QobuzAlbumTrackList = QobuzAlbumTrackList(),
)

@Serializable
data class QobuzAlbumTrackList(val items: List<QobuzAlbumTrackItem> = emptyList())

@Serializable
data class QobuzAlbumTrackItem(
    val id: Long = 0,                             // numeric track id
    val title: String = "",
    val performer: QobuzPerformer? = null,
    val duration: Int = 0,                        // seconds
)

// ── playlist/get ───────────────────────────────────────────────────────
@Serializable
data class QobuzPlaylistList(val items: List<QobuzPlaylistItem> = emptyList())

@Serializable
data class QobuzPlaylistItem(
    val id: Long = 0,
    val name: String = "",
    val description: String? = null,
    val tracks_count: Int = 0,
    val owner: QobuzOwner? = null,
)

@Serializable data class QobuzOwner(val name: String = "")

@Serializable
data class QobuzPlaylistDetailResponse(
    val id: Long = 0,
    val name: String = "",
    val description: String? = null,
    val tracks: QobuzPlaylistTrackList = QobuzPlaylistTrackList()
)

@Serializable
data class QobuzPlaylistTrackList(val items: List<QobuzTrack> = emptyList())
