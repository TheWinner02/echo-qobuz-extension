package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.extension.model.*

fun QobuzImage?.toImage(size: ImageSize): ImageHolder? {
    val url = this?.let {
        when (size) {
            ImageSize.XXL, ImageSize.XLARGE -> large ?: medium ?: small
            ImageSize.LARGE, ImageSize.MEDIUM -> medium ?: large ?: small
            ImageSize.SMALL -> small ?: thumbnail ?: medium
        }
    } ?: return null
    return url.toImageHolder()
}

fun QobuzTrack.toTrack(size: ImageSize) = Track(
    id = id.toString(),
    title = title,
    cover = album?.image.toImage(size),
    album = album?.let {
        Album(
            id = it.id,
            title = it.title,
            cover = it.image.toImage(size),
            artists = listOfNotNull(performer?.name?.let { p -> Artist(id = "", name = p) })
        )
    },
    artists = listOfNotNull(performer?.name?.let {
        Artist(
            id = "", // Qobuz performer ID might not be in track search, set empty or retrieve
            name = it
        )
    }),
    duration = duration * 1000L,
    isrc = isrc,
    isPlayable = (if (streamable) Track.Playable.Yes else Track.Playable.No) as Track.Playable,
    isRadioSupported = false
)

fun QobuzAlbumItem.toAlbum(size: ImageSize) = Album(
    id = id,
    title = title,
    cover = image.toImage(size),
    artists = listOfNotNull(artist?.name?.let {
        Artist(
            id = "",
            name = it
        )
    }),
    trackCount = tracks_count.toLong(),
    isRadioSupported = false
)

fun QobuzAlbumDetailResponse.toAlbum(size: ImageSize) = Album(
    id = id,
    title = title,
    cover = image.toImage(size),
    artists = listOfNotNull(artist?.name?.let {
        Artist(
            id = "",
            name = it
        )
    }),
    trackCount = tracks.items.size.toLong(),
    isRadioSupported = false
)

fun QobuzAlbumTrackItem.toTrack(album: Album, size: ImageSize) = Track(
    id = id.toString(),
    title = title,
    cover = album.cover,
    album = album,
    artists = listOfNotNull(performer?.name?.let {
        Artist(
            id = "",
            name = it
        )
    } ?: album.artists.firstOrNull()),
    duration = duration * 1000L,
    isPlayable = Track.Playable.Yes,
    isRadioSupported = false
)

fun QobuzArtistItem.toArtist(size: ImageSize) = Artist(
    id = id.toString(),
    name = name,
    cover = image.toImage(size),
)

fun QobuzPlaylistItem.toPlaylist(size: ImageSize) = Playlist(
    id = id.toString(),
    title = name,
    isEditable = false,
    isPrivate = false,
    cover = null, // Qobuz playlists might not have cover images easily, or use placeholder
    description = description,
    trackCount = tracks_count.toLong(),
    isRadioSupported = false
)

fun QobuzPlaylistDetailResponse.toPlaylist(size: ImageSize) = Playlist(
    id = id.toString(),
    title = name,
    isEditable = false,
    isPrivate = false,
    cover = null,
    description = description,
    trackCount = tracks.items.size.toLong(),
    isRadioSupported = false
)

// Shelf conversion helpers
fun List<QobuzTrack>?.toTrackShelf(title: String, size: ImageSize) = if (isNullOrEmpty()) null
else Shelf.Lists.Tracks(
    id = title,
    title = title.lowercase().replaceFirstChar { it.uppercase() },
    list = map { it.toTrack(size) }
)

fun List<QobuzAlbumItem>?.toAlbumShelf(title: String, size: ImageSize) = if (isNullOrEmpty()) null
else Shelf.Lists.Items(
    id = title,
    title = title.lowercase().replaceFirstChar { it.uppercase() },
    list = map { it.toAlbum(size) }
)

fun List<QobuzArtistItem>?.toArtistShelf(title: String, size: ImageSize) = if (isNullOrEmpty()) null
else Shelf.Lists.Items(
    id = title,
    title = title.lowercase().replaceFirstChar { it.uppercase() },
    list = map { it.toArtist(size) }
)

fun List<QobuzPlaylistItem>?.toPlaylistShelf(title: String, size: ImageSize) = if (isNullOrEmpty()) null
else Shelf.Lists.Items(
    id = title,
    title = title.lowercase().replaceFirstChar { it.uppercase() },
    list = map { it.toPlaylist(size) }
)