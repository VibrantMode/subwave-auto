package com.powerpoppalace.subwaveauto.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP client for one SUB/WAVE station.
 *
 * Contract (ANDROID_AUTO_PLAN.md §1): every failure path — network error, non-2xx,
 * malformed JSON, empty body, oversized artwork — returns `null`. These functions
 * never throw (the sole exception: coroutine cancellation is re-thrown, which only
 * happens when the caller itself is being cancelled).
 *
 * @param baseUrl station base WITHOUT trailing slash (see [com.powerpoppalace.subwaveauto.prefs.StationPrefs]).
 * @param client  injectable for tests only (short timeouts); production call sites
 *                use the shared default client (8 s call timeout).
 */
class StationApi(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient,
) {

    /** GET {base}/api/now-playing → parsed [NowPlaying], or null on any error. */
    suspend fun nowPlaying(): NowPlaying? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/now-playing").get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@withContext null
                parseNowPlaying(body, baseUrl)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch artwork bytes for `MediaMetadata.artworkData`. Null on any failure,
     * including empty bodies and anything larger than [MAX_ART_BYTES] (~2 MB) —
     * checked against Content-Length when declared, and enforced by capping the
     * read for chunked/undeclared responses.
     */
    suspend fun fetchArt(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
            val request = Request.Builder().url(httpUrl).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val declared = body.contentLength()
                if (declared > MAX_ART_BYTES) return@withContext null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                val stream = body.byteStream()
                var total = 0L
                while (true) {
                    val n = stream.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > MAX_ART_BYTES) return@withContext null
                    out.write(buf, 0, n)
                }
                if (total == 0L) null else out.toByteArray()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Live stream URL. With [cacheBust] (the default) appends `?t=<now-ms>` so a
     * (re)connect never resumes a stale CDN/proxy buffer — mirrors
     * `app/src/audio/player.ts:77`.
     */
    fun streamUrl(cacheBust: Boolean = true): String =
        if (cacheBust) "$baseUrl/stream.mp3?t=${System.currentTimeMillis()}"
        else "$baseUrl/stream.mp3"

    companion object {
        /** Artwork download cap (~2 MB). */
        const val MAX_ART_BYTES: Long = 2L * 1024 * 1024

        /** One shared client so multiple StationApi instances don't multiply thread pools. */
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Parse the `/api/now-playing` payload. All parsing lives here (single seam).
         * Returns null only for malformed JSON; a valid-but-sparse object yields a
         * [NowPlaying] with null fields and `streamOnline = true`.
         *
         * Field mapping (verified against the live station 2026-07-02): the track
         * fields live in a NESTED `nowPlaying` object (`nowPlaying.title` /
         * `.artist` / `.album`), with a top-level fallback kept for older payloads
         * and other stations. Artwork is top-level `art` falling back to top-level
         * `cover` — the controller emits a CONTROLLER-RELATIVE path (`/cover/<id>`)
         * whose public URL sits under the `/api` prefix (Caddy strips it), so
         * relative paths resolve to `{base}/api{path}`, never the origin root
         * (which 404s). `streamOnline` falls back to nested `stream.online`,
         * defaulting to true. Extra fields (`listeners`, `dj`, `session`, …) are
         * ignored.
         */
        internal fun parseNowPlaying(body: String, baseUrl: String): NowPlaying? {
            return try {
                val o = JSONObject(body)
                val track = o.optJSONObject("nowPlaying")
                val rawArt = str(o, "art") ?: str(o, "cover")
                NowPlaying(
                    title = track?.let { str(it, "title") } ?: str(o, "title"),
                    artist = track?.let { str(it, "artist") } ?: str(o, "artist"),
                    album = track?.let { str(it, "album") } ?: str(o, "album"),
                    artUrl = resolveArtUrl(rawArt, baseUrl),
                    streamOnline = streamOnline(o),
                    // Station display name, e.g. dj.station = "Power Pop Palace".
                    stationName = o.optJSONObject("dj")?.let { str(it, "station") },
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Non-blank string field or null (org.json's optString would return ""). */
        private fun str(o: JSONObject, key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.optString(key).takeIf { it.isNotBlank() }
            else null

        /** `streamOnline`, falling back to `stream.online`, defaulting to true. */
        private fun streamOnline(o: JSONObject): Boolean {
            if (o.has("streamOnline") && !o.isNull("streamOnline")) {
                return o.optBoolean("streamOnline", true)
            }
            val stream = o.optJSONObject("stream")
            if (stream != null && stream.has("online") && !stream.isNull("online")) {
                return stream.optBoolean("online", true)
            }
            return true
        }

        /**
         * Absolute art URLs pass through; relative ones are CONTROLLER-relative and
         * publicly served under the `/api` prefix — `/cover/<id>` →
         * `{base}/api/cover/<id>`. (Resolving against the origin root 404s: Caddy
         * routes the `/api` prefix to the controller, everything else to the web app.)
         */
        private fun resolveArtUrl(raw: String?, baseUrl: String): String? {
            if (raw.isNullOrBlank()) return null
            if (raw.toHttpUrlOrNull() != null) return raw
            val path = if (raw.startsWith("/")) raw else "/$raw"
            return "$baseUrl/api$path".toHttpUrlOrNull()?.toString()
        }
    }
}
