package com.powerpoppalace.subwaveauto.art

/**
 * How artwork is published onto the media session (v0.5 Pixel fix + diagnostics).
 *
 * Background: Android Auto's documented contract requires artwork URIs to be
 * LOCAL (`content://` / `android.resource://`). v0.4 set the cover's remote
 * HTTPS URL as `artworkUri`; older gearhead builds permissively fetched it
 * (why it worked on the dev S24) but newer builds — Pixels get them first —
 * prefer the URI route and refuse the unsupported scheme, rendering artless
 * even though the inline `artworkData` bitmap sits right beside it in the
 * platform metadata. Production mode therefore serves the cover through
 * [ArtworkProvider] as a `content://` URI, keeping inline bytes as the
 * immediate bitmap for the notification + Bluetooth.
 *
 * The non-default modes exist ONLY for field diagnosis (hidden phone-UI
 * switch): one affected Pixel user flipping through them tells us exactly
 * which artwork route their gearhead build honors.
 */
internal enum class ArtMode(val prefValue: String, val label: String) {
    /** Production: content:// URI + inline bytes. */
    CONTENT_PLUS_DATA("content+data", "Local URI + bytes (default)"),

    /** Diagnostic: content:// URI only — proves gearhead's URI route alone. */
    CONTENT_ONLY("content", "Local URI only"),

    /** Diagnostic: inline bytes only — proves gearhead's session-bitmap route. */
    DATA_ONLY("data", "Bytes only"),

    /** Diagnostic: v0.4 behavior (remote HTTPS URI + bytes) for A/B comparison. */
    REMOTE_PLUS_DATA("remote+data", "Remote URL + bytes (v0.4)");

    companion object {
        /** Tolerant read: unknown/absent pref values fall back to production. */
        fun fromPref(value: String?): ArtMode =
            entries.firstOrNull { it.prefValue == value } ?: CONTENT_PLUS_DATA
    }
}

/** What a metadata push should publish for artwork. See [planArtFields]. */
internal data class ArtFieldPlan(val uri: String?, val includeData: Boolean)

/**
 * Pure mapping from the active [ArtMode] to the artwork fields of one push.
 * [contentUri] is null when the store write failed (fall back to no URI rather
 * than the remote URL — the remote URL is the thing newer gearhead rejects);
 * [hasBytes] is false when the inline fetch failed (the bounded retry in
 * LiveMetadata handles recovery). JVM-tested.
 */
internal fun planArtFields(
    mode: ArtMode,
    contentUri: String?,
    remoteUrl: String?,
    hasBytes: Boolean,
): ArtFieldPlan = when (mode) {
    ArtMode.CONTENT_PLUS_DATA -> ArtFieldPlan(contentUri, hasBytes)
    ArtMode.CONTENT_ONLY -> ArtFieldPlan(contentUri, false)
    ArtMode.DATA_ONLY -> ArtFieldPlan(null, hasBytes)
    ArtMode.REMOTE_PLUS_DATA -> ArtFieldPlan(remoteUrl, hasBytes)
}
