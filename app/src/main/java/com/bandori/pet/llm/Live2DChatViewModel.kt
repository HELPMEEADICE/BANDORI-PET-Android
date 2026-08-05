package com.bandori.pet.llm

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandori.pet.data.ModelChoice
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ChatUiState(
    val characterId: String? = null,
    val conversationId: String? = null,
    val conversationTitle: String = "",
    val conversations: List<ChatConversationSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val isThinking: Boolean = false,
    val isHistoryLoading: Boolean = false,
    val error: String? = null,
)

class Live2DChatViewModel(application: Application) : AndroidViewModel(application) {
    private val history = ChatHistoryRepository(application)
    private val prompts = CharacterPromptRepository(application)
    private val client = LlmChatClient()
    private val mutableState = MutableStateFlow(ChatUiState())
    private val mutableActions = Channel<String>(Channel.BUFFERED)
    private var requestJob: Job? = null
    private var transitionJob: Job? = null
    private var lastFailedRequest: FailedRequest? = null

    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    val actions: Flow<String> = mutableActions.receiveAsFlow()

    fun selectCharacter(model: ModelChoice, force: Boolean = false) {
        val current = mutableState.value
        if (current.characterId == model.characterId && (!force || current.isGenerating)) return
        mutableState.value = if (current.characterId == model.characterId) {
            current.copy(isHistoryLoading = true, error = null)
        } else {
            ChatUiState(characterId = model.characterId, isHistoryLoading = true)
        }
        launchTransition {
            stopRequestAndJoin()
            val snapshot = runCatching {
                withContext(Dispatchers.IO) { history.loadSnapshot(model.characterId) }
            }.getOrElse {
                mutableState.value = ChatUiState(characterId = model.characterId, error = ERROR_HISTORY_LOAD)
                return@launchTransition
            }
            applySnapshot(model.characterId, snapshot)
        }
    }

