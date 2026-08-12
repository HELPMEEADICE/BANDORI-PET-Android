package com.bandori.pet.ui.live2d

import android.graphics.Rect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bandori.pet.I18n
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.companion.CompanionConnectionState
import com.bandori.pet.llm.ChatBackendMode
import com.bandori.pet.llm.ChatConversationSummary
import com.bandori.pet.llm.ChatMessage
import com.bandori.pet.llm.ChatUiState
import com.bandori.pet.llm.Live2DChatViewModel
import com.bandori.pet.llm.LlmSettings
import java.text.DateFormat
import java.util.Date

@Composable
fun Live2DChatOverlay(
    model: ModelChoice,
    viewModel: Live2DChatViewModel,
    expanded: Boolean,
    launcherVisible: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val focusManager = LocalFocusManager.current
    val settings = remember { LlmSettings.load(context.applicationContext) }
    val input = remember(model.characterId) { mutableStateOf("") }
    var showingHistory by remember(model.characterId) { mutableStateOf(false) }
    var pendingDelete by remember(model.characterId) { mutableStateOf<ChatConversationSummary?>(null) }
    var overlayBottomOnScreenPx by remember { mutableStateOf(0f) }

    LaunchedEffect(model.characterId, expanded) { viewModel.selectCharacter(model, force = expanded) }
    LaunchedEffect(expanded) {
        if (!expanded) focusManager.clearFocus()
    }
    LaunchedEffect(showingHistory) {
        if (showingHistory) focusManager.clearFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayBottomOnScreenPx = coordinates.positionOnScreen().y + coordinates.size.height
            },
    ) {
        val density = LocalDensity.current
        val imeHeightPx = WindowInsets.ime.getBottom(density)
        val visibleWindowFrame = Rect().also(hostView::getWindowVisibleDisplayFrame)
        val imeOverlap = with(density) {
            calculateImeOverlapPx(
                containerBottomOnScreenPx = overlayBottomOnScreenPx,
                imeTopOnScreenPx = visibleWindowFrame.bottom,
                imeHeightPx = imeHeightPx,
            ).toDp()
        }
        // Keep the compact input-only design whenever the keyboard is visible.
        val compactForIme = imeHeightPx > 0

        val transition = updateTransition(targetState = expanded, label = "chatContainerMorph")
        val morphProgress by transition.animateFloat(
            transitionSpec = { tween(durationMillis = 360, easing = FastOutSlowInEasing) },
            label = "chatContainerMorphProgress",
        ) { isExpanded -> if (isExpanded) 1f else 0f }
        val panelContentAlpha = ((morphProgress - 0.28f) / 0.52f).coerceIn(0f, 1f)
        val launcherContentAlpha = (1f - morphProgress / 0.34f).coerceIn(0f, 1f)
        val dismissInteractionSource = remember { MutableInteractionSource() }
        val panelInteractionSource = remember { MutableInteractionSource() }

        if (transition.currentState || transition.targetState) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f * morphProgress))
                    .clickable(
                        interactionSource = dismissInteractionSource,
                        indication = null,
                    ) { onExpandedChange(false) },
            )
        }

        AnimatedVisibility(
            visible = expanded || launcherVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val launcherSize = 52.dp
                val panelWidth = minOf(maxWidth * 0.92f, 560.dp)
                val panelHeight = if (compactForIme) 80.dp else minOf(maxHeight * 0.60f, 620.dp)
                val containerWidth = launcherSize + (panelWidth - launcherSize) * morphProgress
                val containerHeight = launcherSize + (panelHeight - launcherSize) * morphProgress
                val bottomPadding = 18.dp + ((16.dp + imeOverlap) - 18.dp) * morphProgress
                val cornerRadius = 26.dp + (28.dp - 26.dp) * morphProgress
                val launcherColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
                val panelColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.98f)

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding)
                        .size(containerWidth, containerHeight)
                        .clickable(
                            interactionSource = panelInteractionSource,
                            indication = null,
                        ) {
                            if (!expanded && !transition.isRunning) onExpandedChange(true)
                        },
                    shape = RoundedCornerShape(cornerRadius),
                    color = lerp(launcherColor, panelColor, morphProgress),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 6.dp + 4.dp * morphProgress,
                    shadowElevation = 6.dp + 4.dp * morphProgress,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!transition.currentState || !transition.targetState) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = I18n.t("chat_open", model.characterName),
                                modifier = Modifier.graphicsLayer { alpha = launcherContentAlpha },
                            )
                        }

                        if (transition.currentState || transition.targetState) {
                            ChatPanelContent(
                                model = model,
                                viewModel = viewModel,
                                settings = settings,
                                compactForIme = compactForIme,
                                input = input,
                                showingHistory = showingHistory,
                                onHistoryRequest = { showingHistory = true },
                                onHistoryBack = { showingHistory = false },
                                onNewConversation = {
                                    input.value = ""
                                    viewModel.startNewConversation(model.characterId)
                                    showingHistory = false
                                },
                                onConversationSelected = { conversationId ->
                                    input.value = ""
                                    viewModel.selectConversation(model.characterId, conversationId)
                                    showingHistory = false
                                },
                                onDeleteRequest = { pendingDelete = it },
                                onCollapse = { onExpandedChange(false) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = panelContentAlpha }
                                    .padding(if (compactForIme) 12.dp else 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(I18n.t("chat_delete_conversation")) },
            text = {
                Text(
                    I18n.t(
                        "chat_delete_conversation_confirm",
                        conversation.title.ifBlank { I18n.t("chat_new_conversation") },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(model.characterId, conversation.id)
                    pendingDelete = null
                }) { Text(I18n.t("confirm")) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(I18n.t("cancel")) } },
        )
    }
}

