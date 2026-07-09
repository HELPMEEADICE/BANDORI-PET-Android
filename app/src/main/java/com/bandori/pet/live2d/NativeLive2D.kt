package com.bandori.pet.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Surface
import kotlin.math.max

object NativeLive2D {
    private const val MAX_BACKGROUND_EDGE = 2048

    init {
        System.loadLibrary("bandoripet")
    }

    external fun create(
        surface: Surface,
        runtimeRoot: String,
        width: Int,
        height: Int,
        fpsLimit: Int,
        vsyncEnabled: Boolean,
    ): Long
    external fun resize(handle: Long, width: Int, height: Int)
    external fun loadModel(handle: Long, modelPath: String, resourcePaths: Array<String>, resourceBytes: Array<ByteArray>): Boolean
    external fun setRenderOptions(handle: Long, fpsLimit: Int, vsyncEnabled: Boolean)
    external fun setTransform(handle: Long, offsetX: Float, offsetY: Float, scale: Float)
    external fun setBackgroundPixels(handle: Long, pixels: IntArray?, width: Int, height: Int)
    external fun touch(handle: Long, x: Float, y: Float)
    external fun lastError(handle: Long): String
    external fun destroy(handle: Long)

    fun setBackground(context: Context, handle: Long, uri: String?) {
        if (handle == 0L || uri.isNullOrBlank()) {
            setBackgroundPixels(handle, null, 0, 0)
            return
        }

        runCatching { decodeBackground(context, Uri.parse(uri)) }
            .onSuccess { bitmap ->
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                setBackgroundPixels(handle, pixels, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
            .onFailure {
                setBackgroundPixels(handle, null, 0, 0)
            }
    }

    private fun decodeBackground(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: error("无法读取背景图片")
        return scaleToMaxEdge(decoded)
    }

    private fun computeSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var edge = max(width, height)
        while (edge / sampleSize > MAX_BACKGROUND_EDGE) sampleSize *= 2
        return sampleSize
    }

    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= MAX_BACKGROUND_EDGE) return bitmap
        val scale = MAX_BACKGROUND_EDGE.toFloat() / edge.toFloat()
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
