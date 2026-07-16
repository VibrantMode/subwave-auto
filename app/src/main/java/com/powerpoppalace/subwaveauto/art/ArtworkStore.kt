package com.powerpoppalace.subwaveauto.art

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Max covers kept on disk. Gearhead loads content URIs lazily and may retry
 *  minutes after the track change, so the store keeps a real tail, not just
 *  the current cover. */
internal const val MAX_STORE_ENTRIES = 16

/** Byte budget for the artwork dir (normalized covers are ~10–40 KB, so this
 *  is generous headroom, not a working limit). */
internal const val MAX_STORE_BYTES = 10L * 1024 * 1024

/** Provider path version segment — bump if the URI contract ever changes. */
internal const val ART_URI_VERSION = "v1"

/** 64 lowercase hex chars (a SHA-256) — the ONLY path component [ArtworkProvider]
 *  serves. Rejects traversal, extensions, uppercase, anything odd. Pure, JVM-tested. */
internal fun isValidArtHash(s: String): Boolean =
    s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

/** Lowercase-hex SHA-256 of [bytes]. Pure, JVM-tested. */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

/** File extension for a stored cover, from its MIME type. Pure, JVM-tested. */
internal fun extForMime(mime: String?): String = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "img"
}

/** MIME type for a stored cover file, from its extension (inverse of [extForMime]). */
internal fun mimeForExt(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}

/** On-disk metadata one eviction decision needs. */
internal data class StoredFileMeta(val hash: String, val sizeBytes: Long, val lastModified: Long)

/**
 * Which hashes to delete to bring the store within [maxEntries] / [maxTotalBytes]:
 * newest-first survival order (by lastModified), the pinned (currently-on-air)
 * hash always survives regardless of age. Pure, JVM-tested.
 */
internal fun selectEvictions(
    files: List<StoredFileMeta>,
    pinned: String?,
    maxEntries: Int,
    maxTotalBytes: Long,
): List<String> {
    val evict = mutableListOf<String>()
    var count = 0
    var bytes = 0L
    // Pinned first so it always claims its budget slot, then newest → oldest.
    val ordered = files.sortedWith(
        compareByDescending<StoredFileMeta> { it.hash == pinned }.thenByDescending { it.lastModified },
    )
    for (f in ordered) {
        if (f.hash != pinned && (count + 1 > maxEntries || bytes + f.sizeBytes > maxTotalBytes)) {
            evict.add(f.hash)
        } else {
            count++
            bytes += f.sizeBytes
        }
    }
    return evict
}

/**
 * Disk store for normalized cover art, shared by [LiveMetadata][com.powerpoppalace.subwaveauto.playback.LiveMetadata]
 * (writer) and [ArtworkProvider] (reader). Files live in
 * `noBackupFilesDir/artwork/<sha256>.<ext>` — content-hash keyed, so the same
 * cover reached through different URLs dedupes, and a URI handed to gearhead
 * never changes meaning. Writes are atomic (tmp + rename) and happen BEFORE the
 * metadata push publishes the URI, so the provider can always satisfy a read
 * for a URI that exists in session metadata.
 */
internal class ArtworkStore private constructor(
    private val dir: File,
    private val authority: String,
) {

    internal class Entry(val hash: String, val file: File, val mimeType: String) {
        fun contentUri(authority: String): Uri =
            Uri.parse("content://$authority/$ART_URI_VERSION/$hash")
    }

    /** The on-air cover's hash — never evicted. */
    @Volatile
    private var pinnedHash: String? = null

    /**
     * Persist one cover; returns its entry (with the content URI to publish).
     * Existing file for the same bytes → touch + pin, no rewrite. Any I/O
     * failure returns null — callers publish without a URI rather than throw.
     */
    @Synchronized
    fun put(bytes: ByteArray, mimeType: String?): Entry? = try {
        dir.mkdirs()
        val hash = sha256Hex(bytes)
        val ext = extForMime(mimeType)
        val file = File(dir, "$hash.$ext")
        if (!file.exists()) {
            val tmp = File(dir, "$hash.$ext.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("rename failed")
            }
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        pinnedHash = hash
        evict()
        Entry(hash, file, mimeForExt(ext))
    } catch (_: Exception) {
        null
    }

    /** Provider-side lookup by hash. Null for anything not on disk. */
    fun entryFor(hash: String): Entry? {
        if (!isValidArtHash(hash)) return null
        val match = dir.listFiles()?.firstOrNull {
            it.isFile && it.name.substringBeforeLast('.') == hash && !it.name.endsWith(".tmp")
        } ?: return null
        return Entry(hash, match, mimeForExt(match.extension))
    }

    fun uriFor(entry: Entry): Uri = entry.contentUri(authority)

    private fun evict() {
        val metas = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.map { StoredFileMeta(it.name.substringBeforeLast('.'), it.length(), it.lastModified()) }
            ?: return
        val doomed = selectEvictions(metas, pinnedHash, MAX_STORE_ENTRIES, MAX_STORE_BYTES).toSet()
        dir.listFiles()?.forEach {
            val name = it.name
            // Stale tmp files from a crashed write die with the sweep too.
            if (name.substringBeforeLast('.') in doomed || (name.endsWith(".tmp") && it.lastModified() < System.currentTimeMillis() - 60_000)) {
                it.delete()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ArtworkStore? = null

        fun authority(context: Context): String = "${context.packageName}.art"

        /** Process-wide singleton — the provider and the playback service must
         *  see the same directory + pin state. */
        fun get(context: Context): ArtworkStore =
            instance ?: synchronized(this) {
                instance ?: ArtworkStore(
                    File(context.applicationContext.noBackupFilesDir, "artwork"),
                    authority(context),
                ).also { instance = it }
            }
    }
}
