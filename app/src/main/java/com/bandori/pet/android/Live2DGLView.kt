package com.bandori.pet.android

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Live2DGLView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private var runtimeRoot: String? = null
    private var pendingModelPath: String? = null
    private var surfaceReady = false

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 8)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun configure(runtimeRoot: String, modelPath: String?) {
        this.runtimeRoot = runtimeRoot
        pendingModelPath = modelPath
        queueEvent {
            if (surfaceReady) {
                NativeLive2D.initLive2D(runtimeRoot)
                modelPath?.let { NativeLive2D.loadModel(it) }
            }
        }
    }

    fun loadModel(path: String) {
        pendingModelPath = path
        queueEvent {
            if (surfaceReady) {
                NativeLive2D.loadModel(path)
            }
        }
    }

    fun triggerMotion(name: String) {
        queueEvent { NativeLive2D.triggerMotion(name) }
    }

    fun setLipSync(value: Float) {
        queueEvent { NativeLive2D.setLipSync(value.coerceIn(0f, 1f)) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        surfaceReady = true
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        NativeLive2D.onSurfaceCreated()
        runtimeRoot?.let { NativeLive2D.initLive2D(it) }
        pendingModelPath?.let { NativeLive2D.loadModel(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        NativeLive2D.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        NativeLive2D.renderFrame()
    }
}
