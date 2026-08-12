package com.bandori.pet.ui.live2d

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bandori.pet.I18n
import com.bandori.pet.RenderSettings
import com.bandori.pet.Live2DControlIcon
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.live2d.Live2DRenderView
import com.bandori.pet.llm.Live2DChatViewModel
import com.bandori.pet.llm.ChatBackendMode
import com.bandori.pet.ui.ImageBitmapCache
import com.bandori.pet.ui.SampledImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@Composable
fun Live2DScreen(
    selectedModel: ModelChoice?,
    renderSettings: RenderSettings,
    fullScreen: Boolean,
    onFullScreenChanged: (Boolean) -> Unit,
    onRemoteCharacterSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var status by remember(selectedModel) { mutableStateOf<String?>(null) }
    var locked by remember(selectedModel) { mutableStateOf(true) }
    var controlsVisible by remember(selectedModel) { mutableStateOf(true) }
    var controlPulse by remember(selectedModel) { mutableStateOf(0) }
    var chatExpanded by remember(selectedModel) { mutableStateOf(false) }
    val chatViewModel: Live2DChatViewModel = viewModel()
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(chatState.backendMode, chatState.characterId) {
        if (chatState.backendMode == ChatBackendMode.Desktop) {
            chatState.characterId?.let(onRemoteCharacterSelected)
        }
    }

    fun revealControls() {
        controlsVisible = true
        controlPulse += 1
    }

    LaunchedEffect(controlsVisible, controlPulse) {
        if (controlsVisible) {
            delay(10_000)
            controlsVisible = false
        }
    }

    if (fullScreen) {
        Live2DStage(
            selectedModel = selectedModel,
            renderSettings = renderSettings,
            status = status,
            locked = locked,
            controlsVisible = controlsVisible,
            fullScreen = true,
            chatExpanded = chatExpanded,
            chatViewModel = chatViewModel,
            cornerRadius = 0.dp,
            onStatusChanged = { status = it },
            onInteraction = { revealControls() },
            onLockedChange = {
                locked = it
                revealControls()
            },
            onFullScreenChanged = {
                onFullScreenChanged(it)
                revealControls()
            },
            onChatExpandedChange = { chatExpanded = it },
            modifier = modifier,
        )
    } else {
        ElevatedCard(
            modifier = modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Live2DStage(
                selectedModel = selectedModel,
                renderSettings = renderSettings,
                status = status,
                locked = locked,
                controlsVisible = controlsVisible,
                fullScreen = false,
                chatExpanded = chatExpanded,
                chatViewModel = chatViewModel,
                cornerRadius = 24.dp,
                onStatusChanged = { status = it },
                onInteraction = { revealControls() },
                onLockedChange = {
                    locked = it
                    revealControls()
                },
                onFullScreenChanged = {
                    onFullScreenChanged(it)
                    revealControls()
                },
                onChatExpandedChange = { chatExpanded = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            )
        }
    }
}

@Composable
fun Live2DStage(
    selectedModel: ModelChoice?,
    renderSettings: RenderSettings,
    status: String?,
    locked: Boolean,
    controlsVisible: Boolean,
    fullScreen: Boolean,
    chatExpanded: Boolean,
    chatViewModel: Live2DChatViewModel,
    cornerRadius: Dp,
    onStatusChanged: (String?) -> Unit,
    onInteraction: () -> Unit,
    onLockedChange: (Boolean) -> Unit,
    onFullScreenChanged: (Boolean) -> Unit,
    onChatExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surface
    val stageBackground = remember(primaryContainer, surface) {
        Brush.verticalGradient(colors = listOf(primaryContainer, surface))
    }
    LaunchedEffect(selectedModel?.characterId) {
        selectedModel?.let(chatViewModel::selectCharacter)
        if (selectedModel == null) onChatExpandedChange(false)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(stageBackground)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        ContentUriImage(
            uri = renderSettings.backgroundUri,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selectedModel == null) {
            EmptyMessage(I18n.t("empty_no_model_title"), I18n.t("empty_no_model_body"))
        } else {
            Live2DRenderer(
                selectedModel = selectedModel,
                renderSettings = renderSettings,
                locked = locked,
                chatExpanded = chatExpanded,
                chatViewModel = chatViewModel,
                onStatusChanged = onStatusChanged,
                onInteraction = onInteraction,
                modifier = Modifier.fillMaxSize(),
            )
        }
        status?.let {
            Surface(
                modifier = Modifier
                    .align(if (chatExpanded) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(
                        start = 18.dp,
                        top = if (chatExpanded) 18.dp else 0.dp,
                        end = 18.dp,
                        bottom = if (chatExpanded) 0.dp else 82.dp,
                    ),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 6.dp,
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        AnimatedVisibility(
            visible = controlsVisible && selectedModel != null && !chatExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Live2DControlButton(
                    icon = if (locked) Live2DControlIcon.Lock else Live2DControlIcon.Unlock,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(18.dp),
                    onClick = { onLockedChange(!locked) },
                )
                Live2DControlButton(
                    icon = if (fullScreen) Live2DControlIcon.ExitFullScreen else Live2DControlIcon.FullScreen,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(18.dp),
                    onClick = { onFullScreenChanged(!fullScreen) },
                )
            }
        }
        selectedModel?.let { model ->
            Live2DChatOverlay(
                model = model,
                viewModel = chatViewModel,
                expanded = chatExpanded,
                launcherVisible = controlsVisible,
                onExpandedChange = {
                    onChatExpandedChange(it)
                    onInteraction()
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun Live2DRenderer(
    selectedModel: ModelChoice,
    renderSettings: RenderSettings,
    locked: Boolean,
    chatExpanded: Boolean,
    chatViewModel: Live2DChatViewModel,
    onStatusChanged: (String?) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renderView by remember(selectedModel) { mutableStateOf<Live2DRenderView?>(null) }
    val presentationScale by animateFloatAsState(
        targetValue = if (chatExpanded) 0.72f else 1f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "chatPresentationScale",
    )
    val presentationOffsetY by animateFloatAsState(
        targetValue = if (chatExpanded) 0.36f else 0f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "chatPresentationOffsetY",
    )

    LaunchedEffect(renderView, chatViewModel) {
        chatViewModel.actions.collect { action -> renderView?.playAction(action) }
    }
    LaunchedEffect(renderView, chatViewModel) {
        chatViewModel.mouth.collect { (open, form) -> renderView?.setLipSync(open, form) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, renderView) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> renderView?.setRenderingActive(true)
                Lifecycle.Event.ON_STOP -> renderView?.setRenderingActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        renderView?.setRenderingActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            Live2DRenderView(context).apply {
                renderView = this
                statusChanged = onStatusChanged
                interactionChanged = onInteraction
                setInteractionLocked(locked)
                setRenderOptions(renderSettings.fpsLimit, renderSettings.vsyncEnabled)
                setRenderResolution(renderSettings.renderResolution)
                setFpsDisplayEnabled(renderSettings.fpsDisplayEnabled)
                setGazeFollowEnabled(renderSettings.gazeFollowEnabled)
                setPresentationTransform(presentationScale, presentationOffsetY)
                setModel(selectedModel)
            }
        },
        update = { view ->
            view.statusChanged = onStatusChanged
            view.interactionChanged = onInteraction
            view.setInteractionLocked(locked)
            view.setRenderOptions(renderSettings.fpsLimit, renderSettings.vsyncEnabled)
            view.setRenderResolution(renderSettings.renderResolution)
            view.setFpsDisplayEnabled(renderSettings.fpsDisplayEnabled)
            view.setGazeFollowEnabled(renderSettings.gazeFollowEnabled)
            view.setPresentationTransform(presentationScale, presentationOffsetY)
            view.setModel(selectedModel)
            renderView = view
        },
        onReset = null,
        onRelease = { view ->
            if (renderView === view) renderView = null
            view.release()
        },
    )
}

@Composable
fun Live2DControlButton(
    icon: Live2DControlIcon,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val iconColor = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Canvas(
            modifier = Modifier
                .padding(12.dp)
                .size(26.dp),
        ) {
            val strokeWidth = 2.4.dp.toPx()
            val stroke = Stroke(width = strokeWidth)
            val w = size.width
            val h = size.height
            when (icon) {
                Live2DControlIcon.Lock, Live2DControlIcon.Unlock -> {
                    val bodyTop = h * 0.45f
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(w * 0.24f, bodyTop),
                        size = Size(w * 0.52f, h * 0.38f),
                        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                        style = stroke,
                    )
                    val shackleLeft = if (icon == Live2DControlIcon.Lock) w * 0.34f else w * 0.46f
                    drawArc(
                        color = iconColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(shackleLeft, h * 0.18f),
                        size = Size(w * 0.32f, h * 0.48f),
                        style = stroke,
                    )
                }
                Live2DControlIcon.FullScreen -> {
                    drawLine(iconColor, Offset(w * 0.15f, h * 0.4f), Offset(w * 0.15f, h * 0.15f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.4f, h * 0.15f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.6f, h * 0.15f), Offset(w * 0.85f, h * 0.15f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.85f, h * 0.4f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.85f, h * 0.6f), Offset(w * 0.85f, h * 0.85f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.85f, h * 0.85f), Offset(w * 0.6f, h * 0.85f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.4f, h * 0.85f), Offset(w * 0.15f, h * 0.85f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.15f, h * 0.6f), strokeWidth)
                }
                Live2DControlIcon.ExitFullScreen -> {
                    drawLine(iconColor, Offset(w * 0.15f, h * 0.4f), Offset(w * 0.4f, h * 0.4f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.4f, h * 0.4f), Offset(w * 0.4f, h * 0.15f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.6f, h * 0.15f), Offset(w * 0.6f, h * 0.4f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.6f, h * 0.4f), Offset(w * 0.85f, h * 0.4f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.85f, h * 0.6f), Offset(w * 0.6f, h * 0.6f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.6f, h * 0.6f), Offset(w * 0.6f, h * 0.85f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.4f, h * 0.85f), Offset(w * 0.4f, h * 0.6f), strokeWidth)
                    drawLine(iconColor, Offset(w * 0.4f, h * 0.6f), Offset(w * 0.15f, h * 0.6f), strokeWidth)
                }
            }
        }
    }
}

@Composable
fun ContentUriImage(uri: String?, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val cacheKey = uri?.let { "content:$BACKGROUND_IMAGE_MAX_EDGE:$it" }
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let(ImageBitmapCache::get)) }
    LaunchedEffect(cacheKey) {
        val key = cacheKey ?: return@LaunchedEffect
        if (bitmap != null || ImageBitmapCache.isKnownMissing(key)) return@LaunchedEffect
        val decoded = uri?.let {
            withContext(Dispatchers.IO) {
                SampledImageDecoder.decodeContentUri(appContext, Uri.parse(it), BACKGROUND_IMAGE_MAX_EDGE)
            }
        }
        if (decoded == null) ImageBitmapCache.markMissing(key) else ImageBitmapCache.put(key, decoded)
        bitmap = decoded
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap = bitmap!!, contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}

private const val BACKGROUND_IMAGE_MAX_EDGE = 2048

@Composable
fun EmptyMessage(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
