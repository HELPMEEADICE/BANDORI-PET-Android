package com.bandori.pet.wallpaper

import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.bandori.pet.RenderSettings
import com.bandori.pet.isWallpaperEnabled
import com.bandori.pet.loadPersistedModelChoice
import com.bandori.pet.loadWallpaperBackgroundUri
import com.bandori.pet.loadWallpaperTransform
import com.bandori.pet.live2d.AssetSync
import com.bandori.pet.live2d.NativeLive2D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

class Live2DWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = Live2DEngine()

    private inner class Live2DEngine : Engine(), SurfaceHolder.Callback {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var handle = 0L
        private var runtimeRoot: String? = null
        private var surfaceReady = false
        private var visible = false
        private var loading = false
        private var width = 1
        private var height = 1
        private var loadGeneration = 0
        private var surfaceHolderRef: SurfaceHolder? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolderRef = surfaceHolder
            setTouchEventsEnabled(true)
            surfaceHolder.addCallback(this)
        }

        override fun onDestroy() {
            surfaceHolderRef?.removeCallback(this)
            stopRenderer()
            scope.cancel()
            super.onDestroy()
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            ensureRenderer()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            if (handle != 0L) NativeLive2D.resize(handle, this.width, this.height)
            ensureRenderer()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            stopRenderer()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                ensureRenderer()
            } else {
                stopRenderer()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && visible && handle != 0L) {
                val xRatio = event.x / max(width.toFloat(), 1f)
                val yRatio = event.y / max(height.toFloat(), 1f)
                val clampedX = xRatio.coerceIn(0f, 1f)
                val clampedY = yRatio.coerceIn(0f, 1f)
                NativeLive2D.touch(handle, clampedX, clampedY)
                if (RenderSettings.load(applicationContext).gazeFollowEnabled) {
                    NativeLive2D.lookAt(handle, clampedX, clampedY)
                }
            }
        }

        private fun ensureRenderer() {
            if (!visible || !surfaceReady || loading) return
            if (!isWallpaperEnabled(applicationContext)) {
                stopRenderer()
                return
            }
            val surface = surfaceHolderRef?.surface ?: return
            if (!surface.isValid) return
            val model = runCatching { loadPersistedModelChoice(applicationContext) }
                .onFailure { Log.e("BandoriPet", "Failed to load wallpaper model", it) }
                .getOrNull() ?: return
            val settings = RenderSettings.load(applicationContext)
            val wallpaperBackgroundUri = loadWallpaperBackgroundUri(applicationContext)
            val generation = ++loadGeneration
            loading = true
            scope.launch {
                val prepared = runCatching { AssetSync.prepareModel(applicationContext, model.modelAssetPath) }
                    .onFailure { Log.e("BandoriPet", "Failed to prepare wallpaper model", it) }
                    .getOrNull()
                if (prepared != null) {
                    if (generation == loadGeneration && visible && surfaceReady) {
                        val background = NativeLive2D.loadBackground(applicationContext, wallpaperBackgroundUri)
                        val activeSurface = surfaceHolderRef?.surface
                        if (generation != loadGeneration || !visible || !surfaceReady || activeSurface?.isValid != true) {
                            loading = false
                            return@launch
                        }
                        if (handle == 0L || runtimeRoot != prepared.runtimeRoot) {
                            destroyHandle()
                            runtimeRoot = prepared.runtimeRoot
                            handle = NativeLive2D.create(
                                activeSurface,
                                prepared.runtimeRoot,
                                width,
                                height,
                                settings.fpsLimit,
                                settings.vsyncEnabled,
                            )
                            NativeLive2D.setBackground(handle, background)
                            applyWallpaperTransform()
                        } else {
                            NativeLive2D.setRenderOptions(handle, settings.fpsLimit, settings.vsyncEnabled)
                            NativeLive2D.setBackground(handle, background)
                        }
                        if (handle != 0L) {
                            NativeLive2D.loadModel(
                                handle,
                                prepared.modelPath,
                                prepared.resourcePaths.toTypedArray(),
                                prepared.resourceBytes.toTypedArray(),
                            )
                        }
                    }
                }
                loading = false
            }
        }

        private fun applyWallpaperTransform() {
            if (handle == 0L) return
            val transform = loadWallpaperTransform(applicationContext)
            NativeLive2D.setTransform(handle, transform.offsetX, transform.offsetY, transform.scale)
        }

        private fun stopRenderer() {
            loadGeneration++
            loading = false
            destroyHandle()
        }

        private fun destroyHandle() {
            if (handle != 0L) {
                NativeLive2D.destroy(handle)
                handle = 0L
                runtimeRoot = null
            }
        }
    }
}
