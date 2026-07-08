package com.bandori.pet.live2d

import android.view.Surface

object NativeLive2D {
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
    external fun loadModel(handle: Long, modelPath: String): Boolean
    external fun setRenderOptions(handle: Long, fpsLimit: Int, vsyncEnabled: Boolean)
    external fun setTransform(handle: Long, offsetX: Float, offsetY: Float, scale: Float)
    external fun touch(handle: Long, x: Float, y: Float)
    external fun lastError(handle: Long): String
    external fun destroy(handle: Long)
}
