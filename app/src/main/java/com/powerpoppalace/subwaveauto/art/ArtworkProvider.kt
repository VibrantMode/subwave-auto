package com.powerpoppalace.subwaveauto.art

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Read-only content provider serving normalized cover art to Android Auto
 * (v0.5 Pixel artwork fix). AA's documented contract wants artwork as a LOCAL
 * `content://` URI — this is that URI's backend. URIs look like
 * `content://<applicationId>.art/v1/<sha256>` and resolve to files written by
 * [ArtworkStore] BEFORE the session metadata publishes them.
 *
 * Deliberately `exported="true"` with no permission and no URI grants: cover
 * art of a public radio station is public data, and metadata is not an Intent —
 * `grantUriPermissions` can never flow through a media session, while
 * per-package grants (gearhead, systemui, OEM variants) are a brittle
 * allowlist. The attack surface is a hash-validated read of an app-owned
 * cache file; every write/query mutation path is rejected.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** `v1/<sha256>` → hash, or null for any other shape (traversal, junk). */
    private fun hashFor(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != ART_URI_VERSION) return null
        return segments[1].takeIf { isValidArtHash(it) }
    }

    private fun entryFor(uri: Uri): ArtworkStore.Entry? {
        val ctx = context ?: return null
        val hash = hashFor(uri) ?: return null
        return ArtworkStore.get(ctx).entryFor(hash)
    }

    override fun getType(uri: Uri): String? = entryFor(uri)?.mimeType

    // Advertise the image stream so a typed-asset open with an image wildcard
    // filter resolves through the default openAssetFile → openFile chain.
    // (Line comment on purpose: Kotlin BLOCK comments nest, so a literal
    // image-slash-star filter string inside a KDoc opens an unclosed comment.)
    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        val mime = entryFor(uri)?.mimeType ?: return null
        return if (compareMimeTypes(mime, mimeTypeFilter)) arrayOf(mime) else null
    }

    private fun compareMimeTypes(concrete: String, filter: String): Boolean {
        val f = filter.split('/')
        val c = concrete.split('/')
        if (f.size != 2 || c.size != 2) return false
        return (f[0] == "*" || f[0] == c[0]) && (f[1] == "*" || f[1] == c[1])
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only provider")
        val entry = entryFor(uri)
        val caller = runCatching { callingPackage }.getOrNull()
        ArtDiagnostics.recordProviderOpen(caller, uri.path ?: "?", entry != null)
        if (entry == null) throw FileNotFoundException("no such artwork: $uri")
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Minimal defensive metadata for clients that query before opening. */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val entry = entryFor(uri) ?: return cursor
        cursor.addRow(
            cols.map { col ->
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> entry.file.name
                    OpenableColumns.SIZE -> entry.file.length()
                    else -> null
                }
            },
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only provider")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("read-only provider")
}