@Composable
private fun ChatPanelContent(
    model: ModelChoice,
    viewModel: Live2DChatViewModel,
    settings: LlmSettings,
    compactForIme: Boolean,
    input: MutableState<String>,
    showingHistory: Boolean,
    onHistoryRequest: () -> Unit,
    onHistoryBack: () -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onDeleteRequest: (ChatConversationSummary) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (showingHistory) {
        ChatHistoryPanel(
            state = state,
            onBack = onHistoryBack,
            onNewConversation = onNewConversation,
            onConversationSelected = onConversationSelected,
            onDeleteRequest = onDeleteRequest,
            modifier = modifier,
        )
        return
    }

    Column(modifier) {
        if (!compactForIme) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.backendMode == ChatBackendMode.Desktop) state.characterId ?: "桌面互联" else model.characterName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.backendMode == ChatBackendMode.Desktop) {
                            "桌面模式 · ${companionConnectionText(state.companionConnection)}"
                        } else {
                            "本机模式 · ${settings.model.ifBlank { I18n.t("chat_not_configured_short") }}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onHistoryRequest) {
                    Icon(Icons.Outlined.History, contentDescription = I18n.t("chat_history"))
                }
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = I18n.t("chat_minimize"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { viewModel.setBackendMode(ChatBackendMode.Local) },
                    enabled = state.backendMode != ChatBackendMode.Local,
                ) { Text("本机") }
                TextButton(
                    onClick = { viewModel.setBackendMode(ChatBackendMode.Desktop) },
                    enabled = state.backendMode != ChatBackendMode.Desktop,
                ) { Text("桌面") }
                if (state.backendMode == ChatBackendMode.Desktop && state.companionConnection != CompanionConnectionState.Connected) {
                    TextButton(onClick = viewModel::reconnectCompanion) { Text("重新连接") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.isHistoryLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.backendMode == ChatBackendMode.Local && !settings.isConfigured) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    I18n.t("chat_not_configured"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (!compactForIme) {
                if (state.backendMode == ChatBackendMode.Desktop && (
                        state.companionConnection != CompanionConnectionState.Connected ||
                            state.companionMode != "private" ||
                            !state.companionCapabilities.remoteChat
                    )) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            companionUnavailableText(state),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                ChatMessageList(
                    messages = state.messages,
                    streamingText = state.streamingText,
                    thinking = state.isThinking,
                    onReplay = if (state.backendMode == ChatBackendMode.Desktop && state.companionCapabilities.tts) viewModel::replayTts else null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                state.error?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chatErrorText(error),
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (isRetryableChatError(error)) {
                                IconButton(onClick = { viewModel.retry(model) }) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = I18n.t("chat_retry"))
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input.value,
                    onValueChange = { input.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(I18n.t("chat_input_hint")) },
                    maxLines = if (compactForIme) 1 else 4,
                    enabled = !state.isGenerating && companionCanSend(state),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val message = input.value
                        if (message.isNotBlank()) {
                            if (viewModel.send(model, message)) input.value = ""
                        }
                    }),
                    shape = RoundedCornerShape(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val message = input.value
                        if (state.isGenerating) {
                            viewModel.stop()
                        } else if (message.isNotBlank()) {
                            if (viewModel.send(model, message)) input.value = ""
                        }
                    },
                    enabled = state.isGenerating || (input.value.isNotBlank() && companionCanSend(state)),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (state.isGenerating) Icons.Outlined.Stop else Icons.Outlined.Send,
                        contentDescription = I18n.t(if (state.isGenerating) "chat_stop" else "chat_send"),
                    )
                }
            }
        }
    }
}

private fun companionCanSend(state: ChatUiState): Boolean =
    state.backendMode == ChatBackendMode.Local || (
        state.companionConnection == CompanionConnectionState.Connected &&
            state.companionMode == "private" &&
            state.companionCapabilities.remoteChat
        )

private fun companionConnectionText(state: CompanionConnectionState): String = when (state) {
    CompanionConnectionState.Disconnected -> "已断开"
    CompanionConnectionState.Connecting -> "连接中"
    CompanionConnectionState.Connected -> "已连接"
    CompanionConnectionState.ProfileUnavailable -> "档案不可用"
    CompanionConnectionState.Error -> "连接失败"
}

