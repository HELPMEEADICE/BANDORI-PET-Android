package com.bandori.pet.live2d

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.bandori.pet.I18n
import com.bandori.pet.RenderResolution
import com.bandori.pet.data.ModelChoice
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class Live2DTransform(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
)

class Live2DRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handle = 0L
    private var renderSurface: Surface? = null
    private var runtimeRoot: String? = null
    private var selectedModel: ModelChoice? = null
    private var loading = false
    private var loadGeneration = 0
    private var interactionLocked = true
    private var fpsLimit = 60
    private var vsyncEnabled = true
    private var renderResolution = RenderResolution.PointToPoint
    private var fpsDisplayEnabled = false
    private var gazeFollowEnabled = false
    private var offsetX = 0f
    private var offsetY = 0f
    private var modelScale = 1f
    private var presentationScale = 1f
    private var presentationOffsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var pinching = false
    private var moved = false
    private var pendingAction: String? = null

    var statusChanged: ((String?) -> Unit)? = null
    var interactionChanged: (() -> Unit)? = null
    var transformChanged: ((Live2DTransform) -> Unit)? = null

    init {
        setOpaque(false)
        surfaceTextureListener = this
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setModel(model: ModelChoice?) {
        if (selectedModel == model && handle != 0L && !loading) return
        selectedModel = model
        loadGeneration += 1
        loadSelectedModel()
    }

    fun setInteractionLocked(locked: Boolean) {
        interactionLocked = locked
    }

    fun setTransform(transform: Live2DTransform) {
        offsetX = transform.offsetX
        offsetY = transform.offsetY
        modelScale = transform.scale.coerceIn(0.4f, 3f)
        applyTransform()
    }

    fun currentTransform(): Live2DTransform = Live2DTransform(offsetX, offsetY, modelScale)

    fun setPresentationScale(scale: Float) {
        val next = scale.coerceIn(0.4f, 1f)
        if (presentationScale == next) return
        presentationScale = next
        applyTransform()
    }

    fun setPresentationOffsetY(offsetY: Float) {
        val next = offsetY.coerceIn(-1f, 1f)
        if (presentationOffsetY == next) return
        presentationOffsetY = next
        applyTransform()
    }

    fun playAction(tag: String) {
        if (tag.isBlank()) return
        pendingAction = tag
        dispatchPendingAction()
    }

    fun setRenderOptions(fpsLimit: Int, vsyncEnabled: Boolean) {
        val nextFpsLimit = fpsLimit.coerceIn(15, 120)
        if (this.fpsLimit == nextFpsLimit && this.vsyncEnabled == vsyncEnabled) return
        this.fpsLimit = nextFpsLimit
        this.vsyncEnabled = vsyncEnabled
        if (handle != 0L) NativeLive2D.setRenderOptions(handle, nextFpsLimit, vsyncEnabled)
    }

    fun setRenderResolution(resolution: RenderResolution) {
        if (renderResolution == resolution) return
        renderResolution = resolution
        if (handle != 0L) NativeLive2D.setRenderScale(handle, resolution.scale)
    }

    fun setFpsDisplayEnabled(enabled: Boolean) {
        if (fpsDisplayEnabled == enabled) return
        fpsDisplayEnabled = enabled
        if (handle != 0L) NativeLive2D.setFpsDisplayEnabled(handle, enabled)
    }

    fun setGazeFollowEnabled(enabled: Boolean) {
        gazeFollowEnabled = enabled
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderSurface?.release()
        renderSurface = Surface(surface)
        loadSelectedModel()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (handle != 0L) NativeLive2D.resize(handle, width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        destroyRenderer()
        renderSurface?.release()
        renderSurface = null
        return true
    }

    fun release() {
        surfaceTextureListener = null
        destroyRenderer()
        renderSurface?.release()
        renderSurface = null
        scope.cancel()
    }

    private fun destroyRenderer() {
        if (handle != 0L) {
            NativeLive2D.destroy(handle)
            handle = 0L
        }
    }

    private fun loadSelectedModel() {
        val model = selectedModel ?: return
        val surface = renderSurface ?: return
        if (!surface.isValid || loading) return

        loading = true
        val generation = loadGeneration
        statusChanged?.invoke(I18n.t("status_preparing"))
        scope.launch {
            val result = runCatching { AssetSync.prepareModel(context.applicationContext, model.modelAssetPath) }
            if (selectedModel == model && generation == loadGeneration && renderSurface == surface && surface.isValid) {
                result.onSuccess { prepared ->
                    if (handle == 0L || runtimeRoot != prepared.runtimeRoot) {
                        if (handle != 0L) NativeLive2D.destroy(handle)
                        runtimeRoot = prepared.runtimeRoot
                        handle = NativeLive2D.create(
                            surface,
                            prepared.runtimeRoot,
                            width.coerceAtLeast(1),
                            height.coerceAtLeast(1),
                            fpsLimit,
                            vsyncEnabled,
                            renderResolution.scale,
                        )
                        NativeLive2D.setFpsDisplayEnabled(handle, fpsDisplayEnabled)
                        applyTransform()
                    }
                    val accepted = handle != 0L && NativeLive2D.loadModel(
                        handle,
                        prepared.modelPath,
                        prepared.resourcePaths.toTypedArray(),
                        prepared.resourceBytes.toTypedArray(),
                    )
                    val nativeError = if (handle != 0L) NativeLive2D.lastError(handle) else I18n.t("status_core_failed")
                    statusChanged?.invoke(
                        when {
                            nativeError.isNotBlank() -> nativeError
                            accepted -> null
                            else -> I18n.t("status_model_load_failed")
                        },
                    )
                    if (accepted) dispatchPendingAction()
                }
                    .onFailure { statusChanged?.invoke(it.message ?: I18n.t("status_resource_failed")) }
            }
            loading = false
            if (selectedModel != model || generation != loadGeneration) loadSelectedModel()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        interactionChanged?.invoke()
        if (interactionLocked) {
            if (event.actionMasked == MotionEvent.ACTION_UP && handle != 0L) {
                sendTouch(event.x, event.y)
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
                            transformChanged?.invoke(currentTransform())
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
                        transformChanged?.invoke(currentTransform())
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
                    sendTouch(event.x, event.y)
                    performClick()
                }
                pinching = false
            }
            MotionEvent.ACTION_CANCEL -> pinching = false
        }
        return true
    }

    private fun sendTouch(x: Float, y: Float) {
        val xRatio = x / max(width.toFloat(), 1f)
        val yRatio = y / max(height.toFloat(), 1f)
        val clampedX = xRatio.coerceIn(0f, 1f)
        val clampedY = yRatio.coerceIn(0f, 1f)
        NativeLive2D.touch(handle, clampedX, clampedY)
        if (gazeFollowEnabled) NativeLive2D.lookAt(handle, clampedX, clampedY)
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun applyTransform() {
        if (handle != 0L) {
            NativeLive2D.setTransform(handle, offsetX, offsetY + presentationOffsetY, modelScale * presentationScale)
        }
    }

    private fun dispatchPendingAction() {
        val action = pendingAction ?: return
        if (handle == 0L) return
        pendingAction = null
        NativeLive2D.playAction(handle, action)
    }
}
