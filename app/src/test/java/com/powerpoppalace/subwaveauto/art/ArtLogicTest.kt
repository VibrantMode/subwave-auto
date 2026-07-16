package com.powerpoppalace.subwaveauto.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the v0.5 artwork pipeline's pure logic: publish-mode
 * mapping, hash/path validation, MIME↔extension mapping, store eviction, and
 * the normalizer's dimension math. No Android types involved.
 */
class ArtLogicTest {

    private val contentUri = "content://com.powerpoppalace.subwaveauto.art/v1/aa11"
    private val remoteUrl = "https://radio.example.com/api/cover/42"

    // --- planArtFields: mode → published fields ---

    @Test
    fun productionMode_publishesContentUriAndBytes() {
        val plan = planArtFields(ArtMode.CONTENT_PLUS_DATA, contentUri, remoteUrl, hasBytes = true)
        assertEquals(contentUri, plan.uri)
        assertTrue(plan.includeData)
    }

    @Test
    fun productionMode_storeFailure_fallsBackToBytesOnly_neverRemote() {
        // The remote URL is exactly what newer gearhead rejects — a failed store
        // write must NOT resurrect it.
        val plan = planArtFields(ArtMode.CONTENT_PLUS_DATA, contentUri = null, remoteUrl = remoteUrl, hasBytes = true)
        assertNull(plan.uri)
        assertTrue(plan.includeData)
    }

    @Test
    fun contentOnlyMode_noInlineBytes() {
        val plan = planArtFields(ArtMode.CONTENT_ONLY, contentUri, remoteUrl, hasBytes = true)
        assertEquals(contentUri, plan.uri)
        assertFalse(plan.includeData)
    }

    @Test
    fun dataOnlyMode_noUri() {
        val plan = planArtFields(ArtMode.DATA_ONLY, contentUri, remoteUrl, hasBytes = true)
        assertNull(plan.uri)
        assertTrue(plan.includeData)
    }

    @Test
    fun remoteMode_reproducesV04Behavior() {
        val plan = planArtFields(ArtMode.REMOTE_PLUS_DATA, contentUri, remoteUrl, hasBytes = true)
        assertEquals(remoteUrl, plan.uri)
        assertTrue(plan.includeData)
    }

    @Test
    fun failedFetch_publishesNoBytesInAnyMode() {
        for (mode in ArtMode.entries) {
            assertFalse(planArtFields(mode, contentUri, remoteUrl, hasBytes = false).includeData)
        }
    }

    // --- ArtMode.fromPref: tolerant read ---

    @Test
    fun artMode_fromPref_roundTripsAllModes_unknownFallsBackToProduction() {
        for (mode in ArtMode.entries) assertEquals(mode, ArtMode.fromPref(mode.prefValue))
        assertEquals(ArtMode.CONTENT_PLUS_DATA, ArtMode.fromPref(null))
        assertEquals(ArtMode.CONTENT_PLUS_DATA, ArtMode.fromPref("garbage"))
    }

    // --- hash + provider path validation ---

    @Test
    fun sha256Hex_knownVector() {
        // SHA-256("abc") — FIPS 180-2 test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun isValidArtHash_acceptsOnlyFullLowercaseHex() {
        assertTrue(isValidArtHash(sha256Hex(byteArrayOf(1))))
        assertFalse(isValidArtHash(""))
        assertFalse(isValidArtHash("abc"))
        assertFalse(isValidArtHash("A".repeat(64))) // uppercase
        assertFalse(isValidArtHash("g".repeat(64))) // non-hex
        assertFalse(isValidArtHash("../" + "a".repeat(61))) // traversal shape
        assertFalse(isValidArtHash("a".repeat(63)))
        assertFalse(isValidArtHash("a".repeat(65)))
    }

    // --- MIME ↔ extension ---

    @Test
    fun mimeAndExtension_roundTrip() {
        assertEquals("jpg", extForMime("image/jpeg"))
        assertEquals("png", extForMime("image/png"))
        assertEquals("webp", extForMime("image/webp"))
        assertEquals("jpg", extForMime("image/jpeg; charset=binary"))
        assertEquals("img", extForMime(null))
        assertEquals("img", extForMime("application/json"))
        assertEquals("image/jpeg", mimeForExt("jpg"))
        assertEquals("image/png", mimeForExt("png"))
        assertEquals("image/webp", mimeForExt("webp"))
        assertEquals("application/octet-stream", mimeForExt("img"))
    }

    // --- selectEvictions: newest-first survival, pin always wins ---

    private fun meta(hash: String, size: Long, modified: Long) = StoredFileMeta(hash, size, modified)

    @Test
    fun eviction_underLimits_nothingEvicted() {
        val files = (1..5).map { meta("h$it", 10_000, it.toLong()) }
        assertTrue(selectEvictions(files, pinned = "h1", maxEntries = 16, maxTotalBytes = 1_000_000).isEmpty())
    }

    @Test
    fun eviction_overEntryCount_dropsOldestFirst() {
        val files = (1..20).map { meta("h$it", 100, it.toLong()) } // h20 newest
        val evicted = selectEvictions(files, pinned = null, maxEntries = 16, maxTotalBytes = 1_000_000)
        assertEquals(listOf("h4", "h3", "h2", "h1"), evicted)
    }

    @Test
    fun eviction_pinnedOldEntry_survivesWhileYoungerDies() {
        val files = (1..20).map { meta("h$it", 100, it.toLong()) }
        val evicted = selectEvictions(files, pinned = "h1", maxEntries = 16, maxTotalBytes = 1_000_000)
        assertFalse("h1" in evicted) // pinned = on-air cover, never evicted
        assertTrue("h5" in evicted) // an extra younger entry dies in its place
        assertEquals(4, evicted.size)
    }

    @Test
    fun eviction_overByteBudget_dropsOldest() {
        val files = (1..4).map { meta("h$it", 400, it.toLong()) } // 1600 B total
        val evicted = selectEvictions(files, pinned = null, maxEntries = 16, maxTotalBytes = 1_000)
        assertEquals(listOf("h2", "h1"), evicted)
    }

    // --- normalizer dimension math ---

    @Test
    fun computeInSampleSize_powersOfTwoTowardTarget() {
        assertEquals(1, computeInSampleSize(300, 300, 320))
        assertEquals(1, computeInSampleSize(320, 320, 320))
        assertEquals(2, computeInSampleSize(1000, 1000, 320))
        assertEquals(4, computeInSampleSize(2000, 1400, 320))
        assertEquals(1, computeInSampleSize(0, 100, 320)) // degenerate input
    }

    @Test
    fun fitWithin_preservesAspectAndSmallImages() {
        assertEquals(Pair(320, 320), fitWithin(1000, 1000, 320))
        assertEquals(Pair(320, 240), fitWithin(1200, 900, 320))
        assertEquals(Pair(160, 320), fitWithin(500, 1000, 320))
        assertEquals(Pair(200, 100), fitWithin(200, 100, 320)) // already small — untouched
        assertEquals(Pair(320, 1), fitWithin(10_000, 3, 320)) // extreme banner never hits 0
    }
}
