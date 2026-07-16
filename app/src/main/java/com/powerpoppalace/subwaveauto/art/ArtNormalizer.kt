package com.powerpoppalace.subwaveauto.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * Longest artwork edge after normalization. 320 px is what car head units
 * actually render; anything bigger only inflates the decoded ARGB bitmap the
 * platform session must parcel (a 100 KB JPEG can decode to a multi-megabyte
 * bitmap — the DECODED size is the binder-relevant number, not the file size).
 */
internal const val ART_TARGET_MAX_DIM = 320

/** Baseline-JPEG quality for the normalized cover (~10–40 KB at 320 px). */
internal const val ART_JPEG_QUALITY = 85

/**
 * Power-of-two `BitmapFactory.Options.inSampleSize` that gets the decode within
 * ~2× of [target] on the longest edge (final exact fit happens via scaling).
 * Pure, JVM-tested.
 */
internal fun computeInSampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= target) {
        sample *= 2
        longest /= 2
    }
    return sample
}

/**
 * Dimensions scaled to fit within [maxDim] on the longest edge, preserving
 * aspect ratio; already-small images pass through unchanged. Pure, JVM-tested.
 */
internal fun fitWithin(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return Pair(width, height)
    val longest = maxOf(width, height)
    if (longest <= maxDim) return Pair(width, height)
    val scale = maxDim.toDouble() / longest
    return Pair(
        (width * scale).toInt().coerceAtLeast(1),
        (height * scale).toInt().coerceAtLeast(1),
    )
}

/** A normalized cover: sRGB baseline JPEG bytes + final dimensions. */
internal class NormalizedArt(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Decode-scale-reencode a fetched cover into a small, maximally-compatible
 * JPEG: ≤ [ART_TARGET_MAX_DIM] px on the longest edge, transparency composited
 * onto black (JPEG has no alpha; black matches the car UI), profiles/metadata
 * dropped by the re-encode. One normalized asset feeds BOTH delivery paths —
 * the inline `artworkData` bytes and the [ArtworkProvider] file — so what the
 * notification shows and what gearhead loads are byte-identical.
 *
 * Returns null when the input isn't decodable as an image; callers fall back
 * to the original bytes (or skip artwork) rather than failing the push.
 */
internal object ArtNormalizer {

    fun normalize(input: ByteArray): NormalizedArt? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, ART_TARGET_MAX_DIM)
            }
            val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, opts)
            if (decoded == null) {
                null
            } else {
                val (w, h) = fitWithin(decoded.width, decoded.height, ART_TARGET_MAX_DIM)
                val scaled = if (w != decoded.width || h != decoded.height) {
                    Bitmap.createScaledBitmap(decoded, w, h, /* filter = */ true)
                } else {
                    decoded
                }
                // JPEG can't carry alpha — composite transparent covers onto a
                // solid ground instead of letting the encoder pick undefined black
                // fringes. Opaque covers (the overwhelming majority) skip this.
                val flat = if (scaled.hasAlpha()) {
                    val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(out)
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(scaled, 0f, 0f, null)
                    if (scaled !== decoded) scaled.recycle()
                    out
                } else {
                    scaled
                }
                val sink = ByteArrayOutputStream()
                val ok = flat.compress(Bitmap.CompressFormat.JPEG, ART_JPEG_QUALITY, sink)
                if (flat !== decoded) flat.recycle()
                decoded.recycle()
                if (ok) NormalizedArt(sink.toByteArray(), w, h) else null
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
