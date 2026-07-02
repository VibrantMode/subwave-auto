package com.powerpoppalace.subwaveauto.playback

import com.powerpoppalace.subwaveauto.net.NowPlaying
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for LiveMetadata's pure logic: the (title, artist) change-detection
 * tuple and the LRU artwork byte cache. No Android/media3 types involved.
 */
class LiveMetadataLogicTest {

    private fun np(
        title: String? = "Starry Eyes",
        artist: String? = "The Records",
        album: String? = "Shades in Bed",
        artUrl: String? = "https://cdn.example.com/covers/abc.jpg",
    ) = NowPlaying(title = title, artist = artist, album = album, artUrl = artUrl, streamOnline = true)

    // --- nowPlayingTuple: change detection ---

    @Test
    fun sameTitleAndArtist_tupleUnchanged() {
        assertEquals(nowPlayingTuple(np()), nowPlayingTuple(np()))
    }

    @Test
    fun albumOrArtChangeAlone_tupleUnchanged() {
        // Album/art alone never trigger a metadata push — only title+artist identity.
        assertEquals(
            nowPlayingTuple(np(album = "Shades in Bed", artUrl = "https://a/1.jpg")),
            nowPlayingTuple(np(album = "Crashes", artUrl = "https://a/2.jpg")),
        )
    }

    @Test
    fun titleChange_tupleChanges() {
        assertNotEquals(nowPlayingTuple(np(title = "Teenarama")), nowPlayingTuple(np()))
    }

    @Test
    fun artistChange_tupleChanges() {
        assertNotEquals(nowPlayingTuple(np(artist = "The Rubinoos")), nowPlayingTuple(np()))
    }

    @Test
    fun nullFields_stillComparable() {
        val sparse = np(title = null, artist = null)
        assertEquals(nowPlayingTuple(sparse), nowPlayingTuple(sparse.copy()))
        assertNotEquals(nowPlayingTuple(sparse), nowPlayingTuple(np()))
    }

    // --- ArtCache: LRU, max 8 entries ---

    @Test
    fun artCache_missReturnsNull_hitReturnsBytes() {
        val cache = ArtCache(MAX_ART_CACHE_ENTRIES)
        assertNull(cache.get("https://a/missing.jpg"))
        val bytes = byteArrayOf(1, 2, 3)
        cache.put("https://a/1.jpg", bytes)
        assertArrayEquals(bytes, cache.get("https://a/1.jpg"))
    }

    @Test
    fun artCache_evictsEldestBeyondEightEntries() {
        val cache = ArtCache(MAX_ART_CACHE_ENTRIES)
        for (i in 1..9) cache.put("https://a/$i.jpg", byteArrayOf(i.toByte()))
        assertEquals(8, cache.size())
        assertNull(cache.get("https://a/1.jpg")) // eldest evicted
        assertArrayEquals(byteArrayOf(9), cache.get("https://a/9.jpg"))
    }

    @Test
    fun artCache_accessOrder_recentlyReadEntrySurvivesEviction() {
        val cache = ArtCache(MAX_ART_CACHE_ENTRIES)
        for (i in 1..8) cache.put("https://a/$i.jpg", byteArrayOf(i.toByte()))
        cache.get("https://a/1.jpg") // touch the eldest → now most-recently-used
        cache.put("https://a/9.jpg", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(1), cache.get("https://a/1.jpg")) // survived
        assertNull(cache.get("https://a/2.jpg")) // the true LRU entry was evicted
        assertEquals(8, cache.size())
    }

    // --- caps sanity (§1 contract values) ---

    @Test
    fun metadataArtCap_isOneMegabyte_belowStationApiDownloadCap() {
        assertEquals(1024 * 1024, MAX_METADATA_ART_BYTES)
        assertTrue(MAX_METADATA_ART_BYTES < com.powerpoppalace.subwaveauto.net.StationApi.MAX_ART_BYTES)
    }

    // --- displayArtist: station branding on the artist line ---

    @Test
    fun displayArtist_artistAndStation_joinedWithBullet() {
        assertEquals(
            "Nikki and the Corvettes • Power Pop Palace",
            displayArtist("Nikki and the Corvettes", "Power Pop Palace"),
        )
    }

    @Test
    fun displayArtist_partialAndEmptyInputs() {
        assertEquals("Just Artist", displayArtist("Just Artist", null))
        assertEquals("Power Pop Palace", displayArtist(null, "Power Pop Palace"))
        assertEquals(null, displayArtist(null, null))
    }
}
