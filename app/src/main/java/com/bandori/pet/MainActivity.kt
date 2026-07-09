package com.bandori.pet

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bandori.pet.data.AppData
import com.bandori.pet.data.Band
import com.bandori.pet.data.CharacterInfo
import com.bandori.pet.data.DataRepository
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.data.ZstModelArchive
import com.bandori.pet.live2d.Live2DRenderView
import com.bandori.pet.ui.theme.BandoriPetTheme
import com.bandori.pet.wallpaper.Live2DWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        setContent {
            var themeSettings by remember { mutableStateOf(ThemeSettings.load(appContext)) }
            val darkTheme = themeSettings.darkMode.resolveDarkTheme(isSystemInDarkTheme())
            BandoriPetTheme(
                darkTheme = darkTheme,
                dynamicColor = themeSettings.dynamicColorEnabled,
            ) {
                BandoriPetApp(
                    themeSettings = themeSettings,
                    onThemeSettingsChanged = { settings ->
                        themeSettings = settings
                        settings.save(appContext)
                    },
                )
            }
        }
    }
}

private enum class Screen(val title: String) {
    Live2D("Live2D"),
    Model("模型"),
    Settings("设置"),
}

private enum class Live2DControlIcon {
    Lock,
    Unlock,
    FullScreen,
    ExitFullScreen,
}

private data class ModelTransferState(
    val characterId: String,
    val characterName: String,
    val actionLabel: String,
    val progress: ZstModelArchive.DownloadProgress? = null,
)

