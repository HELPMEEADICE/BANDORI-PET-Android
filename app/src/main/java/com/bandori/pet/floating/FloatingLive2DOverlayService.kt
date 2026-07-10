package com.bandori.pet.floating

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import com.bandori.pet.FloatingLive2DItem
import com.bandori.pet.FloatingOverlaySettings
import com.bandori.pet.RenderSettings
import com.bandori.pet.live2d.Live2DRenderView
import com.bandori.pet.saveFloatingLive2DItemBounds
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class FloatingLive2DOverlayService : Service() {
    private val windows = linkedMapOf<String, FloatingWindow>()
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncWindows()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clearWindows()
        super.onDestroy()
    }

    private fun syncWindows() {
        val settings = FloatingOverlaySettings.load(applicationContext)
        if (!settings.enabled || settings.items.isEmpty() || !canDrawOverlays(applicationContext)) {
            clearWindows()
            stopSelf()
            return
        }

        val renderSettings = RenderSettings.load(applicationContext)
        val activeIds = settings.items.map { it.id }.toSet()
        windows.keys.filterNot { it in activeIds }.forEach(::removeWindow)

        settings.items.forEach { item ->
            val existing = windows[item.id]
            if (existing != null && existing.canReuseFor(item)) {
                existing.update(
                    item = item,
                    locked = settings.locked,
                    touchThrough = settings.touchThrough,
                    fpsLimit = renderSettings.fpsLimit,
                    fpsDisplayEnabled = renderSettings.fpsDisplayEnabled,
                    vsyncEnabled = renderSettings.vsyncEnabled,
                )
            } else {
                if (existing != null) removeWindow(item.id)
                val window = FloatingWindow(
                    context = this,
                    item = item,
                    locked = settings.locked,
                    touchThrough = settings.touchThrough,
                    fpsLimit = renderSettings.fpsLimit,
                    fpsDisplayEnabled = renderSettings.fpsDisplayEnabled,
                    vsyncEnabled = renderSettings.vsyncEnabled,
                    onBoundsChanged = { x, y, width, height ->
                        saveFloatingLive2DItemBounds(applicationContext, item.id, x, y, width, height)
                    },
                )
                windowManager.addView(window.root, window.params)
                windows[item.id] = window
            }
        }
    }

    private fun clearWindows() {
        windows.keys.toList().forEach(::removeWindow)
        windows.clear()
    }

    private fun removeWindow(id: String) {
        val window = windows.remove(id) ?: return
        runCatching { windowManager.removeView(window.root) }
        window.release()
    }

    private class FloatingWindow(
        context: Context,
        item: FloatingLive2DItem,
        locked: Boolean,
        touchThrough: Boolean,
        fpsLimit: Int,
        fpsDisplayEnabled: Boolean,
        vsyncEnabled: Boolean,
        onBoundsChanged: (Int, Int, Int, Int) -> Unit,
    ) {
        private val modelAssetPath = item.model.modelAssetPath
        private val renderView = Live2DRenderView(context).apply {
            setInteractionLocked(true)
            setRenderOptions(fpsLimit, vsyncEnabled)
            setFpsDisplayEnabled(fpsDisplayEnabled)
            setModel(item.model)
        }

        val params = WindowManager.LayoutParams(
            item.width.coerceIn(MIN_WIDTH, MAX_WIDTH),
            item.height.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            overlayWindowType(),
            overlayWindowFlags(touchThrough),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = overlayWindowAlpha(touchThrough)
            x = item.x
            y = item.y
        }

        val root = DraggableOverlayLayout(
            context = context,
            params = params,
            locked = locked,
            onBoundsChanged = onBoundsChanged,
        ).apply {
            addView(
                renderView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        fun canReuseFor(item: FloatingLive2DItem): Boolean = item.model.modelAssetPath == modelAssetPath

        fun update(
            item: FloatingLive2DItem,
            locked: Boolean,
            touchThrough: Boolean,
            fpsLimit: Int,
            fpsDisplayEnabled: Boolean,
            vsyncEnabled: Boolean,
        ) {
            root.setLocked(locked)
            renderView.setRenderOptions(fpsLimit, vsyncEnabled)
            renderView.setFpsDisplayEnabled(fpsDisplayEnabled)
            val nextWidth = item.width.coerceIn(MIN_WIDTH, MAX_WIDTH)
            val nextHeight = item.height.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
            val nextFlags = overlayWindowFlags(touchThrough)
            val nextAlpha = overlayWindowAlpha(touchThrough)
            if (
                params.x != item.x ||
                params.y != item.y ||
                params.width != nextWidth ||
                params.height != nextHeight ||
                params.flags != nextFlags ||
                params.alpha != nextAlpha
            ) {
                params.x = item.x
                params.y = item.y
                params.width = nextWidth
                params.height = nextHeight
                params.flags = nextFlags
                params.alpha = nextAlpha
                root.updateWindow()
            }
        }

        fun release() {
            renderView.release()
        }
    }

    private class DraggableOverlayLayout(
        context: Context,
        private val params: WindowManager.LayoutParams,
        locked: Boolean,
        private val onBoundsChanged: (Int, Int, Int, Int) -> Unit,
    ) : FrameLayout(context) {
        private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var locked = locked
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var startWidth = 0
        private var startHeight = 0
        private var startSpan = 0f
        private var dragging = false
        private var resizing = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (locked) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginDrag(event)
                    dragging = false
                    resizing = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        beginResize(event)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizing) return true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (locked) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) beginResize(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizing && event.pointerCount >= 2) {
                        resize(event)
                    } else {
                        drag(event)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount - 1 < 2) {
                        resizing = false
                        beginDrag(event)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging || resizing) saveBounds()
                    dragging = false
                    resizing = false
                }
            }
            return true
        }

        private fun beginDrag(event: MotionEvent) {
            downRawX = event.rawX
            downRawY = event.rawY
            startX = params.x
            startY = params.y
        }

        private fun beginResize(event: MotionEvent) {
            resizing = true
            dragging = true
            startSpan = pointerSpan(event).coerceAtLeast(1f)
            startWidth = params.width
            startHeight = params.height
        }

        private fun drag(event: MotionEvent) {
            dragging = true
            params.x = startX + (event.rawX - downRawX).roundToInt()
            params.y = startY + (event.rawY - downRawY).roundToInt()
            updateWindow()
        }

        private fun resize(event: MotionEvent) {
            val scale = pointerSpan(event) / max(startSpan, 1f)
            params.width = (startWidth * scale).roundToInt().coerceIn(MIN_WIDTH, MAX_WIDTH)
            params.height = (startHeight * scale).roundToInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
            updateWindow()
        }

        fun setLocked(locked: Boolean) {
            this.locked = locked
            if (locked) {
                if (dragging || resizing) saveBounds()
                dragging = false
                resizing = false
            }
        }

        fun updateWindow() {
            runCatching { windowManager.updateViewLayout(this, params) }
        }

        private fun saveBounds() {
            onBoundsChanged(params.x, params.y, params.width, params.height)
        }

        private fun pointerSpan(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
        }
    }

    companion object {
        private const val MIN_WIDTH = 180
        private const val MIN_HEIGHT = 240
        private const val MAX_WIDTH = 1200
        private const val MAX_HEIGHT = 1600

        fun sync(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, FloatingLive2DOverlayService::class.java)
            if (canDrawOverlays(appContext)) {
                appContext.startService(intent)
            } else {
                appContext.stopService(intent)
            }
        }

        fun permissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        @Suppress("DEPRECATION")
        private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        private fun overlayWindowFlags(touchThrough: Boolean): Int {
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (touchThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            return flags
        }

        private fun overlayWindowAlpha(touchThrough: Boolean): Float =
            if (touchThrough && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.8f else 1f
    }
}