private fun companionUnavailableText(state: ChatUiState): String = when {
    state.companionConnection == CompanionConnectionState.ProfileUnavailable || state.companionMode == "profile_unavailable" ->
        "桌面已切换到其他用户档案，当前设备无权读取。"
    state.companionMode == "group_unavailable" -> "桌面正在使用不受支持的群聊。请选择一个私聊继续。"
    state.companionConnection != CompanionConnectionState.Connected -> "桌面连接已断开，远程消息已从手机内存清除。"
    !state.companionCapabilities.remoteChat -> "桌面 LLM 未配置或当前不可用；仍可查看已同步的私聊记录。"
    else -> "桌面互联当前不可用。"
}

@Composable
private fun ChatHistoryPanel(
    state: ChatUiState,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onDeleteRequest: (ChatConversationSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = I18n.t("back"))
            }
            Text(
                I18n.t("chat_history"),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onNewConversation) {
                Icon(Icons.Outlined.AddComment, contentDescription = I18n.t("chat_new_conversation"))
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.isHistoryLoading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.conversations.isEmpty() -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    I18n.t("chat_history_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = state.conversations,
                    key = { it.id },
                    contentType = { "conversation" },
                ) { conversation ->
                    val selected = conversation.id == state.conversationId
                    Surface(
                        onClick = { onConversationSelected(conversation.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conversation.title.ifBlank { I18n.t("chat_new_conversation") },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    conversation.preview.ifBlank { I18n.t("chat_empty_conversation") },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    formatConversationTimestamp(conversation.updatedAt),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = I18n.t("chat_current_conversation"),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { onDeleteRequest(conversation) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = I18n.t("chat_delete_conversation"),
                                )
                            }
                        }
                    }
                }
            }
        }
        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                chatErrorText(error),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatConversationTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

private fun chatErrorText(error: String): String = when (error) {
    Live2DChatViewModel.ERROR_LLM_NOT_CONFIGURED -> I18n.t("chat_not_configured")
    Live2DChatViewModel.ERROR_HISTORY_LOAD -> I18n.t("chat_history_load_failed")
    Live2DChatViewModel.ERROR_HISTORY_SAVE -> I18n.t("chat_history_save_failed")
    Live2DChatViewModel.ERROR_HISTORY_DELETE -> I18n.t("chat_history_delete_failed")
    else -> error
}

private fun isRetryableChatError(error: String): Boolean = error !in setOf(
    Live2DChatViewModel.ERROR_LLM_NOT_CONFIGURED,
    Live2DChatViewModel.ERROR_HISTORY_LOAD,
    Live2DChatViewModel.ERROR_HISTORY_SAVE,
    Live2DChatViewModel.ERROR_HISTORY_DELETE,
)

private fun calculateImeOverlapPx(
    containerBottomOnScreenPx: Float,
    imeTopOnScreenPx: Int,
    imeHeightPx: Int,
): Float {
    if (imeHeightPx <= 0 || containerBottomOnScreenPx <= 0f) return 0f
    return (containerBottomOnScreenPx - imeTopOnScreenPx).coerceIn(0f, imeHeightPx.toFloat())
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    thinking: Boolean,
    onReplay: ((ChatMessage) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (streamingText.isNotBlank() || thinking) 1 else 0
    val streamScrollBucket = streamingText.length / 24
    var previousItemCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(itemCount, streamScrollBucket) {
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val wasNearBottom = shouldFollowNewChatContent(previousItemCount, lastVisibleIndex)
        previousItemCount = itemCount
        if (itemCount > 0 && wasNearBottom) listState.scrollToItem(itemCount - 1)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = messages,
            key = { it.id },
            contentType = { "message" },
        ) { message ->
            val replay = if (onReplay != null && message.role == "assistant") {
                { onReplay(message) }
            } else {
                null
            }
            ChatBubble(message.role, message.content, onReplay = replay)
        }
        if (streamingText.isNotBlank() || thinking) {
            item(key = "streaming", contentType = "message") {
                ChatBubble("assistant", streamingText.ifBlank { I18n.t("chat_thinking") }, thinking)
            }
        }
    }
}

internal fun shouldFollowNewChatContent(previousItemCount: Int, lastVisibleIndex: Int): Boolean =
    previousItemCount <= 0 || lastVisibleIndex < 0 || lastVisibleIndex >= previousItemCount - 2

@Composable
private fun ChatBubble(role: String, content: String, thinking: Boolean = false, onReplay: (() -> Unit)? = null) {
    val fromUser = role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (fromUser) 20.dp else 6.dp,
                bottomEnd = if (fromUser) 6.dp else 20.dp,
            ),
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thinking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(content, style = MaterialTheme.typography.bodyMedium)
                if (onReplay != null) {
                    TextButton(onClick = onReplay) { Text("朗读") }
                }
            }
        }
    }
}
