package com.bandori.pet.android

object NativeLive2D {
    val available: Boolean
    private var lastLoadError: String? = null

    init {
        var ok = false
        try {
            System.loadLibrary("live2d_jni")
            ok = true
        } catch (error: UnsatisfiedLinkError) {
            lastLoadError = error.message
        }
        available = ok
    }

    fun loadError(): String? = lastLoadError

    fun initLive2D(runtimeRoot: String): Boolean = available && nativeInitLive2D(runtimeRoot)
    fun onSurfaceCreated(): Boolean = available && nativeOnSurfaceCreated()
    fun onSurfaceChanged(width: Int, height: Int): Boolean = available && nativeOnSurfaceChanged(width, height)
    fun loadModel(path: String): Boolean = available && nativeLoadModel(path)
    fun renderFrame(): Boolean = available && nativeRenderFrame()
    fun triggerMotion(name: String): Boolean = available && nativeTriggerMotion(name)
    fun setLipSync(value: Float): Boolean = available && nativeSetLipSync(value)
    fun dispose(): Boolean = available && nativeDispose()
    fun lastError(): String = if (available) nativeLastError() else (lastLoadError ?: "native library unavailable")

    @JvmStatic private external fun nativeInitLive2D(runtimeRoot: String): Boolean
    @JvmStatic private external fun nativeOnSurfaceCreated(): Boolean
    @JvmStatic private external fun nativeOnSurfaceChanged(width: Int, height: Int): Boolean
    @JvmStatic private external fun nativeLoadModel(path: String): Boolean
    @JvmStatic private external fun nativeRenderFrame(): Boolean
    @JvmStatic private external fun nativeTriggerMotion(name: String): Boolean
    @JvmStatic private external fun nativeSetLipSync(value: Float): Boolean
    @JvmStatic private external fun nativeDispose(): Boolean
    @JvmStatic private external fun nativeLastError(): String
}