@Composable
private fun BandoriPetApp(
    themeSettings: ThemeSettings,
    onThemeSettingsChanged: (ThemeSettings) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var appData by remember { mutableStateOf<AppData?>(null) }
    var selectedScreen by remember { mutableStateOf(Screen.Live2D) }
    var selectedBandId by remember { mutableStateOf<String?>(null) }
    var selectedCharacterId by remember { mutableStateOf(loadSelectedCharacterId(appContext)) }
    var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }
    var preferredModelAssetPath by remember { mutableStateOf(loadSelectedModelAssetPath(appContext)) }
    var live2DFullScreen by remember { mutableStateOf(false) }
    var modelAssetsVersion by remember { mutableStateOf(0) }
    var renderSettings by remember { mutableStateOf(RenderSettings.load(appContext)) }
    val updateRenderSettings: (RenderSettings) -> Unit = { settings ->
        renderSettings = settings
        settings.save(appContext)
    }
    val selectCharacterModel: (String, ModelChoice?) -> Unit = { characterId, model ->
        selectedCharacterId = characterId
        selectedModel = model
        preferredModelAssetPath = model?.modelAssetPath
        saveModelSelection(appContext, characterId, model)
    }

    PredictiveBackHandler(enabled = live2DFullScreen) { progress ->
        try {
            progress.collect { }
            live2DFullScreen = false
        } catch (_: CancellationException) {
            // Gesture was cancelled; keep the current screen state unchanged.
        }
    }

    LaunchedEffect(modelAssetsVersion) {
        val repository = DataRepository(appContext)
        val data = withContext(Dispatchers.IO) { repository.load() }
        appData = data
        val activeCharacterId = when {
            data.characters.containsKey(selectedCharacterId) -> selectedCharacterId
            data.characters.containsKey("kasumi") -> "kasumi"
            else -> data.bands.firstOrNull()
                ?.characters
                ?.firstOrNull { it in data.characters }
                ?: data.characters.keys.firstOrNull()
        } ?: selectedCharacterId
        selectedCharacterId = activeCharacterId
        selectedBandId = data.bands.firstOrNull { activeCharacterId in it.characters }?.id ?: data.bands.firstOrNull()?.id
        val models = withContext(Dispatchers.IO) {
            data.characters[activeCharacterId]?.let { repository.availableModels(it) }.orEmpty()
        }
        val restoredModel = selectedModel?.takeIf { current ->
            current.characterId == activeCharacterId && models.any { it.modelAssetPath == current.modelAssetPath }
        }
            ?: preferredModelAssetPath?.let { path -> models.firstOrNull { it.modelAssetPath == path } }
            ?: models.firstOrNull()
        selectedModel = restoredModel
        preferredModelAssetPath = restoredModel?.modelAssetPath
        saveModelSelection(appContext, activeCharacterId, restoredModel)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val data = appData
        if (data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (selectedScreen == Screen.Live2D && live2DFullScreen) {
            Live2DScreen(
                selectedModel = selectedModel,
                renderSettings = renderSettings,
                fullScreen = true,
                onFullScreenChanged = { live2DFullScreen = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Screen.entries.forEach { screen ->
                            NavigationBarItem(
                                selected = selectedScreen == screen,
                                onClick = {
                                    selectedScreen = screen
                                    live2DFullScreen = false
                                },
                                icon = { NavIcon(screen) },
                                label = { Text(screen.title, fontWeight = FontWeight.Bold) },
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Header(selectedModel)
                    Spacer(Modifier.height(12.dp))
                    AnimatedContent(targetState = selectedScreen, label = "screen") { screen ->
                        when (screen) {
                            Screen.Live2D -> Live2DScreen(
                                selectedModel = selectedModel,
                                renderSettings = renderSettings,
                                fullScreen = false,
                                onFullScreenChanged = { live2DFullScreen = it },
                            )
                            Screen.Model -> ModelScreen(
                                data = data,
                                selectedBandId = selectedBandId,
                                selectedCharacterId = selectedCharacterId,
                                selectedModel = selectedModel,
                                modelAssetsVersion = modelAssetsVersion,
                                onBandSelected = { band ->
                                    selectedBandId = band.id
                                    band.characters.firstOrNull()?.let { characterId ->
                                        val model = data.characters[characterId]
                                            ?.let { DataRepository(appContext).availableModels(it).firstOrNull() }
                                        selectCharacterModel(characterId, model)
                                    }
                                },
                                onCharacterSelected = { character ->
                                    selectCharacterModel(
                                        character.id,
                                        DataRepository(appContext).availableModels(character).firstOrNull(),
                                    )
                                },
                                onModelSelected = { selectCharacterModel(it.characterId, it) },
                                onModelAssetsChanged = { modelAssetsVersion += 1 },
                            )
                            Screen.Settings -> SettingsScreen(
                                themeSettings = themeSettings,
                                onThemeSettingsChanged = onThemeSettingsChanged,
                                renderSettings = renderSettings,
                                onRenderSettingsChanged = updateRenderSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(selectedModel: ModelChoice?) {
    Column {
        Text(
            text = "Bandori Pet",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = selectedModel?.title ?: "选择你的 Live2D 模型",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Live2DScreen(
    selectedModel: ModelChoice?,
    renderSettings: RenderSettings,
    fullScreen: Boolean,
    onFullScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember(selectedModel) { mutableStateOf<String?>(null) }
    var locked by remember(selectedModel) { mutableStateOf(true) }
    var controlsVisible by remember(selectedModel) { mutableStateOf(true) }
    var controlPulse by remember(selectedModel) { mutableStateOf(0) }

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
            modifier = modifier,
        )
    } else {
        ElevatedCard(
            modifier = modifier.fillMaxSize(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Live2DStage(
                selectedModel = selectedModel,
                renderSettings = renderSettings,
                status = status,
                locked = locked,
                controlsVisible = controlsVisible,
                fullScreen = false,
                cornerRadius = 26.dp,
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun Live2DStage(
    selectedModel: ModelChoice?,
    renderSettings: RenderSettings,
    status: String?,
    locked: Boolean,
    controlsVisible: Boolean,
    fullScreen: Boolean,
    cornerRadius: Dp,
    onStatusChanged: (String?) -> Unit,
    onInteraction: () -> Unit,
    onLockedChange: (Boolean) -> Unit,
    onFullScreenChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        ContentUriImage(
            uri = renderSettings.backgroundUri,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selectedModel == null) {
            EmptyMessage("还没有可展示的模型", "进入“模型”页选择乐队和角色。")
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    Live2DRenderView(context).apply {
                        statusChanged = onStatusChanged
                        interactionChanged = onInteraction
                        setInteractionLocked(locked)
                        setRenderOptions(renderSettings.fpsLimit, renderSettings.vsyncEnabled)
                        setModel(selectedModel)
                    }
                },
                update = { view ->
                    view.statusChanged = onStatusChanged
                    view.interactionChanged = onInteraction
                    view.setInteractionLocked(locked)
                    view.setRenderOptions(renderSettings.fpsLimit, renderSettings.vsyncEnabled)
                    view.setModel(selectedModel)
                },
            )
        }
        status?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(18.dp),
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
            visible = controlsVisible && selectedModel != null,
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
    }
}

@Composable
private fun Live2DControlButton(
    icon: Live2DControlIcon,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val iconColor = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
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
private fun ModelScreen(
    data: AppData,
    selectedBandId: String?,
    selectedCharacterId: String,
    selectedModel: ModelChoice?,
    modelAssetsVersion: Int,
    onBandSelected: (Band) -> Unit,
    onCharacterSelected: (CharacterInfo) -> Unit,
    onModelSelected: (ModelChoice) -> Unit,
    onModelAssetsChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(data) { DataRepository(context) }
    val selectedBand = data.bands.firstOrNull { it.id == selectedBandId } ?: data.bands.first()
    val characters = selectedBand.characters.mapNotNull { id -> data.characters[id] }
    val selectedCharacter = data.characters[selectedCharacterId]
    val availableModels = remember(selectedCharacter, modelAssetsVersion) {
        selectedCharacter?.let { repository.availableModels(it) }.orEmpty()
    }
    var modelTransfer by remember { mutableStateOf<ModelTransferState?>(null) }
    var downloadMessage by remember(selectedCharacter?.id) { mutableStateOf<String?>(null) }

    fun startCharacterModelTransfer(
        character: CharacterInfo,
        actionLabel: String,
        successMessage: String,
        failureMessage: String,
    ) {
        if (modelTransfer != null) return
        modelTransfer = ModelTransferState(
            characterId = character.id,
            characterName = character.display,
            actionLabel = actionLabel,
        )
        if (selectedCharacterId == character.id) downloadMessage = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ZstModelArchive.downloadCharacter(context.applicationContext, character.id) { progress ->
                        scope.launch {
                            modelTransfer = modelTransfer
                                ?.takeIf { it.characterId == character.id }
                                ?.copy(progress = progress)
                        }
                    }
                }
            }
            result.onSuccess {
                if (selectedCharacterId == character.id) downloadMessage = successMessage
                onModelAssetsChanged()
            }.onFailure { error ->
                if (selectedCharacterId == character.id) downloadMessage = error.localizedMessage ?: failureMessage
            }
            if (modelTransfer?.characterId == character.id) modelTransfer = null
        }
    }

    fun updateCharacterModel(character: CharacterInfo) {
        startCharacterModelTransfer(
            character = character,
            actionLabel = "正在更新",
            successMessage = "更新完成，正在载入...",
            failureMessage = "更新失败",
        )
    }

    fun deleteCharacterModel(character: CharacterInfo) {
        scope.launch {
            withContext(Dispatchers.IO) {
                ZstModelArchive.deleteDownloadedCharacter(context.applicationContext, character.id)
            }
            onModelAssetsChanged()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SelectionWindow(
            title = "选择乐队",
            subtitle = "滑动选择乐队，角色列表会同步更新",
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp),
        ) {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(data.bands.size, key = { data.bands[it].id }) { index ->
                    val band = data.bands[index]
                    ImageCard(
                        modifier = Modifier.width(186.dp),
                        title = band.display,
                        imagePath = band.logo,
                        selected = band.id == selectedBand.id,
                        aspectRatio = 1.8f,
                        imageContentScale = ContentScale.Fit,
                        onClick = { onBandSelected(band) },
                    )
                }
            }
        }

        SelectionWindow(
            title = "选择角色",
            subtitle = selectedBand.display,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(characters, key = { it.id }) { character ->
                    var menuExpanded by remember(character.id) { mutableStateOf(false) }
                    val hasDownloadedModel = remember(menuExpanded, modelAssetsVersion, character.id) {
                        menuExpanded && ZstModelArchive.hasDownloadedCharacter(context.applicationContext, character.id)
                    }

                    Box {
                        ImageCard(
                            title = character.display,
                            subtitle = selectedBand.display,
                            imagePath = "models/${character.id}/character.png",
                            imageReloadKey = modelAssetsVersion,
                            selected = character.id == selectedCharacterId,
                            aspectRatio = 0.82f,
                            onClick = { onCharacterSelected(character) },
                            onLongClick = { menuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.width(224.dp),
                            shape = RoundedCornerShape(20.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = character.display,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "角色模型",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("更新模型") },
                                enabled = modelTransfer == null,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    updateCharacterModel(character)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除已下载模型") },
                                enabled = hasDownloadedModel,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    deleteCharacterModel(character)
                                },
                            )
                        }
                    }
                }
            }
        }

        SelectionWindow(
            title = "选择服装",
            subtitle = selectedCharacter?.display?.let { "选择${it}的服装" } ?: "当前角色",
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f),
        ) {
            if (availableModels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ModelDownloadPrompt(
                        character = selectedCharacter,
                        transfer = modelTransfer,
                        message = downloadMessage,
                        onDownload = {
                            selectedCharacter?.let { character ->
                                startCharacterModelTransfer(
                                    character = character,
                                    actionLabel = "正在下载",
                                    successMessage = "下载完成，正在载入...",
                                    failureMessage = "下载失败",
                                )
                            }
                        },
                    )
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    modelTransfer?.let { transfer ->
                        ModelTransferProgress(
                            transfer = transfer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(156.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(availableModels, key = { it.modelAssetPath }) { model ->
                            TextCard(
                                title = model.costumeName,
                                subtitle = model.costumeId,
                                selected = selectedModel?.modelAssetPath == model.modelAssetPath,
                                showMoc3Badge = model.isMoc3,
                                onClick = { onModelSelected(model) },
                            )
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun ModelDownloadPrompt(
    character: CharacterInfo?,
    transfer: ModelTransferState?,
    message: String?,
    onDownload: () -> Unit,
) {
    val transferring = transfer != null
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            enabled = character != null && !transferring,
            onClick = onDownload,
        ) {
            if (transferring) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (transferring) "${transfer?.actionLabel}..." else "下载${character?.display ?: "角色"}模型")
        }
        transfer?.let { ModelTransferProgress(it) }
        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ModelTransferProgress(
    transfer: ModelTransferState,
    modifier: Modifier = Modifier,
) {
    val progress = transfer.progress
    val fraction = progress?.takeIf { it.totalBytes > 0 }
        ?.let { (it.downloadedBytes.toFloat() / it.totalBytes.toFloat()).coerceIn(0f, 1f) }
    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${transfer.actionLabel}${transfer.characterName}模型",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = progress?.let { formatTransferSpeed(it.bytesPerSecond) } ?: "连接中...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = progress?.let { formatTransferProgress(it) } ?: "等待服务器响应...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTransferProgress(progress: ZstModelArchive.DownloadProgress): String {
    val downloaded = formatBytes(progress.downloadedBytes.toDouble())
    val total = progress.totalBytes.takeIf { it > 0 }?.let { formatBytes(it.toDouble()) } ?: "未知大小"
    val percent = progress.totalBytes.takeIf { it > 0 }
        ?.let { ((progress.downloadedBytes * 100.0) / it).roundToInt().coerceIn(0, 100) }
    return if (percent != null) "$downloaded / $total，$percent%" else "$downloaded / $total"
}

private fun formatTransferSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond)}/s"

private fun formatBytes(bytes: Double): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.coerceAtLeast(0.0)
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val valueText = if (value >= 10.0 || unitIndex == 0) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$valueText ${units[unitIndex]}"
}

@Composable
private fun SettingsScreen(
    themeSettings: ThemeSettings,
    onThemeSettingsChanged: (ThemeSettings) -> Unit,
    renderSettings: RenderSettings,
    onRenderSettingsChanged: (RenderSettings) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var wallpaperEnabled by remember { mutableStateOf(isWallpaperEnabled(appContext)) }
    var wallpaperBackgroundUri by remember { mutableStateOf(loadWallpaperBackgroundUri(appContext)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeSettingsCard(
            settings = themeSettings,
            onSettingsChanged = onThemeSettingsChanged,
        )
        RenderSettingsCard(
            settings = renderSettings,
            onSettingsChanged = onRenderSettingsChanged,
        )
        WallpaperSettingsCard(
            enabled = wallpaperEnabled,
            backgroundUri = wallpaperBackgroundUri,
            onEnabledChanged = { enabled ->
                wallpaperEnabled = enabled
                setWallpaperEnabled(appContext, enabled)
                if (enabled) openLiveWallpaperPicker(context)
            },
            onBackgroundChanged = { uri ->
                wallpaperBackgroundUri = uri
                saveWallpaperBackgroundUri(appContext, uri)
            },
            onAdjustPosition = {
                context.startActivity(Intent(context, WallpaperAdjustActivity::class.java))
            },
        )
        InfoCard(
            "关于",
            "Bandori Pet Android。点击 Live2D 展示框会轮换触发模型动作。当前项目使用 GPLv3 许可证发布。",
        )
    }
}

@Composable
private fun ThemeSettingsCard(
    settings: ThemeSettings,
    onSettingsChanged: (ThemeSettings) -> Unit,
) {
    var darkModeMenuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("主题设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "控制应用配色与明暗外观，修改后立即生效。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("莫奈取色", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.dynamicColorEnabled) "按照系统壁纸动态取色。" else "关闭时使用默认粉色主题。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.dynamicColorEnabled,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(dynamicColorEnabled = enabled))
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("深色模式", fontWeight = FontWeight.SemiBold)
                    Text(
                        darkModeDescription(settings.darkMode),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Box {
                    TextButton(onClick = { darkModeMenuExpanded = true }) {
                        Text(darkModeLabel(settings.darkMode))
                    }
                    DropdownMenu(
                        expanded = darkModeMenuExpanded,
                        onDismissRequest = { darkModeMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        DarkModeSetting.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(darkModeLabel(mode)) },
                                onClick = {
                                    darkModeMenuExpanded = false
                                    onSettingsChanged(settings.copy(darkMode = mode))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun darkModeLabel(mode: DarkModeSetting): String = when (mode) {
    DarkModeSetting.On -> "开启"
    DarkModeSetting.Off -> "关闭"
    DarkModeSetting.System -> "跟随系统"
}

private fun darkModeDescription(mode: DarkModeSetting): String = when (mode) {
    DarkModeSetting.On -> "始终使用深色主题。"
    DarkModeSetting.Off -> "始终使用浅色主题。"
    DarkModeSetting.System -> "根据系统设置自动切换。"
}

private fun openLiveWallpaperPicker(context: android.content.Context) {
    val component = ComponentName(context, Live2DWallpaperService::class.java)
    val changeIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
    runCatching { context.startActivity(changeIntent) }
        .recoverCatching { context.startActivity(fallbackIntent) }
}

@Composable
private fun WallpaperSettingsCard(
    enabled: Boolean,
    backgroundUri: String?,
    onEnabledChanged: (Boolean) -> Unit,
    onBackgroundChanged: (String?) -> Unit,
    onAdjustPosition: () -> Unit,
) {
    val context = LocalContext.current
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistBackgroundUri(context.applicationContext, uri)
        onBackgroundChanged(uri.toString())
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("壁纸设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "桌面渲染使用当前选择的 Live2D 模型。桌面上只响应点击动作，位置需要在这里调整。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("开启桌面渲染", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "已允许动态壁纸渲染，系统仍需要选择 Bandori Pet 壁纸。" else "关闭后即使已设为壁纸也不会启动渲染。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("调整模型位置", fontWeight = FontWeight.SemiBold)
                    Text(
                        "全屏后单指拖动，双指缩放，保存后应用到桌面壁纸。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = onAdjustPosition) {
                    Text("调整")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("壁纸背景", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (backgroundUri == null) "使用默认透明背景。" else "已选择照片，只应用到桌面壁纸。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = {
                            backgroundPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Text(if (backgroundUri == null) "选择" else "更换")
                    }
                }
                if (backgroundUri != null) {
                    TextButton(onClick = { onBackgroundChanged(null) }) {
                        Text("清除背景")
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderSettingsCard(
    settings: RenderSettings,
    onSettingsChanged: (RenderSettings) -> Unit,
) {
    val context = LocalContext.current
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistBackgroundUri(context.applicationContext, uri)
        onSettingsChanged(settings.copy(backgroundUri = uri.toString()))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Live2D 设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "这些选项会立即应用并自动保存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("模型渲染 FPS 限制", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${settings.fpsLimit} FPS",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = settings.fpsLimit.toFloat(),
                    onValueChange = { value ->
                        val fps = (value / 5f).roundToInt().coerceIn(3, 24) * 5
                        onSettingsChanged(settings.copy(fpsLimit = fps))
                    },
                    valueRange = 15f..120f,
                    steps = 20,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("垂直同步", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.vsyncEnabled) "跟随屏幕刷新，减少画面撕裂。" else "关闭后只受 FPS 限制影响。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.vsyncEnabled,
                    onCheckedChange = { enabled -> onSettingsChanged(settings.copy(vsyncEnabled = enabled)) },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Live2D 背景", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.backgroundUri == null) "使用默认渐变背景。" else "已选择照片，只应用到 Live2D 预览。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = {
                            backgroundPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Text(if (settings.backgroundUri == null) "选择照片" else "更换")
                    }
                }
                if (settings.backgroundUri != null) {
                    TextButton(onClick = { onSettingsChanged(settings.copy(backgroundUri = null)) }) {
                        Text("清除背景")
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionWindow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .clip(RoundedCornerShape(20.dp)),
                content = content,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ImageCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    imagePath: String?,
    imageReloadKey: Int = 0,
    selected: Boolean,
    aspectRatio: Float,
    imageContentScale: ContentScale = ContentScale.Crop,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        border = if (selected) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            AssetImage(
                path = imagePath,
                reloadKey = imageReloadKey,
                modifier = Modifier.fillMaxSize(),
                contentScale = imageContentScale,
                placeholderText = title,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f))))
                    .padding(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(
                            text = it,
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    showMoc3Badge: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp, end = if (showMoc3Badge) 70.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showMoc3Badge) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "MOC3",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyMessage(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NavIcon(screen: Screen) {
    Icon(
        imageVector = when (screen) {
            Screen.Live2D -> Icons.Outlined.Face
            Screen.Model -> Icons.Outlined.ViewInAr
            Screen.Settings -> Icons.Outlined.Settings
        },
        contentDescription = screen.title,
    )
}

private val ModelChoice.isMoc3: Boolean
    get() = modelAssetPath.endsWith(".model3.json") || modelAssetPath.contains("moc3", ignoreCase = true)

@Composable
private fun AssetImage(path: String?, reloadKey: Int = 0, modifier: Modifier, contentScale: ContentScale, placeholderText: String? = null) {
    val context = LocalContext.current
    val bitmap = remember(path, reloadKey) {
        path?.let {
            runCatching {
                context.assets.open(it).use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
            }.getOrNull() ?: ZstModelArchive.readLogicalPath(context, it)
                ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholderText?.take(1)?.uppercase() ?: "BP",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun ContentUriImage(uri: String?, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(it))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}