    fun startNewConversation(characterId: String) {
        if (mutableState.value.characterId != characterId) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val conversations = runCatching {
                withContext(Dispatchers.IO) { history.listConversations(characterId) }
            }.getOrElse {
                mutableState.value = mutableState.value.copy(isHistoryLoading = false, error = ERROR_HISTORY_LOAD)
                return@launchTransition
            }
            lastFailedRequest = null
            mutableState.value = ChatStateTransitions.newDraft(characterId, conversations)
        }
    }

    fun selectConversation(characterId: String, conversationId: String) {
        if (mutableState.value.characterId != characterId || mutableState.value.conversationId == conversationId) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val conversation = history.loadConversation(characterId, conversationId)
                        ?: error("Conversation not found")
                    history.setActiveConversation(characterId, conversationId)
                    conversation to history.listConversations(characterId)
                }
            }.getOrElse {
                mutableState.value = mutableState.value.copy(isHistoryLoading = false, error = ERROR_HISTORY_LOAD)
                return@launchTransition
            }
            lastFailedRequest = null
            val (conversation, conversations) = result
            mutableState.value = ChatStateTransitions.fromConversation(conversation, conversations)
        }
    }

    fun deleteConversation(characterId: String, conversationId: String) {
        if (mutableState.value.characterId != characterId) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val wasActive = mutableState.value.conversationId == conversationId
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    check(history.deleteConversation(characterId, conversationId))
                    val conversations = history.listConversations(characterId)
                    val replacement = if (wasActive) {
                        conversations.firstOrNull()?.let { history.loadConversation(characterId, it.id) }
                    } else {
                        null
                    }
                    if (wasActive) history.setActiveConversation(characterId, replacement?.id)
                    conversations to replacement
                }
            }.getOrElse {
                mutableState.value = mutableState.value.copy(isHistoryLoading = false, error = ERROR_HISTORY_DELETE)
                return@launchTransition
            }
            val (conversations, replacement) = result
            lastFailedRequest = null
            mutableState.value = if (wasActive) {
                ChatStateTransitions.afterActiveDelete(characterId, conversations, replacement)
            } else {
                mutableState.value.copy(conversations = conversations, isHistoryLoading = false)
            }
        }
    }

    fun send(model: ModelChoice, input: String) {
        val text = input.trim()
        val current = mutableState.value
        if (
            text.isEmpty() ||
            requestJob?.isActive == true ||
            current.isHistoryLoading ||
            current.characterId != model.characterId
        ) return
        startRequest(model, text, appendUser = true)
    }

    fun retry(model: ModelChoice) {
        val failed = lastFailedRequest ?: return
        val current = mutableState.value
        if (
            requestJob?.isActive == true ||
            current.isHistoryLoading ||
            failed.characterId != model.characterId ||
            current.characterId != failed.characterId ||
            current.conversationId != failed.conversationId
        ) return
        startRequest(model, failed.input, appendUser = false)
    }

    fun stop() {
        requestJob?.cancel()
    }

    fun clearAll() {
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val cleared = runCatching { withContext(Dispatchers.IO) { history.clearAll() } }.isSuccess
            val characterId = mutableState.value.characterId
            lastFailedRequest = null
            mutableState.value = ChatUiState(
                characterId = characterId,
                error = if (cleared) null else ERROR_HISTORY_DELETE,
            )
        }
    }

    private fun startRequest(model: ModelChoice, input: String, appendUser: Boolean) {
        requestJob = viewModelScope.launch {
            val current = mutableState.value
            if (current.characterId != model.characterId) return@launch

            val now = System.currentTimeMillis()
            val conversationId = current.conversationId ?: UUID.randomUUID().toString()
            val existingSummary = current.conversations.firstOrNull { it.id == conversationId }
            val createdAt = existingSummary?.createdAt ?: now
            val title = current.conversationTitle.ifBlank {
                if (appendUser) ChatHistoryRepository.titleFromFirstMessage(input) else existingSummary?.title.orEmpty()
            }
            val messages = if (appendUser) {
                (current.messages + newMessage("user", input)).takeLast(ChatHistoryRepository.MAX_STORED_MESSAGES)
            } else {
                current.messages
            }
            val requestContext = RequestContext(
                characterId = model.characterId,
                conversationId = conversationId,
                title = title,
                createdAt = createdAt,
                updatedAt = now,
                messages = messages,
            )

            val conversations = runCatching {
                withContext(Dispatchers.IO) {
                    history.saveConversation(requestContext.toConversation(messages))
                    history.setActiveConversation(model.characterId, conversationId)
                    history.listConversations(model.characterId)
                }
            }.getOrElse {
                mutableState.value = current.copy(
                    conversationId = conversationId,
                    conversationTitle = title,
                    messages = messages,
                    error = ERROR_HISTORY_SAVE,
                )
                return@launch
            }

            mutableState.value = current.copy(
                conversationId = conversationId,
                conversationTitle = title,
                conversations = conversations,
                messages = messages,
                streamingText = "",
                isThinking = false,
                error = null,
            )

            val settings = LlmSettings.load(getApplication())
            if (!settings.isConfigured) {
                mutableState.value = mutableState.value.copy(error = ERROR_LLM_NOT_CONFIGURED)
                return@launch
            }
            val characterPrompt = withContext(Dispatchers.IO) { prompts.buildSystemPrompt(model) }
            val parser = ActionTagParser(characterPrompt.allowedActionTags)
            mutableState.value = mutableState.value.copy(isGenerating = true)
            lastFailedRequest = null
            try {
                client.streamCompletion(settings, settings.systemPromptWithCustom(characterPrompt.text), messages).collect { event ->
                    if (!isActive(requestContext)) return@collect
                    when (event) {
                        is LlmStreamEvent.Content -> mutableState.value = mutableState.value.copy(
                            streamingText = parser.consume(event.text),
                            isThinking = false,
                        )
                        LlmStreamEvent.ReasoningStarted -> mutableState.value = mutableState.value.copy(isThinking = true)
                    }
                }
                finalizeAssistant(requestContext, parser)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finalizeAssistant(requestContext, parser) }
                throw cancelled
            } catch (error: Throwable) {
                if (!currentCoroutineContext().isActive) {
                    withContext(NonCancellable) { finalizeAssistant(requestContext, parser) }
                    return@launch
                }
                lastFailedRequest = FailedRequest(
                    characterId = requestContext.characterId,
                    conversationId = requestContext.conversationId,
                    input = input,
                )
                if (isActive(requestContext)) {
                    mutableState.value = mutableState.value.copy(
                        isGenerating = false,
                        isThinking = false,
                        streamingText = "",
                        error = error.message ?: error.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private suspend fun finalizeAssistant(request: RequestContext, parser: ActionTagParser) {
        val result = parser.finish()
        val finalMessages = if (result.text.isNotBlank()) {
            (request.messages + newMessage("assistant", result.text)).takeLast(ChatHistoryRepository.MAX_STORED_MESSAGES)
        } else {
            request.messages
        }
        val updatedRequest = request.copy(
            updatedAt = if (result.text.isNotBlank()) System.currentTimeMillis() else request.updatedAt,
        )
        val conversations = runCatching {
            withContext(Dispatchers.IO) {
                history.saveConversation(updatedRequest.toConversation(finalMessages))
                history.listConversations(request.characterId)
            }
        }.getOrElse {
            if (isActive(request)) {
                mutableState.value = mutableState.value.copy(
                    messages = finalMessages,
                    streamingText = "",
                    isGenerating = false,
                    isThinking = false,
                    error = ERROR_HISTORY_SAVE,
                )
            }
            return
        }
        if (!isActive(request)) return
        result.action?.let { mutableActions.send(it) }
        if (lastFailedRequest?.conversationId == request.conversationId) lastFailedRequest = null
        mutableState.value = mutableState.value.copy(
            conversationId = request.conversationId,
            conversationTitle = request.title,
            conversations = conversations,
            messages = finalMessages,
            streamingText = "",
            isGenerating = false,
            isThinking = false,
            error = null,
        )
    }

    private fun applySnapshot(characterId: String, snapshot: ChatHistorySnapshot) {
        lastFailedRequest = null
        mutableState.value = ChatStateTransitions.fromSnapshot(characterId, snapshot)
    }

    private fun launchTransition(block: suspend () -> Unit) {
        transitionJob?.cancel()
        transitionJob = viewModelScope.launch { block() }
    }

    private suspend fun stopRequestAndJoin() {
        requestJob?.takeIf { it.isActive }?.cancelAndJoin()
    }

    private fun isActive(request: RequestContext): Boolean = ChatStateTransitions.matchesConversation(
        mutableState.value,
        request.characterId,
        request.conversationId,
    )

    private fun newMessage(role: String, content: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = role,
        content = content,
        timestamp = System.currentTimeMillis(),
    )

    private data class FailedRequest(
        val characterId: String,
        val conversationId: String,
        val input: String,
    )

    private data class RequestContext(
        val characterId: String,
        val conversationId: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ChatMessage>,
    ) {
        fun toConversation(messages: List<ChatMessage>): ChatConversation = ChatConversation(
            id = conversationId,
            characterId = characterId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
        )
    }

    companion object {
        const val ERROR_LLM_NOT_CONFIGURED = "LLM_NOT_CONFIGURED"
        const val ERROR_HISTORY_LOAD = "CHAT_HISTORY_LOAD_FAILED"
        const val ERROR_HISTORY_SAVE = "CHAT_HISTORY_SAVE_FAILED"
        const val ERROR_HISTORY_DELETE = "CHAT_HISTORY_DELETE_FAILED"
    }
}

internal object ChatStateTransitions {
    fun fromSnapshot(characterId: String, snapshot: ChatHistorySnapshot): ChatUiState =
        snapshot.activeConversation?.let { fromConversation(it, snapshot.conversations) }
            ?: newDraft(characterId, snapshot.conversations)

    fun fromConversation(
        conversation: ChatConversation,
        conversations: List<ChatConversationSummary>,
    ): ChatUiState = ChatUiState(
        characterId = conversation.characterId,
        conversationId = conversation.id,
        conversationTitle = conversation.title,
        conversations = conversations,
        messages = conversation.messages,
    )

    fun newDraft(
        characterId: String,
        conversations: List<ChatConversationSummary>,
    ): ChatUiState = ChatUiState(
        characterId = characterId,
        conversations = conversations,
    )

    fun afterActiveDelete(
        characterId: String,
        conversations: List<ChatConversationSummary>,
        replacement: ChatConversation?,
    ): ChatUiState = replacement?.let { fromConversation(it, conversations) }
        ?: newDraft(characterId, conversations)

    fun matchesConversation(state: ChatUiState, characterId: String, conversationId: String): Boolean =
        state.characterId == characterId && state.conversationId == conversationId
}
