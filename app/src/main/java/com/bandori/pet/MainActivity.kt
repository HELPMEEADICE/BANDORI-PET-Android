package com.bandori.pet

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bandori.pet.data.AppData
import com.bandori.pet.data.Band
import com.bandori.pet.data.CharacterInfo
import com.bandori.pet.data.DataRepository
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.live2d.Live2DRenderView
import com.bandori.pet.ui.theme.BandoriPetTheme

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

@Composable
private fun BandoriPetApp() {
    val context = LocalContext.current
    var appData by remember { mutableStateOf<AppData?>(null) }
    var selectedScreen by remember { mutableStateOf(Screen.Live2D) }
    var selectedBandId by remember { mutableStateOf<String?>(null) }
    var selectedCharacterId by remember { mutableStateOf("tomorin") }
    var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }

    LaunchedEffect(Unit) {
        val repository = DataRepository(context)
        val data = repository.load()
        appData = data
        selectedBandId = data.bands.firstOrNull { selectedCharacterId in it.characters }?.id ?: data.bands.firstOrNull()?.id
        selectedModel = data.characters[selectedCharacterId]
            ?.let { repository.availableModels(it).firstOrNull() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val data = appData
        if (data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Screen.entries.forEach { screen ->
                            NavigationBarItem(
                                selected = selectedScreen == screen,
                                onClick = { selectedScreen = screen },
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
                            Screen.Live2D -> Live2DScreen(selectedModel)
                            Screen.Model -> ModelScreen(
                                data = data,
                                selectedBandId = selectedBandId,
                                selectedCharacterId = selectedCharacterId,
                                selectedModel = selectedModel,
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
                            )
                            Screen.Settings -> SettingsScreen(selectedModel)
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
private fun Live2DScreen(selectedModel: ModelChoice?) {
    var status by remember(selectedModel) { mutableStateOf<String?>(null) }
    ElevatedCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selectedModel == null) {
                EmptyMessage("还没有可展示的模型", "进入“模型”页选择乐队和角色。")
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        Live2DRenderView(context).apply {
                            statusChanged = { status = it }
                            setModel(selectedModel)
                        }
                    },
                    update = { view ->
                        view.statusChanged = { status = it }
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
        }
    }
}

@Composable
private fun ModelScreen(
    data: AppData,
    selectedBandId: String?,
    selectedCharacterId: String,
    selectedModel: ModelChoice?,
    onBandSelected: (Band) -> Unit,
    onCharacterSelected: (CharacterInfo) -> Unit,
    onModelSelected: (ModelChoice) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(data) { DataRepository(context) }
    val selectedBand = data.bands.firstOrNull { it.id == selectedBandId } ?: data.bands.first()
    val characters = selectedBand.characters.mapNotNull { id -> data.characters[id] }
    val selectedCharacter = data.characters[selectedCharacterId]
    val availableModels = remember(selectedCharacter) {
        selectedCharacter?.let { repository.availableModels(it) }.orEmpty()
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
                    ImageCard(
                        title = character.display,
                        subtitle = selectedBand.display,
                        imagePath = "models/${character.id}/character.png",
                        selected = character.id == selectedCharacterId,
                        aspectRatio = 0.82f,
                        onClick = { onCharacterSelected(character) },
                    )
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
                    EmptyMessage("该角色暂无本地模型", "请把模型放到 models/{角色名}/{服装名}/ 下。")
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
private fun SettingsScreen(selectedModel: ModelChoice?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard("渲染核心", "Live2D-v2-Lua + LuaJIT，通过 JNI 建立 Android EGL/OpenGL ES 2.0 上下文。moc 使用 live2d_embed.lua，moc3/model3 使用 live2d_moc3_embed.lua。")
        InfoCard("运行要求", "运行设备需要支持 OpenGL ES 2.0，并在 app/src/main/jniLibs/<abi>/ 提供 libluajit.so。")
        InfoCard("当前模型", selectedModel?.modelAssetPath ?: "未选择")
        InfoCard("关于", "Bandori Pet Android，原生 Material Design 3 风格。点击 Live2D 展示框会轮换触发模型动作。")
    }
}

@Composable
private fun SelectionWindow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
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
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                content = content,
            )
        }
    }
}

@Composable
private fun ImageCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    imagePath: String?,
    selected: Boolean,
    aspectRatio: Float,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
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
private fun AssetImage(path: String?, modifier: Modifier, contentScale: ContentScale, placeholderText: String? = null) {
    val context = LocalContext.current
    val bitmap = remember(path) {
        path?.let {
            runCatching {
                context.assets.open(it).use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
            }.getOrNull()
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
