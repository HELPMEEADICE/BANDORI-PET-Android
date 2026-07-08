package com.bandori.pet

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
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
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Screen.entries.forEach { screen ->
                            NavigationBarItem(
                                selected = selectedScreen == screen,
                                onClick = { selectedScreen = screen },
                                icon = { NavTile(screen.title, selectedScreen == screen) },
                                label = null,
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
            text = selectedModel?.title ?: "请选择模型",
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

    Column(modifier = Modifier.fillMaxSize()) {
        Text("选择乐队", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.32f),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(data.bands, key = { it.id }) { band ->
                ImageCard(
                    title = band.display,
                    imagePath = band.logo,
                    selected = band.id == selectedBand.id,
                    aspectRatio = 1.8f,
                    onClick = { onBandSelected(band) },
                )
            }
        }

        Text("选择角色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.34f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(characters, key = { it.id }) { character ->
                ImageCard(
                    title = character.display,
                    imagePath = "models/${character.id}/character.png",
                    selected = character.id == selectedCharacterId,
                    aspectRatio = 0.86f,
                    onClick = { onCharacterSelected(character) },
                )
            }
        }

        Text("选择服装", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (availableModels.isEmpty()) {
            EmptyMessage("该角色暂无本地模型", "请把模型放到 models/{角色名}/{服装名}/ 下。")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.34f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(availableModels, key = { it.modelAssetPath }) { model ->
                    TextCard(
                        title = model.costumeName,
                        subtitle = model.costumeId,
                        selected = selectedModel?.modelAssetPath == model.modelAssetPath,
                        onClick = { onModelSelected(model) },
                    )
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
private fun ImageCard(
    title: String,
    imagePath: String?,
    selected: Boolean,
    aspectRatio: Float,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(Modifier.fillMaxSize()) {
            AssetImage(
                path = imagePath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.58f))))
                    .padding(10.dp),
            ) {
                Text(title, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TextCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.Center) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun NavTile(title: String, selected: Boolean) {
    Surface(
        modifier = Modifier.size(width = 92.dp, height = 44.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(title, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssetImage(path: String?, modifier: Modifier, contentScale: ContentScale) {
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
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("No Image", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}
