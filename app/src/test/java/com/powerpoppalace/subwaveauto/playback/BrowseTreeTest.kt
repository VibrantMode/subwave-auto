package com.powerpoppalace.subwaveauto.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.powerpoppalace.subwaveauto.net.StationApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the AA-critical resolution seam (ANDROID_AUTO_PLAN.md §2 WP3 step 4):
 * Android Auto sends bare mediaIds with NO URI — every requested item must resolve
 * to the fully-formed live item (fresh `?t=` cache-busted stream URI, explicit
 * AUDIO_MPEG mime, [LIVE_ITEM_ID]).
 *
 * [BrowseTree.resolveMediaItems] is exactly the list `onAddMediaItems` returns
 * (that callback is a one-line delegate); testing it directly avoids constructing
 * a real MediaSession/ControllerInfo (neither is constructible in a unit test).
 *
 * Uses a real [StationApi] against a fake base URL — no network is touched:
 * `streamUrl()` is pure string building.
 *
 * Robolectric because media3's MediaItem.Builder needs a real android.net.Uri
 * (the mockable android.jar throws). SDK pinned to 34 — Robolectric 4.14.1
 * doesn't emulate this module's compileSdk 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseTreeTest {

    private val base = "https://station.example.com"
    private val api = StationApi(base)
    private val tree = BrowseTree(api)

    private fun assertIsFullyFormedLiveItem(item: MediaItem, notBeforeMs: Long, notAfterMs: Long) {
        assertEquals(LIVE_ITEM_ID, item.mediaId)

        val config = item.localConfiguration
        assertNotNull("resolved item must carry a stream URI", config)
        val uri = config!!.uri.toString()
        assertTrue("URI must target the station stream: $uri", uri.startsWith("$base/stream.mp3?t="))
        val t = uri.substringAfter("?t=")
        assertTrue("cache-buster must be non-empty", t.isNotEmpty())
        val tMs = t.toLong() // throws (fails the test) if not numeric
        assertTrue("cache-buster must be FRESH (minted at resolve time)", tMs in notBeforeMs..notAfterMs)

        assertEquals(MimeTypes.AUDIO_MPEG, config.mimeType)

        val meta = item.mediaMetadata
        assertEquals(MediaMetadata.MEDIA_TYPE_RADIO_STATION, meta.mediaType)
        assertEquals(true, meta.isPlayable)
        assertEquals(false, meta.isBrowsable)
        assertEquals("SUB/WAVE", meta.title.toString())
    }

    // --- liveMediaItem: the single shared builder ---

    @Test
    fun liveMediaItem_isFullyFormed() {
        val before = System.currentTimeMillis()
        val item = liveMediaItem(api)
        val after = System.currentTimeMillis()
        assertIsFullyFormedLiveItem(item, before, after)
    }

    @Test
    fun liveMediaItem_mintsAFreshCacheBusterPerCall() {
        val first = liveMediaItem(api).localConfiguration!!.uri.toString()
        Thread.sleep(2) // currentTimeMillis granularity
        val second = liveMediaItem(api).localConfiguration!!.uri.toString()
        assertFalse("consecutive resolves must never reuse an old ?t=", first == second)
    }

    // --- onAddMediaItems resolution (via its delegate resolveMediaItems) ---

    @Test
    fun resolve_liveItemId_returnsFullyFormedLiveItem() {
        val requested = MediaItem.Builder().setMediaId(LIVE_ITEM_ID).build() // bare id, no URI — what AA sends
        val before = System.currentTimeMillis()
        val resolved = tree.resolveMediaItems(listOf(requested))
        val after = System.currentTimeMillis()
        assertEquals(1, resolved.size)
        assertIsFullyFormedLiveItem(resolved[0], before, after)
    }

    @Test
    fun resolve_unknownBareMediaId_stillResolvesToLiveItem() {
        // ANY requested id maps to the live item — there is only one thing to play.
        val requested = MediaItem.Builder().setMediaId("some_unknown_id").build()
        val before = System.currentTimeMillis()
        val resolved = tree.resolveMediaItems(listOf(requested))
        val after = System.currentTimeMillis()
        assertEquals(1, resolved.size)
        assertIsFullyFormedLiveItem(resolved[0], before, after)
    }

    @Test
    fun resolve_usesTheCurrentApi_afterBaseUrlSwap() {
        val swapped = BrowseTree(StationApi("https://old.example.com"))
        swapped.api = StationApi(base) // what PlaybackService's prefs listener does
        val resolved = swapped.resolveMediaItems(listOf(MediaItem.Builder().setMediaId(LIVE_ITEM_ID).build()))
        assertTrue(resolved[0].localConfiguration!!.uri.toString().startsWith("$base/stream.mp3?t="))
    }

    // --- browse-tree items ---

    @Test
    fun rootItem_isBrowsableNotPlayable() {
        val root = tree.rootItem()
        assertEquals(ROOT_ID, root.mediaId)
        assertEquals("SUB/WAVE", root.mediaMetadata.title.toString())
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
    }

    @Test
    fun browseLiveItem_isTheLiveItemWithSubtitle() {
        val before = System.currentTimeMillis()
        val item = tree.browseLiveItem()
        val after = System.currentTimeMillis()
        assertIsFullyFormedLiveItem(item, before, after)
        assertEquals("Live radio", item.mediaMetadata.subtitle.toString())
    }
}
