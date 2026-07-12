package com.bandori.pet.ui.live2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bandori.pet.I18n
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.llm.ChatMessage
import com.bandori.pet.llm.Live2DChatViewModel
import com.bandori.pet.llm.LlmSettings

@Composable
fun Live2DChatOverlay(
    model: ModelChoice,
    viewModel: Live2DChatViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember(expanded) { LlmSettings.load(context.applicationContext) }
    var input by remember(model.characterId) { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(model.characterId, expanded) { viewModel.selectCharacter(model, force = expanded) }

    Box(modifier = modifier.fillMaxSize()) {
        if (!expanded) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .width(240.dp)
                    .height(52.dp)
                    .clickable { onExpandedChange(true) },
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(I18n.t("chat_open", model.characterName), fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .clickable { onExpandedChange(false) },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.60f)
                    .widthIn(max = 560.dp)
                    .heightIn(max = 620.dp)
                    .imePadding()
                    .clickable {},
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
                tonalElevation = 12.dp,
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.characterName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                settings.model.ifBlank { I18n.t("chat_not_configured_short") },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = I18n.t("chat_clear"))
                        }
                        IconButton(onClick = { onExpandedChange(false) }) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = I18n.t("chat_minimize"))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!settings.isConfigured) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                I18n.t("chat_not_configured"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        ChatMessageList(
                            messages = state.messages,
                            streamingText = state.streamingText,
                            thinking = state.isThinking,
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
                                        if (error == "LLM_NOT_CONFIGURED") I18n.t("chat_not_configured") else error,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    IconButton(onClick = { viewModel.retry(model) }) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = I18n.t("chat_retry"))
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(I18n.t("chat_input_hint")) },
                                maxLines = 4,
                                enabled = !state.isGenerating,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    if (input.isNotBlank()) {
                                        viewModel.send(model, input)
                                        input = ""
                                    }
                                }),
                                shape = RoundedCornerShape(22.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = {
                                    if (state.isGenerating) {
                                        viewModel.stop()
                                    } else if (input.isNotBlank()) {
                                        viewModel.send(model, input)
                                        input = ""
                                    }
                                },
                                enabled = state.isGenerating || input.isNotBlank(),
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
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(I18n.t("chat_clear")) },
            text = { Text(I18n.t("chat_clear_confirm", model.characterName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCurrent(model.characterId)
                    confirmClear = false
                }) { Text(I18n.t("confirm")) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(I18n.t("cancel")) } },
        )
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    thinking: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (streamingText.isNotBlank() || thinking) 1 else 0
    LaunchedEffect(itemCount, streamingText.length) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message -> ChatBubble(message.role, message.content) }
        if (streamingText.isNotBlank() || thinking) {
            item(key = "streaming") {
                ChatBubble("assistant", streamingText.ifBlank { I18n.t("chat_thinking") }, thinking)
            }
        }
    }
}

@Composable
private fun ChatBubble(role: String, content: String, thinking: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (role == "user") Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            shape = RoundedCornerShape(18.dp),
            color = if (role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thinking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
