package com.powerpoppalace.subwaveauto.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM matrix for [normalizeBaseUrl] — the https-except-private-hosts rule
 * from ANDROID_AUTO_PLAN.md §1. No Android Context involved.
 */
class NormalizeBaseUrlTest {

    // --- https: always accepted ---

    @Test
    fun httpsPublicHost_accepted() {
        assertEquals(
            "https://radio.powerpoppalace.com",
            normalizeBaseUrl("https://radio.powerpoppalace.com")
        )
    }

    @Test
    fun httpsWithPort_accepted() {
        assertEquals("https://example.com:8443", normalizeBaseUrl("https://example.com:8443"))
    }

    @Test
    fun httpsWithPath_accepted() {
        assertEquals("https://example.com/radio", normalizeBaseUrl("https://example.com/radio"))
    }

    // --- http: public hosts rejected ---

    @Test
    fun httpPublicHost_rejected() {
        assertNull(normalizeBaseUrl("http://radio.powerpoppalace.com"))
    }

    @Test
    fun httpPublicIp_rejected() {
        assertNull(normalizeBaseUrl("http://8.8.8.8"))
    }

    // --- http: private/dev hosts accepted ---

    @Test
    fun httpLocalhost_accepted() {
        assertEquals("http://localhost:7700", normalizeBaseUrl("http://localhost:7700"))
    }

    @Test
    fun httpLoopback_accepted() {
        assertEquals("http://127.0.0.1:7700", normalizeBaseUrl("http://127.0.0.1:7700"))
    }

    @Test
    fun http10Dot_accepted() {
        assertEquals("http://10.0.0.5", normalizeBaseUrl("http://10.0.0.5"))
    }

    @Test
    fun http192168_accepted() {
        assertEquals("http://192.168.25.15:7700", normalizeBaseUrl("http://192.168.25.15:7700"))
    }

    @Test
    fun http172_16_accepted_lowerBound() {
        assertEquals("http://172.16.0.1", normalizeBaseUrl("http://172.16.0.1"))
    }

    @Test
    fun http172_31_accepted_upperBound() {
        assertEquals("http://172.31.255.1", normalizeBaseUrl("http://172.31.255.1"))
    }

    @Test
    fun http172_32_rejected_outsidePrivateRange() {
        assertNull(normalizeBaseUrl("http://172.32.0.1"))
    }

    @Test
    fun http172_15_rejected_outsidePrivateRange() {
        assertNull(normalizeBaseUrl("http://172.15.0.1"))
    }

    // --- lookalike hosts must NOT sneak through the private-prefix checks ---

    @Test
    fun httpPublicHostContainingLocalhostWord_rejected() {
        assertNull(normalizeBaseUrl("http://localhost.evil.com"))
    }

    @Test
    fun http192168Lookalike_rejected() {
        assertNull(normalizeBaseUrl("http://192.168.evil.com"))
    }

    // --- normalization ---

    @Test
    fun trailingSlash_stripped() {
        assertEquals(
            "https://radio.powerpoppalace.com",
            normalizeBaseUrl("https://radio.powerpoppalace.com/")
        )
    }

    @Test
    fun multipleTrailingSlashes_stripped() {
        assertEquals("https://example.com", normalizeBaseUrl("https://example.com///"))
    }

    @Test
    fun surroundingWhitespace_trimmed() {
        assertEquals(
            "https://radio.powerpoppalace.com",
            normalizeBaseUrl("  https://radio.powerpoppalace.com/  ")
        )
    }

    @Test
    fun localhostCaseInsensitive_accepted() {
        assertEquals("http://LOCALHOST:7700", normalizeBaseUrl("http://LOCALHOST:7700"))
    }

    // --- garbage rejected ---

    @Test
    fun emptyString_rejected() {
        assertNull(normalizeBaseUrl(""))
    }

    @Test
    fun whitespaceOnly_rejected() {
        assertNull(normalizeBaseUrl("   "))
    }

    @Test
    fun slashesOnly_rejected() {
        assertNull(normalizeBaseUrl("///"))
    }

    @Test
    fun noScheme_rejected() {
        assertNull(normalizeBaseUrl("radio.powerpoppalace.com"))
    }

    @Test
    fun wrongScheme_rejected() {
        assertNull(normalizeBaseUrl("ftp://example.com"))
    }

    @Test
    fun notAUrl_rejected() {
        assertNull(normalizeBaseUrl("definitely not a url"))
    }

    @Test
    fun schemeOnly_rejected() {
        assertNull(normalizeBaseUrl("https://"))
    }

    @Test
    fun embeddedSpace_rejected() {
        assertNull(normalizeBaseUrl("https://exa mple.com"))
    }
}
