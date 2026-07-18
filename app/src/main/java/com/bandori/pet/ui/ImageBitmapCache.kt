package com.bandori.pet.ui

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap

internal object ImageBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    operator fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }

    private fun cacheSizeKb(): Int =
        (Runtime.getRuntime().maxMemory() / 1024L / 16L)
            .coerceIn(8L * 1024L, 32L * 1024L)
            .toInt()
}
