package com.bandori.pet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bandori.pet.data.AppData
import com.bandori.pet.data.DataRepository
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.data.ZstModelArchive
import com.bandori.pet.ui.live2d.Live2DScreen
import com.bandori.pet.ui.model.ModelScreen
import com.bandori.pet.ui.settings.SettingsScreen
import com.bandori.pet.ui.theme.BandoriPetTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContext = applicationContext
        I18n.init(appContext)
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

enum class Screen {
    Live2D,
    Model,
    Settings,
}

fun Screen.title(): String = when (this) {
    Screen.Live2D -> I18n.t("nav_live2d")
    Screen.Model -> I18n.t("nav_model")
    Screen.Settings -> I18n.t("nav_settings")
}

enum class Live2DControlIcon {
    Lock,
    Unlock,
    FullScreen,
    ExitFullScreen,
}

data class ModelTransferState(
    val characterId: String,
    val characterName: String,
    val actionLabel: String,
    val progress: ZstModelArchive.DownloadProgress? = null,
)

@Composable
fun BandoriPetApp(
    themeSettings: ThemeSettings,
    onThemeSettingsChanged: (ThemeSettings) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var appData by remember { mutableStateOf<AppData?>(null) }
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.Live2D) }
    var selectedBandId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCharacterId by remember { mutableStateOf(loadSelectedCharacterId(appContext)) }
    var selectedModel by remember { mutableStateOf<ModelChoice?>(null) }
    var preferredModelAssetPath by remember { mutableStateOf(loadSelectedModelAssetPath(appContext)) }
    var modelAssetsVersion by remember { mutableStateOf(0) }
    var renderSettings by remember { mutableStateOf(RenderSettings.load(appContext)) }
    val repository = remember { DataRepository(appContext) }
    val scope = rememberCoroutineScope()
    val modelSelectionGeneration = remember { AtomicInteger(0) }
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
    val selectCharacter: (String) -> Unit = selectCharacter@{ characterId ->
        if (selectedCharacterId == characterId && selectedModel?.characterId == characterId) return@selectCharacter
        selectedCharacterId = characterId
        selectedModel = null
        preferredModelAssetPath = null
        val character = appData?.characters?.get(characterId)
        val generation = modelSelectionGeneration.incrementAndGet()
        scope.launch {
            val model = try {
                withContext(Dispatchers.IO) {
                    character?.let(repository::availableModels)?.firstOrNull()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (generation == modelSelectionGeneration.get() && selectedCharacterId == characterId) {
                selectCharacterModel(characterId, model)
            }
        }
    }

    LaunchedEffect(modelAssetsVersion) {
        val reloadGeneration = modelSelectionGeneration.incrementAndGet()
        val data = runCatching { withContext(Dispatchers.IO) { repository.load() } }
            .getOrNull() ?: return@LaunchedEffect
        appData = data
        if (reloadGeneration != modelSelectionGeneration.get()) return@LaunchedEffect
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
        val models = try {
            withContext(Dispatchers.IO) {
                data.characters[activeCharacterId]?.let { repository.availableModels(it) }.orEmpty()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@LaunchedEffect
        }
        if (
            reloadGeneration != modelSelectionGeneration.get() ||
            selectedCharacterId != activeCharacterId
        ) return@LaunchedEffect
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
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    AppTopBar(selectedModel = selectedModel)
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                    ) {
                        Screen.entries.forEach { screen ->
                            NavigationBarItem(
                                selected = selectedScreen == screen,
                                onClick = {
                                    selectedScreen = screen
                                },
                                icon = { NavIcon(screen, selected = selectedScreen == screen) },
                                label = {
                                    Text(
                                        text = screen.title(),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    AnimatedContent(
                        targetState = selectedScreen,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (slideInHorizontally(animationSpec = tween(240)) { width -> direction * width / 12 } +
                                fadeIn(animationSpec = tween(200))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(180)) { width -> -direction * width / 12 } +
                                    fadeOut(animationSpec = tween(140)))
                        },
                        contentKey = { it },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.Live2D -> Live2DScreen(
                                selectedModel = selectedModel,
                                renderSettings = renderSettings,
                                fullScreen = false,
                                onRemoteCharacterSelected = selectCharacter,
                                onFullScreenChanged = { fullScreen ->
                                    if (fullScreen) {
                                        context.startActivity(Intent(context, FullscreenLive2DActivity::class.java))
                                    }
                                },
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
                                        selectCharacter(characterId)
                                    }
                                },
                                onCharacterSelected = { character ->
                                    selectCharacter(character.id)
                                },
                                onModelSelected = {
                                    modelSelectionGeneration.incrementAndGet()
                                    selectCharacterModel(it.characterId, it)
                                },
                                onModelAssetsChanged = {
                                    modelAssetsVersion += 1
                                    DataRepository.invalidateCache()
                                },
                            )
                            Screen.Settings -> SettingsScreen(
                                selectedModel = selectedModel,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(selectedModel: ModelChoice?) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = I18n.t("app_title"),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = selectedModel?.title ?: I18n.t("header_select_model"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun NavIcon(screen: Screen, selected: Boolean) {
    Icon(
        imageVector = when (screen to selected) {
            Screen.Live2D to true -> Icons.Filled.Face
            Screen.Model to true -> Icons.Filled.ViewInAr
            Screen.Settings to true -> Icons.Filled.Settings
            Screen.Live2D to false -> Icons.Outlined.Face
            Screen.Model to false -> Icons.Outlined.ViewInAr
            else -> Icons.Outlined.Settings
        },
        contentDescription = screen.title(),
    )
}
