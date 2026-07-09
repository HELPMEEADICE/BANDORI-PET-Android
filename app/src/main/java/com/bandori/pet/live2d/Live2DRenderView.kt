package com.bandori.pet.live2d

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.bandori.pet.data.ModelChoice
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
    private var interactionLocked = true
    private var fpsLimit = 60
    private var vsyncEnabled = true
    private var offsetX = 0f
    private var offsetY = 0f
    private var modelScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var pinching = false
    private var moved = false

    var statusChanged: ((String?) -> Unit)? = null
    var interactionChanged: (() -> Unit)? = null

    init {
        holder.addCallback(this)
        setOnTouchListener { _, event -> handleTouch(event) }
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

    fun setInteractionLocked(locked: Boolean) {
        interactionLocked = locked
    }

    fun setRenderOptions(fpsLimit: Int, vsyncEnabled: Boolean) {
        val nextFpsLimit = fpsLimit.coerceIn(15, 120)
        if (this.fpsLimit == nextFpsLimit && this.vsyncEnabled == vsyncEnabled) return
        this.fpsLimit = nextFpsLimit
        this.vsyncEnabled = vsyncEnabled
        if (handle != 0L) NativeLive2D.setRenderOptions(handle, nextFpsLimit, vsyncEnabled)
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
                        handle = NativeLive2D.create(
                            holder.surface,
                            prepared.runtimeRoot,
                            width.coerceAtLeast(1),
                            height.coerceAtLeast(1),
                            fpsLimit,
                            vsyncEnabled,
                        )
                        applyTransform()
                    }
                    val accepted = handle != 0L && NativeLive2D.loadModel(
                        handle,
                        prepared.modelPath,
                        prepared.resourcePaths.toTypedArray(),
                        prepared.resourceBytes.toTypedArray(),
                    )
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

    private fun handleTouch(event: MotionEvent): Boolean {
        interactionChanged?.invoke()
        if (interactionLocked) {
            if (event.actionMasked == MotionEvent.ACTION_UP && handle != 0L) {
                NativeLive2D.touch(handle, event.x, event.y)
                performClick()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                lastSpan = 0f
                pinching = false
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastSpan = pointerSpan(event)
                    pinching = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && event.pointerCount >= 2) {
                    val span = pointerSpan(event)
                    if (span > 0f && lastSpan > 0f) {
                        val nextScale = (modelScale * (span / lastSpan)).coerceIn(0.4f, 3f)
                        if (nextScale != modelScale) {
                            modelScale = nextScale
                            applyTransform()
                            moved = true
                        }
                    }
                    lastSpan = span
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (dx * dx + dy * dy > 4f) {
                        val base = max(1f, min(width.toFloat(), height.toFloat()))
                        offsetX += dx * 2f / base
                        offsetY -= dy * 2f / base
                        applyTransform()
                        moved = true
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) {
                    pinching = false
                    val keepIndex = if (event.actionIndex == 0) 1 else 0
                    if (keepIndex < event.pointerCount) {
                        lastX = event.getX(keepIndex)
                        lastY = event.getY(keepIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && handle != 0L) {
                    NativeLive2D.touch(handle, event.x, event.y)
                    performClick()
                }
                pinching = false
            }
            MotionEvent.ACTION_CANCEL -> pinching = false
        }
        return true
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun applyTransform() {
        if (handle != 0L) NativeLive2D.setTransform(handle, offsetX, offsetY, modelScale)
    }
}
