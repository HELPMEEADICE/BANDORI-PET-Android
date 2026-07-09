package com.bandori.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val SETTINGS_PREFS = "bandori_pet_settings"
private const val KEY_FPS_LIMIT = "fps_limit"
private const val KEY_VSYNC_ENABLED = "vsync_enabled"

private data class RenderSettings(
    val fpsLimit: Int = 60,
    val vsyncEnabled: Boolean = true,
) {
    fun save(context: Context) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS_LIMIT, fpsLimit)
            .putBoolean(KEY_VSYNC_ENABLED, vsyncEnabled)
            .apply()
    }

    companion object {
        fun load(context: Context): RenderSettings {
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            return RenderSettings(
                fpsLimit = prefs.getInt(KEY_FPS_LIMIT, 60).coerceIn(15, 120),
                vsyncEnabled = prefs.getBoolean(KEY_VSYNC_ENABLED, true),
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BandoriPetTheme {
                BandoriPetApp()
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

@Composable
private fun BandoriPetApp() {
    val context = LocalContext.current
    var appData by remember { mutableStateOf<AppData?>(null) }
    var selectedScreen by remember { mutableStateOf(Screen.Live2D) }
    var selectedBandId by remember { mutableStateOf<String?>(null) }
    var selectedCharacterId by remember { mutableStateOf("tomorin") }
    var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }
    var live2DFullScreen by remember { mutableStateOf(false) }
    var modelAssetsVersion by remember { mutableStateOf(0) }
    var renderSettings by remember { mutableStateOf(RenderSettings.load(context.applicationContext)) }
    val updateRenderSettings: (RenderSettings) -> Unit = { settings ->
        renderSettings = settings
        settings.save(context.applicationContext)
    }

    LaunchedEffect(modelAssetsVersion) {
        val repository = DataRepository(context.applicationContext)
        val data = withContext(Dispatchers.IO) { repository.load() }
        appData = data
        selectedBandId = data.bands.firstOrNull { selectedCharacterId in it.characters }?.id ?: data.bands.firstOrNull()?.id
        val models = withContext(Dispatchers.IO) {
            data.characters[selectedCharacterId]?.let { repository.availableModels(it) }.orEmpty()
        }
        selectedModel = selectedModel?.takeIf { current -> models.any { it.modelAssetPath == current.modelAssetPath } }
            ?: models.firstOrNull()
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
                                icon = { NavIcon(screen, selectedScreen == screen) },
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
                                        selectedCharacterId = characterId
                                        selectedModel = data.characters[characterId]
                                            ?.let { DataRepository(context).availableModels(it).firstOrNull() }
                                    }
                                },
                                onCharacterSelected = { character ->
                                    selectedCharacterId = character.id
                                    selectedModel = DataRepository(context).availableModels(character).firstOrNull()
                                },
                                onModelSelected = { selectedModel = it },
                                onModelAssetsChanged = { modelAssetsVersion += 1 },
                            )
                            Screen.Settings -> SettingsScreen(
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
    var downloadingModel by remember(selectedCharacter?.id) { mutableStateOf(false) }
    var downloadMessage by remember(selectedCharacter?.id) { mutableStateOf<String?>(null) }

    fun updateCharacterModel(character: CharacterInfo) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ZstModelArchive.downloadCharacter(context.applicationContext, character.id)
                }
            }
            result.onSuccess {
                if (selectedCharacterId == character.id) downloadMessage = "更新完成，正在载入..."
                onModelAssetsChanged()
            }.onFailure { error ->
                if (selectedCharacterId == character.id) downloadMessage = error.localizedMessage ?: "更新失败"
            }
        }
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
                                onClick = {
                                    menuExpanded = false
                                    updateCharacterModel(character)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除已下载模型") },
                                enabled = hasDownloadedModel,
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
                        downloading = downloadingModel,
                        message = downloadMessage,
                        onDownload = {
                            selectedCharacter?.let { character ->
                                downloadingModel = true
                                downloadMessage = null
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            ZstModelArchive.downloadCharacter(context.applicationContext, character.id)
                                        }
                                    }
                                    result.onSuccess {
                                        downloadMessage = "下载完成，正在载入..."
                                        onModelAssetsChanged()
                                    }.onFailure { error ->
                                        downloadMessage = error.localizedMessage ?: "下载失败"
                                    }
                                    downloadingModel = false
                                }
                            }
                        },
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(156.dp),
                    modifier = Modifier.fillMaxSize(),
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

@Composable
private fun ModelDownloadPrompt(
    character: CharacterInfo?,
    downloading: Boolean,
    message: String?,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            enabled = character != null && !downloading,
            onClick = onDownload,
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (downloading) "正在下载..." else "下载${character?.display ?: "角色"}模型")
        }
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
private fun SettingsScreen(
    renderSettings: RenderSettings,
    onRenderSettingsChanged: (RenderSettings) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RenderSettingsCard(
            settings = renderSettings,
            onSettingsChanged = onRenderSettingsChanged,
        )
        InfoCard(
            "关于",
            "Bandori Pet Android。点击 Live2D 展示框会轮换触发模型动作。当前项目使用 GPLv3 许可证发布。",
        )
    }
}

@Composable
private fun RenderSettingsCard(
    settings: RenderSettings,
    onSettingsChanged: (RenderSettings) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("渲染设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "这些选项会立即应用到 Live2D 渲染线程，并自动保存。",
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
                contentScale = ContentScale.Crop,
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
private fun NavIcon(screen: Screen, selected: Boolean) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        val iconColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
        ) {
            when (screen) {
                Screen.Live2D -> {
                    drawCircle(iconColor, radius = size.minDimension * 0.26f, center = Offset(size.width * 0.5f, size.height * 0.32f))
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(size.width * 0.18f, size.height * 0.58f),
                        size = Size(size.width * 0.64f, size.height * 0.34f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.18f, size.height * 0.18f),
                    )
                }
                Screen.Model -> {
                    val stroke = Stroke(width = 2.4.dp.toPx())
                    drawRoundRect(iconColor, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = stroke)
                    drawCircle(iconColor, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.5f, size.height * 0.48f))
                    drawLine(iconColor, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height * 0.2f), strokeWidth = 2.2.dp.toPx())
                    drawLine(iconColor, Offset(size.width * 0.5f, size.height * 0.8f), Offset(size.width * 0.5f, size.height), strokeWidth = 2.2.dp.toPx())
                }
                Screen.Settings -> {
                    val stroke = Stroke(width = 2.2.dp.toPx())
                    drawCircle(iconColor, radius = size.minDimension * 0.34f, style = stroke)
                    drawCircle(iconColor, radius = size.minDimension * 0.11f)
                    drawLine(iconColor, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height * 0.2f), strokeWidth = 2.2.dp.toPx())
                    drawLine(iconColor, Offset(size.width * 0.5f, size.height * 0.8f), Offset(size.width * 0.5f, size.height), strokeWidth = 2.2.dp.toPx())
                    drawLine(iconColor, Offset(0f, size.height * 0.5f), Offset(size.width * 0.2f, size.height * 0.5f), strokeWidth = 2.2.dp.toPx())
                    drawLine(iconColor, Offset(size.width * 0.8f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), strokeWidth = 2.2.dp.toPx())
                }
            }
        }
    }
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
