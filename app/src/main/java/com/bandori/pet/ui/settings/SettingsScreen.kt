package com.bandori.pet.ui.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.bandori.pet.DarkModeSetting
import com.bandori.pet.FloatingLive2DItem
import com.bandori.pet.FloatingOverlaySettings
import com.bandori.pet.I18n
import com.bandori.pet.RenderResolution
import com.bandori.pet.RenderSettings
import com.bandori.pet.ThemeSettings
import com.bandori.pet.addFloatingLive2DItem
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.floating.FloatingLive2DOverlayService
import com.bandori.pet.isWallpaperEnabled
import com.bandori.pet.llm.ChatHistoryRepository
import com.bandori.pet.llm.LlmSettings
import com.bandori.pet.llm.ThinkingMode
import com.bandori.pet.loadWallpaperBackgroundUri
import com.bandori.pet.persistBackgroundUri
import com.bandori.pet.removeFloatingLive2DItem
import com.bandori.pet.resetFloatingLive2DItemPositions
import com.bandori.pet.saveWallpaperBackgroundUri
import com.bandori.pet.setWallpaperEnabled
import com.bandori.pet.wallpaper.Live2DWallpaperService
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    selectedModel: ModelChoice?,
    themeSettings: ThemeSettings,
    onThemeSettingsChanged: (ThemeSettings) -> Unit,
    renderSettings: RenderSettings,
    onRenderSettingsChanged: (RenderSettings) -> Unit,
    onSubpageVisibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var wallpaperEnabled by remember { mutableStateOf(isWallpaperEnabled(appContext)) }
    var wallpaperBackgroundUri by remember { mutableStateOf(loadWallpaperBackgroundUri(appContext)) }
    var floatingOverlaySettings by remember { mutableStateOf(FloatingOverlaySettings.load(appContext)) }
    var destination by remember { mutableStateOf(SettingsDestination.Root) }

    fun openRootSettings() {
        destination = SettingsDestination.Root
        onSubpageVisibilityChanged(false)
    }

    if (destination == SettingsDestination.Llm) {
        LlmSettingsScreen(onBack = ::openRootSettings)
        return
    }

    fun updateFloatingOverlaySettings(settings: FloatingOverlaySettings) {
        val latestItemsById = FloatingOverlaySettings.load(appContext).items.associateBy { it.id }
        val nextSettings = settings.copy(
            items = settings.items.map { item ->
                latestItemsById[item.id]?.let { latest ->
                    item.copy(
                        x = latest.x,
                        y = latest.y,
                        width = latest.width,
                        height = latest.height,
                    )
                } ?: item
            },
        )
        floatingOverlaySettings = nextSettings
        nextSettings.save(appContext)
        FloatingLive2DOverlayService.sync(appContext)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "theme") {
            ThemeSettingsCard(
                settings = themeSettings,
                onSettingsChanged = onThemeSettingsChanged,
            )
        }
        item(key = "render") {
            RenderSettingsCard(
                settings = renderSettings,
                onSettingsChanged = { settings ->
                    onRenderSettingsChanged(settings)
                    FloatingLive2DOverlayService.sync(appContext)
                },
            )
        }
        item(key = "llm") {
            LlmSettingsEntryCard(onClick = {
                destination = SettingsDestination.Llm
                onSubpageVisibilityChanged(true)
            })
        }
        item(key = "floating") {
            FloatingOverlaySettingsCard(
                selectedModel = selectedModel,
                settings = floatingOverlaySettings,
                onSettingsChanged = ::updateFloatingOverlaySettings,
                onRefreshSettings = { floatingOverlaySettings = FloatingOverlaySettings.load(appContext) },
            )
        }
        item(key = "wallpaper") {
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
                    context.startActivity(Intent(context, com.bandori.pet.WallpaperAdjustActivity::class.java))
                },
            )
        }
        item(key = "info") {
            InfoCard(
                I18n.t("settings_about"),
                I18n.t("settings_about_text"),
            )
        }
    }
}

private enum class SettingsDestination { Root, Llm }

