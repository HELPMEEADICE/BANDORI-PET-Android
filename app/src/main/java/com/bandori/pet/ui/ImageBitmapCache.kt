package com.bandori.pet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max

internal object ImageBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }
    private val missing = LruCache<String, Boolean>(MAX_MISSING_ENTRIES)

    operator fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        missing.remove(key)
        cache.put(key, bitmap)
    }

    fun isKnownMissing(key: String): Boolean = missing.get(key) == true

    fun markMissing(key: String) {
        if (cache.get(key) == null) missing.put(key, true)
    }

    private fun cacheSizeKb(): Int =
        (Runtime.getRuntime().maxMemory() / 1024L / 16L)
            .coerceIn(8L * 1024L, 32L * 1024L)
            .toInt()

    private const val MAX_MISSING_ENTRIES = 256
}

/** Decodes UI images close to their display size to avoid large allocations and upload stalls. */
internal object SampledImageDecoder {
    fun decodeAsset(context: Context, path: String, maxEdge: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = decodeOptions(bounds.outWidth, bounds.outHeight, maxEdge)
        val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        scaleToMaxEdge(bitmap, maxEdge).asImageBitmap()
    }.getOrNull()

    fun decodeBytes(bytes: ByteArray, maxEdge: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            decodeOptions(bounds.outWidth, bounds.outHeight, maxEdge),
        ) ?: return@runCatching null
        scaleToMaxEdge(bitmap, maxEdge).asImageBitmap()
    }.getOrNull()

    fun decodeContentUri(context: Context, uri: Uri, maxEdge: Int): ImageBitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions(bounds.outWidth, bounds.outHeight, maxEdge))
        } ?: return@runCatching null
        scaleToMaxEdge(bitmap, maxEdge).asImageBitmap()
    }.getOrNull()

    private fun decodeOptions(width: Int, height: Int, maxEdge: Int): BitmapFactory.Options {
        var sampleSize = 1
        val sourceEdge = max(width, height)
        while (sourceEdge > 0 && sourceEdge / (sampleSize * 2) >= maxEdge) sampleSize *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val sourceEdge = max(bitmap.width, bitmap.height)
        if (sourceEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / sourceEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        bitmap.recycle()
        return scaled
    }
}
