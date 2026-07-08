package com.bandori.pet.live2d

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.bandori.pet.data.ModelChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class Live2DRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handle = 0L
    private var runtimeRoot: String? = null
    private var selectedModel: ModelChoice? = null
    private var loading = false

    var statusChanged: ((String?) -> Unit)? = null

    init {
        holder.addCallback(this)
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && handle != 0L) {
                NativeLive2D.touch(handle, event.x, event.y)
                performClick()
                true
            } else {
                true
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setModel(model: ModelChoice?) {
        if (selectedModel == model && handle != 0L) return
        selectedModel = model
        loadSelectedModel()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        loadSelectedModel()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (handle != 0L) NativeLive2D.resize(handle, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (handle != 0L) {
            NativeLive2D.destroy(handle)
            handle = 0L
        }
    }

    fun release() {
        holder.removeCallback(this)
        if (handle != 0L) NativeLive2D.destroy(handle)
        handle = 0L
        scope.cancel()
    }

    private fun loadSelectedModel() {
        val model = selectedModel ?: return
        if (!holder.surface.isValid || loading) return

        loading = true
        statusChanged?.invoke("正在准备 Live2D 资源...")
        scope.launch {
            runCatching { AssetSync.prepareModel(context.applicationContext, model.modelAssetPath) }
                .onSuccess { prepared ->
                    if (handle == 0L || runtimeRoot != prepared.runtimeRoot) {
                        if (handle != 0L) NativeLive2D.destroy(handle)
                        runtimeRoot = prepared.runtimeRoot
                        handle = NativeLive2D.create(holder.surface, prepared.runtimeRoot, width.coerceAtLeast(1), height.coerceAtLeast(1))
                    }
                    val accepted = handle != 0L && NativeLive2D.loadModel(handle, prepared.modelPath)
                    val nativeError = if (handle != 0L) NativeLive2D.lastError(handle) else "渲染核心启动失败"
                    statusChanged?.invoke(
                        when {
                            nativeError.isNotBlank() -> nativeError
                            accepted -> null
                            else -> "模型加载失败"
                        },
                    )
                }
                .onFailure { statusChanged?.invoke(it.message ?: "资源准备失败") }
            loading = false
        }
    }
}
