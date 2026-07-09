package com.bandori.pet.ui.model

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bandori.pet.I18n
import com.bandori.pet.ModelTransferState
import com.bandori.pet.data.AppData
import com.bandori.pet.data.Band
import com.bandori.pet.data.CharacterInfo
import com.bandori.pet.data.DataRepository
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.data.ZstModelArchive
import com.bandori.pet.ui.formatTransferProgress
import com.bandori.pet.ui.formatTransferSpeed
import com.bandori.pet.ui.isMoc3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModelScreen(
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
            actionLabel = I18n.t("model_updating"),
            successMessage = I18n.t("model_update_done"),
            failureMessage = I18n.t("model_update_failed"),
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
            title = I18n.t("model_select_band"),
            subtitle = I18n.t("model_select_band_subtitle"),
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
            title = I18n.t("model_select_character"),
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
                                    text = I18n.t("model_character_models"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(I18n.t("model_update_model")) },
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
                                text = { Text(I18n.t("model_delete_model")) },
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
            title = I18n.t("model_select_costume"),
            subtitle = selectedCharacter?.display?.let { I18n.t("model_select_costume_subtitle", it) } ?: I18n.t("model_current_character"),
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
                                    actionLabel = I18n.t("model_downloading"),
                                    successMessage = I18n.t("model_download_done"),
                                    failureMessage = I18n.t("model_download_failed"),
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
            Text(if (transferring) "${transfer?.actionLabel}..." else I18n.t("model_download_btn", character?.display ?: I18n.t("model_current_character")))
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
fun ModelTransferProgress(
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
                    text = I18n.t("model_progress_header", transfer.actionLabel, transfer.characterName),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = progress?.let { formatTransferSpeed(it.bytesPerSecond) } ?: I18n.t("model_connecting"),
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
                text = progress?.let { formatTransferProgress(it) } ?: I18n.t("model_waiting"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
fun ImageCard(
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
fun TextCard(
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
fun AssetImage(path: String?, reloadKey: Int = 0, modifier: Modifier, contentScale: ContentScale, placeholderText: String? = null) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var bitmap by remember(path, reloadKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, reloadKey) {
        bitmap = path?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    appContext.assets.open(it).use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
                }.getOrNull() ?: ZstModelArchive.readLogicalPath(appContext, it)
                    ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap = bitmap!!, contentDescription = null, modifier = modifier, contentScale = contentScale)
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