@Composable
private fun LlmSettingsEntryCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { LlmSettings.load(context.applicationContext) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(I18n.t("settings_llm_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (settings.isConfigured) I18n.t("settings_llm_configured", settings.model) else I18n.t("settings_llm_not_configured"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun LlmSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var draft by remember { mutableStateOf(LlmSettings.load(appContext)) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var thinkingMenuExpanded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableStateOf(0f) }
    var predictiveBackEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }

    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent ->
                predictiveBackProgress = backEvent.progress
                predictiveBackEdge = backEvent.swipeEdge
            }
            onBack()
        } catch (_: CancellationException) {
            predictiveBackProgress = 0f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val direction = if (predictiveBackEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                translationX = size.width * 0.16f * predictiveBackProgress * direction
                alpha = 1f - 0.12f * predictiveBackProgress
            }
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = I18n.t("back")) }
            Column {
                Text(I18n.t("settings_llm_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(I18n.t("settings_llm_desc"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it); saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(I18n.t("settings_llm_base_url")) },
                    supportingText = { Text(I18n.t("settings_llm_http_warning")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it); saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(I18n.t("settings_llm_api_key")) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it); saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(I18n.t("settings_llm_model")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.customPrompt,
                    onValueChange = { draft = draft.copy(customPrompt = it); saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(I18n.t("settings_llm_custom_prompt")) },
                    supportingText = { Text(I18n.t("settings_llm_custom_prompt_desc")) },
                    placeholder = { Text(I18n.t("settings_llm_custom_prompt_hint")) },
                    minLines = 4,
                    maxLines = 10,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(I18n.t("settings_llm_thinking"), fontWeight = FontWeight.SemiBold)
                        Text(thinkingModeLabel(draft.thinkingMode), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        TextButton(onClick = { thinkingMenuExpanded = true }) { Text(thinkingModeLabel(draft.thinkingMode)) }
                        DropdownMenu(
                            expanded = thinkingMenuExpanded,
                            onDismissRequest = { thinkingMenuExpanded = false },
                        ) {
                            ThinkingMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(thinkingModeLabel(mode)) },
                                    onClick = {
                                        draft = draft.copy(thinkingMode = mode)
                                        saved = false
                                        thinkingMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(I18n.t("settings_llm_temperature"), fontWeight = FontWeight.SemiBold)
                        Text(String.format("%.1f", draft.temperature), color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = draft.temperature,
                        onValueChange = { draft = draft.copy(temperature = (it * 10).roundToInt() / 10f); saved = false },
                        valueRange = 0f..2f,
                        steps = 19,
                    )
                }
                OutlinedTextField(
                    value = draft.maxTokens.toString(),
                    onValueChange = { value ->
                        value.filter(Char::isDigit).toIntOrNull()?.let {
                            draft = draft.copy(maxTokens = it.coerceIn(1, 32_768))
                            saved = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(I18n.t("settings_llm_max_tokens")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = draft.baseUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") } &&
                        draft.apiKey.isNotBlank() && draft.model.isNotBlank(),
                    onClick = {
                        draft = draft.normalized()
                        draft.save(appContext)
                        saved = true
                    },
                ) { Text(if (saved) I18n.t("settings_llm_saved") else I18n.t("settings_llm_save")) }
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = { confirmClearAll = true }) {
                    Text(I18n.t("settings_llm_clear_all"))
                }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(I18n.t("settings_llm_clear_all")) },
            text = { Text(I18n.t("settings_llm_clear_all_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    ChatHistoryRepository(appContext).clearAll()
                    confirmClearAll = false
                }) { Text(I18n.t("confirm")) }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text(I18n.t("cancel")) } },
        )
    }
}

private fun thinkingModeLabel(mode: ThinkingMode): String = when (mode) {
    ThinkingMode.Auto -> I18n.t("settings_llm_thinking_auto")
    ThinkingMode.Enabled -> I18n.t("settings_llm_thinking_on")
    ThinkingMode.Disabled -> I18n.t("settings_llm_thinking_off")
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
                Text(I18n.t("settings_theme_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    I18n.t("settings_theme_desc"),
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
                    Text(I18n.t("settings_dynamic_color"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.dynamicColorEnabled) I18n.t("settings_dynamic_color_on") else I18n.t("settings_dynamic_color_off"),
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
                    Text(I18n.t("settings_dark_mode"), fontWeight = FontWeight.SemiBold)
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

@Composable
private fun RenderSettingsCard(
    settings: RenderSettings,
    onSettingsChanged: (RenderSettings) -> Unit,
) {
    val context = LocalContext.current
    var fpsLimit by remember(settings.fpsLimit) { mutableFloatStateOf(settings.fpsLimit.toFloat()) }
    var renderResolutionIndex by remember(settings.renderResolution) {
        mutableFloatStateOf(settings.renderResolution.ordinal.toFloat())
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistBackgroundUri(context.applicationContext, uri)
        onSettingsChanged(settings.copy(backgroundUri = uri.toString()))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(I18n.t("settings_live2d_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    I18n.t("settings_live2d_desc"),
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
                    Text(I18n.t("settings_fps_limit"), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${fpsLimit.roundToInt()} FPS",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = fpsLimit,
                    onValueChange = { value ->
                        fpsLimit = ((value / 5f).roundToInt().coerceIn(3, 24) * 5).toFloat()
                    },
                    onValueChangeFinished = {
                        val fps = fpsLimit.roundToInt()
                        if (fps != settings.fpsLimit) onSettingsChanged(settings.copy(fpsLimit = fps))
                    },
                    valueRange = 15f..120f,
                    steps = 20,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(I18n.t("settings_render_resolution"), fontWeight = FontWeight.SemiBold)
                    Text(
                        renderResolutionLabel(RenderResolution.entries[renderResolutionIndex.roundToInt()]),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = renderResolutionIndex,
                    onValueChange = { value ->
                        renderResolutionIndex = value.roundToInt()
                            .coerceIn(RenderResolution.entries.indices)
                            .toFloat()
                    },
                    onValueChangeFinished = {
                        val resolution = RenderResolution.entries[renderResolutionIndex.roundToInt()]
                        if (resolution != settings.renderResolution) {
                            onSettingsChanged(settings.copy(renderResolution = resolution))
                        }
                    },
                    valueRange = 0f..RenderResolution.entries.lastIndex.toFloat(),
                    steps = RenderResolution.entries.size - 2,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_fps_display"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.fpsDisplayEnabled) {
                            I18n.t("settings_fps_display_on")
                        } else {
                            I18n.t("settings_fps_display_off")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.fpsDisplayEnabled,
                    onCheckedChange = { enabled -> onSettingsChanged(settings.copy(fpsDisplayEnabled = enabled)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_vsync"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.vsyncEnabled) I18n.t("settings_vsync_on") else I18n.t("settings_vsync_off"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.vsyncEnabled,
                    onCheckedChange = { enabled -> onSettingsChanged(settings.copy(vsyncEnabled = enabled)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_gaze"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.gazeFollowEnabled) {
                            I18n.t("settings_gaze_on")
                        } else {
                            I18n.t("settings_gaze_off")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.gazeFollowEnabled,
                    onCheckedChange = { enabled -> onSettingsChanged(settings.copy(gazeFollowEnabled = enabled)) },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(I18n.t("settings_bg_label"), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.backgroundUri == null) I18n.t("settings_bg_default") else I18n.t("settings_bg_selected"),
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
                        Text(if (settings.backgroundUri == null) I18n.t("settings_select") else I18n.t("settings_change"))
                    }
                }
                if (settings.backgroundUri != null) {
                    TextButton(onClick = { onSettingsChanged(settings.copy(backgroundUri = null)) }) {
                        Text(I18n.t("settings_clear_bg"))
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingOverlaySettingsCard(
    selectedModel: ModelChoice?,
    settings: FloatingOverlaySettings,
    onSettingsChanged: (FloatingOverlaySettings) -> Unit,
    onRefreshSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var hasOverlayPermission by remember { mutableStateOf(FloatingLive2DOverlayService.canDrawOverlays(appContext)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasOverlayPermission = FloatingLive2DOverlayService.canDrawOverlays(appContext)
        onRefreshSettings()
        FloatingLive2DOverlayService.sync(appContext)
    }

    fun requestPermission() {
        permissionLauncher.launch(FloatingLive2DOverlayService.permissionIntent(context))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(I18n.t("settings_floating_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    I18n.t("settings_floating_desc"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!hasOverlayPermission) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(I18n.t("settings_floating_permission"), fontWeight = FontWeight.SemiBold)
                        Text(
                            I18n.t("settings_floating_permission_desc"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(onClick = ::requestPermission) { Text(I18n.t("settings_floating_auth")) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_floating_enable"), fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !hasOverlayPermission -> I18n.t("settings_floating_no_permission")
                            settings.items.isEmpty() -> I18n.t("settings_floating_no_items")
                            settings.enabled -> I18n.t("settings_floating_count", settings.items.size)
                            else -> I18n.t("settings_floating_enable_desc")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    enabled = hasOverlayPermission,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(enabled = enabled))
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_floating_lock"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.locked) I18n.t("settings_floating_lock_on") else I18n.t("settings_floating_lock_off"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.locked,
                    onCheckedChange = { locked -> onSettingsChanged(settings.copy(locked = locked)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_floating_touch"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (settings.touchThrough) {
                            I18n.t("settings_floating_touch_on")
                        } else {
                            I18n.t("settings_floating_touch_off")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.touchThrough,
                    onCheckedChange = { touchThrough ->
                        onSettingsChanged(settings.copy(touchThrough = touchThrough))
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_floating_add"), fontWeight = FontWeight.SemiBold)
                    Text(
                        selectedModel?.title ?: I18n.t("settings_floating_no_model"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    enabled = selectedModel != null,
                    onClick = {
                        val model = selectedModel ?: return@Button
                        val next = addFloatingLive2DItem(appContext, model)
                        onSettingsChanged(next)
                    },
                ) {
                    Text(I18n.t("settings_floating_add_btn"))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(I18n.t("settings_floating_reset"), fontWeight = FontWeight.SemiBold)
                    Text(
                        I18n.t("settings_floating_reset_desc"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    enabled = settings.items.isNotEmpty(),
                    onClick = {
                        val next = resetFloatingLive2DItemPositions(appContext)
                        onSettingsChanged(next)
                    },
                ) {
                    Text(I18n.t("settings_floating_reset_btn"))
                }
            }
            if (settings.items.isEmpty()) {
                Text(
                    I18n.t("settings_floating_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.items.forEachIndexed { index, item ->
                        FloatingOverlayItemRow(
                            index = index,
                            item = item,
                            onRemove = {
                                val next = removeFloatingLive2DItem(appContext, item.id)
                                onSettingsChanged(next)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingOverlayItemRow(
    index: Int,
    item: FloatingLive2DItem,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "${index + 1}. ${item.model.title}",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = I18n.t("settings_floating_pos", item.x, item.y, item.width, item.height),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRemove) { Text(I18n.t("settings_floating_remove")) }
        }
    }
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
                Text(I18n.t("settings_wallpaper_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    I18n.t("settings_wallpaper_desc"),
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
                    Text(I18n.t("settings_wallpaper_enable"), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) I18n.t("settings_wallpaper_enable_on") else I18n.t("settings_wallpaper_enable_off"),
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
                    Text(I18n.t("settings_wallpaper_adjust"), fontWeight = FontWeight.SemiBold)
                    Text(
                        I18n.t("settings_wallpaper_adjust_desc"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = onAdjustPosition) {
                    Text(I18n.t("settings_wallpaper_adjust_btn"))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(I18n.t("settings_wallpaper_bg_label"), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (backgroundUri == null) I18n.t("settings_wallpaper_bg_default") else I18n.t("settings_wallpaper_bg_selected"),
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
                        Text(if (backgroundUri == null) I18n.t("settings_select") else I18n.t("settings_change"))
                    }
                }
                if (backgroundUri != null) {
                    TextButton(onClick = { onBackgroundChanged(null) }) {
                        Text(I18n.t("settings_clear_bg"))
                    }
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

private fun darkModeLabel(mode: DarkModeSetting): String = when (mode) {
    DarkModeSetting.On -> I18n.t("settings_dark_mode_on")
    DarkModeSetting.Off -> I18n.t("settings_dark_mode_off")
    DarkModeSetting.System -> I18n.t("settings_dark_mode_system")
}

private fun darkModeDescription(mode: DarkModeSetting): String = when (mode) {
    DarkModeSetting.On -> I18n.t("settings_dark_mode_on_desc")
    DarkModeSetting.Off -> I18n.t("settings_dark_mode_off_desc")
    DarkModeSetting.System -> I18n.t("settings_dark_mode_system_desc")
}

private fun renderResolutionLabel(resolution: RenderResolution): String = when (resolution) {
    RenderResolution.SuperSampling -> I18n.t("settings_render_resolution_x2")
    RenderResolution.PointToPoint -> I18n.t("settings_render_resolution_point_to_point")
    RenderResolution.TwoThirds -> I18n.t("settings_render_resolution_two_thirds")
    RenderResolution.Half -> I18n.t("settings_render_resolution_half")
}

private fun openLiveWallpaperPicker(context: android.content.Context) {
    val component = ComponentName(context, Live2DWallpaperService::class.java)
    val changeIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
    runCatching { context.startActivity(changeIntent) }
        .recoverCatching { context.startActivity(fallbackIntent) }
}
