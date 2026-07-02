package com.powerpoppalace.subwaveauto.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.powerpoppalace.subwaveauto.net.NowPlaying
import com.powerpoppalace.subwaveauto.net.StationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Poll cadence for `/api/now-playing` while playing — 5 s, same as the web/RN players. */
private const val POLL_INTERVAL_MS = 5_000L

/**
 * `MediaMetadata.artworkData` cap (§1: "artworkData capped 1 MB"). This sits BELOW
 * [StationApi.MAX_ART_BYTES] (2 MB download cap): an image the API returns but that
 * exceeds this cap is skipped — metadata still updates, just without artwork.
 */
internal const val MAX_METADATA_ART_BYTES = 1024 * 1024

/** Artwork byte-cache size (§1: "artwork cache ≤ 8 entries (LRU)"). */
internal const val MAX_ART_CACHE_ENTRIES = 8

/**
 * The (title, artist) identity tuple used for change detection — a metadata push
 * happens only when this tuple changes (album/art alone never trigger one).
 * Pure function, unit-tested on the plain JVM.
 */
internal fun nowPlayingTuple(np: NowPlaying): Pair<String?, String?> = np.title to np.artist

/**
 * The artist line as shown on car screens: `"<artist> • <station>"`. The artist
 * line is the one spot EVERY Android Auto head unit (and BT/AVRCP display)
 * renders, so the station branding rides there; the semantic
 * [MediaMetadata.station] field is set too, for units that show it separately.
 * Pure function, unit-tested on the plain JVM.
 */
internal fun displayArtist(artist: String?, station: String?): String? = when {
    artist != null && station != null -> "$artist • $station"
    artist != null -> artist
    station != null -> station
    else -> null
}

/**
 * Tiny LRU byte cache keyed by artwork URL. LinkedHashMap in access order + an
 * eldest-entry cap — no external deps. Synchronized because puts happen from IO
 * pollers while (hypothetically) reads could race; cheap at 8 entries.
 */
internal class ArtCache(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, ByteArray>(maxEntries + 1, 1f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(url: String): ByteArray? = map[url]

    @Synchronized
    fun put(url: String, bytes: ByteArray) {
        map[url] = bytes
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun keys(): Set<String> = map.keys.toSet()
}

/**
 * Live now-playing → [MediaMetadata] sync (ANDROID_AUTO_PLAN.md §2 WP3).
 *
 * While the player is playing, polls `{base}/api/now-playing` every [POLL_INTERVAL_MS]
 * and, when the (title, artist) tuple changes, pushes fresh metadata (title, artist,
 * album, cover art) onto the live media item. AA, Bluetooth/AVRCP, and the media
 * notification all render from the session's metadata, so one push updates them all.
 *
 * Threading: polling + artwork fetch run on IO (inside [StationApi]); the player is
 * ONLY touched via `withContext(Dispatchers.Main.immediate)` — media3's threading
 * contract (all [Player] access on the application main thread).
 *
 * Base-URL change: [api] is a mutable `var` (deliberate, minimal deviation from §1's
 * `private val`, mirroring [BrowseTree.api]) — PlaybackService's StationPrefs listener
 * assigns the rebuilt StationApi; the next poll targets the new station. Only ever
 * mutated from the main thread.
 *
 * @param player the service's live-edge ForwardingPlayer (never the raw ExoPlayer).
 * @param scope  service-owned main-thread scope; cancelled in the service's onDestroy,
 *               which also kills any in-flight poll.
 */
class LiveMetadata(
    private val player: Player,
    var api: StationApi,
    private val scope: CoroutineScope,
) {

    /** Active poll loop, non-null only while playing. */
    private var pollJob: Job? = null

    /**
     * Last (title, artist) tuple applied to the player. Reset to null whenever
     * playback stops so a restart re-pushes metadata even if the song is unchanged.
     */
    private var lastTuple: Pair<String?, String?>? = null

    private val artCache = ArtCache(MAX_ART_CACHE_ENTRIES)

    /** Poll ONLY while playing — no network spin while paused/stopped. */
    private val playListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPolling() else stopPolling()
        }
    }

    /** Call from the service's onCreate (main thread). */
    fun start() {
        player.addListener(playListener)
        if (player.isPlaying) startPolling()
    }

    /** Call from the service's onDestroy (main thread). Safe to call repeatedly. */
    fun stop() {
        player.removeListener(playListener)
        stopPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        // Playback stopped → forget the last tuple so a restart re-pushes metadata.
        lastTuple = null
    }

    /**
     * One poll tick. Never throws (short of cancellation): a down station, malformed
     * payload, or failed artwork fetch keeps the LAST metadata and tries again next tick.
     */
    private suspend fun pollOnce() {
        val np = try {
            api.nowPlaying() // internally on Dispatchers.IO; null on any error
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } ?: return // station briefly down → keep last metadata, never crash the loop

        val tuple = nowPlayingTuple(np)
        if (tuple == lastTuple) return

        // Artwork on IO (inside StationApi), BEFORE touching the player.
        val art = np.artUrl?.let { fetchArtCached(it) }

        // Player access only on the main thread (media3 threading contract).
        withContext(Dispatchers.Main.immediate) {
            if (player.mediaItemCount == 0) return@withContext
            val current = player.getMediaItemAt(0)
            val newMeta = current.mediaMetadata.buildUpon() // keep MEDIA_TYPE_RADIO_STATION / isPlayable
                .setTitle(np.title ?: np.stationName ?: "SUB/WAVE")
                // Station branding rides the artist line — the one field every AA
                // unit and BT display renders (see displayArtist).
                .setArtist(displayArtist(np.artist, np.stationName) ?: "Live broadcast")
                .setAlbumTitle(np.album)
                .setStation(np.stationName)
                // Explicitly set (or clear) artwork so a track without art doesn't
                // keep the previous song's cover.
                .setArtworkData(art, if (art != null) MediaMetadata.PICTURE_TYPE_FRONT_COVER else null)
                .build()
            // Same URI, new metadata → media3 keeps playback uninterrupted (the URI
            // is unchanged, so replaceMediaItem is a metadata-only update).
            // FALLBACK (plan §4 risk table): if device QA (WP6 matrix #4) hears an
            // audio glitch on this path, switch to the ForwardingPlayer-metadata
            // pattern instead — override getMediaMetadata() on the service's
            // LiveEdgePlayer and emit onMediaMetadataChanged — and document which
            // shipped.
            player.replaceMediaItem(
                0,
                current.buildUpon().setMediaMetadata(newMeta).build(),
            )
        }

        lastTuple = tuple
    }

    /**
     * Artwork bytes for [MediaMetadata.artworkData]: LRU-cached by URL; fetched via
     * [StationApi.fetchArt] (2 MB download cap upstream); skipped — returning null,
     * never failing the metadata push — when the image exceeds [MAX_METADATA_ART_BYTES].
     */
    private suspend fun fetchArtCached(url: String): ByteArray? {
        artCache.get(url)?.let { return it }
        val bytes = api.fetchArt(url) ?: return null
        if (bytes.size > MAX_METADATA_ART_BYTES) return null
        artCache.put(url, bytes)
        return bytes
    }
}
