package com.bandori.pet.llm

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandori.pet.companion.CompanionConnectionState
import com.bandori.pet.companion.CompanionSettings
import com.bandori.pet.data.ModelChoice
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ChatBackendMode { Local, Desktop }

@Immutable
data class CompanionCapabilities(
    val privateHistory: Boolean = false,
    val remoteChat: Boolean = false,
    val tts: Boolean = false,
    val actions: Boolean = false,
    val llmStatus: String = "unconfigured",
    val ttsStatus: String = "unconfigured",
)

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
    val backendMode: ChatBackendMode = ChatBackendMode.Local,
    val companionConnection: CompanionConnectionState = CompanionConnectionState.Disconnected,
    val companionCapabilities: CompanionCapabilities = CompanionCapabilities(),
    val companionMode: String = "private",
)

class Live2DChatViewModel(application: Application) : AndroidViewModel(application) {
    private val localBackend = LocalChatBackend(application)
    private val desktopBackend = DesktopCompanionBackend.shared(application)
    private val history = localBackend.history
    private val prompts = localBackend.prompts
    private val client = localBackend.client
    private val companionClient = desktopBackend.client
    private val remoteTtsPlayer = desktopBackend.ttsPlayer
    private val mutableState = MutableStateFlow(ChatUiState())
    private val mutableActions = Channel<String>(Channel.BUFFERED)
    private var requestJob: Job? = null
    private var transitionJob: Job? = null
    private var companionHydrationJob: Job? = null
    private var lastFailedRequest: FailedRequest? = null
    private var selectedLocalModel: ModelChoice? = null

    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    val actions: Flow<String> = mutableActions.receiveAsFlow()
    val mouth: StateFlow<Pair<Float, Float>> = remoteTtsPlayer.mouth

    init {
        viewModelScope.launch {
            companionClient.state.collect { connection ->
                val current = mutableState.value
                if (current.backendMode != ChatBackendMode.Desktop) return@collect
                mutableState.value = if (connection == CompanionConnectionState.Disconnected || connection == CompanionConnectionState.Error) {
                    current.copy(
                        characterId = null,
                        conversationId = null,
                        conversationTitle = "",
                        conversations = emptyList(),
                        messages = emptyList(),
                        streamingText = "",
                        isGenerating = false,
                        isThinking = false,
                        companionConnection = connection,
                        companionCapabilities = CompanionCapabilities(),
                    )
                } else {
                    current.copy(companionConnection = connection)
                }
            }
        }
        viewModelScope.launch {
            companionClient.events.collect { (event, data) ->
                if (mutableState.value.backendMode == ChatBackendMode.Desktop) handleCompanionEvent(event, data)
            }
        }
        viewModelScope.launch {
            com.bandori.pet.companion.CompanionRuntimeStatus.forgetEvents.collect {
                if (mutableState.value.backendMode == ChatBackendMode.Desktop) setBackendMode(ChatBackendMode.Local)
            }
        }
        viewModelScope.launch {
            com.bandori.pet.companion.CompanionRuntimeStatus.reconnectEvents.collect {
                if (mutableState.value.backendMode == ChatBackendMode.Desktop) reconnectCompanion()
            }
        }
        viewModelScope.launch {
            com.bandori.pet.companion.CompanionRuntimeStatus.remoteEnabled.collect { enabled ->
                val desired = if (enabled) ChatBackendMode.Desktop else ChatBackendMode.Local
                if (mutableState.value.backendMode != desired) setBackendMode(desired)
            }
        }
        if (CompanionSettings.remoteMode(application) && CompanionSettings.load(application) != null) {
            setBackendMode(ChatBackendMode.Desktop)
        }
    }

    fun setBackendMode(mode: ChatBackendMode) {
        if (mutableState.value.backendMode == mode) return
        transitionJob?.cancel()
        companionHydrationJob?.cancel()
        requestJob?.cancel()
        remoteTtsPlayer.stop()
        if (mode == ChatBackendMode.Desktop) {
            CompanionSettings.setRemoteMode(getApplication(), true)
            com.bandori.pet.companion.CompanionRuntimeStatus.remoteEnabled.value = true
            mutableState.value = ChatUiState(
                backendMode = mode,
                companionConnection = CompanionConnectionState.Connecting,
                isHistoryLoading = true,
            )
            companionClient.connect()
        } else {
            CompanionSettings.setRemoteMode(getApplication(), false)
            com.bandori.pet.companion.CompanionRuntimeStatus.remoteEnabled.value = false
            companionClient.disconnect()
            mutableState.value = ChatUiState()
            selectedLocalModel?.let { selectCharacter(it, force = true) }
        }
    }

    fun reconnectCompanion() {
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) companionClient.connect(force = true)
    }

    fun selectCharacter(model: ModelChoice, force: Boolean = false) {
        selectedLocalModel = model
        val current = mutableState.value
        if (current.backendMode == ChatBackendMode.Desktop) {
            if (force) companionRequest("state.get")
            return
        }
        if (
            current.characterId == model.characterId &&
            (!force || current.isGenerating || current.isHistoryLoading)
        ) return
        mutableState.value = if (current.characterId == model.characterId) {
            current.copy(isHistoryLoading = true, error = null)
        } else {
            ChatUiState(characterId = model.characterId, isHistoryLoading = true)
        }
        launchTransition {
            stopRequestAndJoin()
            val snapshot = runIoCatching { history.loadSnapshot(model.characterId) }.getOrElse {
                mutableState.value = ChatUiState(characterId = model.characterId, error = ERROR_HISTORY_LOAD)
                return@launchTransition
            }
            applySnapshot(model.characterId, snapshot)
        }
    }

    fun startNewConversation(characterId: String) {
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) {
            companionRequest("chat.new", JSONObject().put("characterId", characterId))
            return
        }
        if (mutableState.value.characterId != characterId) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val conversations = runIoCatching { history.listConversations(characterId) }.getOrElse {
                mutableState.value = mutableState.value.copy(isHistoryLoading = false, error = ERROR_HISTORY_LOAD)
                return@launchTransition
            }
            lastFailedRequest = null
            mutableState.value = ChatStateTransitions.newDraft(characterId, conversations)
        }
    }

    fun selectConversation(characterId: String, conversationId: String) {
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) {
            companionRequest("chat.select", JSONObject().put("conversationId", conversationId))
            return
        }
        if (mutableState.value.characterId != characterId || mutableState.value.conversationId == conversationId) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val result = runIoCatching {
                val conversation = history.loadConversation(characterId, conversationId)
                    ?: error("Conversation not found")
                history.setActiveConversation(characterId, conversationId)
                conversation to history.listConversations(characterId)
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
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) {
            companionRequest("history.delete_conversation", JSONObject().put("conversationId", conversationId))
            return
        }
        if (mutableState.value.characterId != characterId) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val wasActive = mutableState.value.conversationId == conversationId
            val result = runIoCatching {
                check(history.deleteConversation(characterId, conversationId))
                val conversations = history.listConversations(characterId)
                val replacement = if (wasActive) {
                    conversations.firstOrNull()?.let { history.loadConversation(characterId, it.id) }
                } else {
                    null
                }
                if (wasActive) history.setActiveConversation(characterId, replacement?.id)
                conversations to replacement
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

    fun send(model: ModelChoice, input: String): Boolean {
        val text = input.trim()
        val current = mutableState.value
        if (current.backendMode == ChatBackendMode.Desktop) {
            if (text.isEmpty() || current.isGenerating || current.isHistoryLoading || !current.companionCapabilities.remoteChat) return false
            companionRequest("chat.send", JSONObject().put("text", text))
            return true
        }
        if (
            text.isEmpty() ||
            requestJob?.isActive == true ||
            current.isHistoryLoading ||
            current.characterId != model.characterId
        ) return false
        startRequest(model, text, appendUser = true)
        return true
    }

    fun retry(model: ModelChoice) {
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) {
            companionRequest("chat.retry")
            return
        }
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
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) {
            companionRequest("chat.stop")
            remoteTtsPlayer.stop()
            return
        }
        requestJob?.cancel()
    }

    fun replayTts(message: ChatMessage) {
        val current = mutableState.value
        if (
            current.backendMode != ChatBackendMode.Desktop ||
            !current.companionCapabilities.tts ||
            message.role != "assistant"
        ) return
        val conversationId = current.conversationId ?: return
        companionRequest(
            "tts.replay",
            JSONObject().put("conversationId", conversationId).put("messageId", message.id),
        )
    }

    fun clearAll() {
        if (mutableState.value.backendMode == ChatBackendMode.Desktop) return
        mutableState.value = mutableState.value.copy(isHistoryLoading = true, error = null)
        launchTransition {
            stopRequestAndJoin()
            val cleared = runIoCatching { history.clearAll() }.isSuccess
            val characterId = mutableState.value.characterId
            lastFailedRequest = null
            mutableState.value = ChatUiState(
                characterId = characterId,
                error = if (cleared) null else ERROR_HISTORY_DELETE,
            )
        }
    }

    private fun companionRequest(method: String, params: JSONObject = JSONObject()) {
        if (mutableState.value.backendMode != ChatBackendMode.Desktop) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(error = null)
            runCatching { companionClient.request(method, params) }
                .onSuccess { result ->
                    if (result.has("mode")) applyCompanionState(result)
                    else if (method == "state.get") applyCompanionState(result)
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(error = error.message ?: "COMPANION_REQUEST_FAILED")
                }
        }
    }

    private suspend fun handleCompanionEvent(event: String, data: JSONObject) {
        when (event) {
            "session.hello" -> {
                val capabilities = data.optJSONObject("capabilities") ?: JSONObject()
                mutableState.value = mutableState.value.copy(
                    companionCapabilities = parseCapabilities(capabilities),
                    companionConnection = if (data.optBoolean("profileAvailable", true)) {
                        CompanionConnectionState.Connected
                    } else {
                        CompanionConnectionState.ProfileUnavailable
                    },
                )
                data.optJSONObject("state")?.let {
                    applyCompanionState(it)
                    scheduleCompanionHydration()
                }
            }
            "capabilities.changed" -> mutableState.value = mutableState.value.copy(
                companionCapabilities = parseCapabilities(data),
            )
            "state.changed" -> {
                applyCompanionState(data)
                scheduleCompanionHydration()
            }
            "generation.delta" -> mutableState.value = mutableState.value.copy(
                streamingText = data.optString("text"),
                isGenerating = data.optBoolean("isGenerating"),
                isThinking = false,
            )
            "action.play" -> if (
                mutableState.value.companionCapabilities.actions &&
                data.optString("characterId") == mutableState.value.characterId
            ) {
                data.optString("action").takeIf(String::isNotBlank)?.let { mutableActions.send(it) }
            }
            "tts.started" -> Unit
            "tts.finished" -> Unit
            "tts.error" -> Unit
            "profile.changed" -> {
                mutableState.value = ChatUiState(
                    backendMode = ChatBackendMode.Desktop,
                    companionConnection = CompanionConnectionState.ProfileUnavailable,
                    companionMode = "profile_unavailable",
                )
            }
        }
    }

    private fun applyCompanionState(payload: JSONObject) {
        val current = mutableState.value
        if (current.backendMode != ChatBackendMode.Desktop) return
        val mode = payload.optString("mode", "private")
        if (mode != "private") {
            remoteTtsPlayer.stop()
            mutableState.value = current.copy(
                characterId = null,
                conversationId = null,
                conversationTitle = "",
                conversations = parseConversationSummaries(payload.optJSONArray("conversations")),
                messages = emptyList(),
                streamingText = "",
                isGenerating = payload.optBoolean("isGenerating"),
                isThinking = false,
                isHistoryLoading = false,
                companionMode = mode,
                error = null,
            )
            return
        }
        mutableState.value = current.copy(
            characterId = payload.optNullableString("characterId"),
            conversationId = payload.optNullableString("conversationId"),
            conversationTitle = payload.optString("conversationTitle"),
            conversations = parseConversationSummaries(payload.optJSONArray("conversations")),
            messages = parseMessages(payload.optJSONArray("messages")),
            streamingText = payload.optString("streamingText"),
            isGenerating = payload.optBoolean("isGenerating"),
            isThinking = payload.optBoolean("isThinking"),
            isHistoryLoading = false,
            companionMode = mode,
            error = null,
        )
    }

    private suspend fun hydrateCompanionPages() {
        var current = mutableState.value
        if (current.backendMode != ChatBackendMode.Desktop || current.companionMode == "profile_unavailable") return
        if (current.conversations.size >= 50) {
            val merged = current.conversations.toMutableList()
            var offset = merged.size
            var pageCount = 0
            while (pageCount++ < 100) {
                val page = try {
                    companionClient.request("history.list", JSONObject().put("limit", 50).put("offset", offset))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    break
                }
                val items = parseConversationSummaries(page.optJSONArray("items"))
                if (items.isEmpty()) break
                merged += items.filterNot { item -> merged.any { it.id == item.id } }
                val nextOffset = if (page.isNull("nextOffset")) -1 else page.optInt("nextOffset", -1)
                if (nextOffset < 0) break
                offset = nextOffset
            }
            current = mutableState.value
            if (current.backendMode == ChatBackendMode.Desktop) {
                mutableState.value = current.copy(conversations = merged)
            }
        }
        current = mutableState.value
        val conversationId = current.conversationId ?: return
        if (current.messages.size < 100) return
        val mergedMessages = current.messages.toMutableList()
        var beforeId = mergedMessages.firstOrNull()?.id ?: return
        var pageCount = 0
        while (pageCount++ < 100) {
            val page = try {
                companionClient.request(
                    "history.messages",
                    JSONObject().put("conversationId", conversationId).put("limit", 100).put("beforeMessageId", beforeId),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                break
            }
            val items = parseMessages(page.optJSONArray("items"))
            if (items.isEmpty()) break
            mergedMessages.addAll(0, items.filterNot { item -> mergedMessages.any { it.id == item.id } })
            beforeId = items.first().id
            if (items.size < 100) break
        }
        current = mutableState.value
        if (current.backendMode == ChatBackendMode.Desktop && current.conversationId == conversationId) {
            mutableState.value = current.copy(messages = mergedMessages)
        }
    }

    private fun scheduleCompanionHydration() {
        companionHydrationJob?.cancel()
        companionHydrationJob = viewModelScope.launch { hydrateCompanionPages() }
    }

    private fun parseConversationSummaries(array: JSONArray?): List<ChatConversationSummary> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(ChatConversationSummary(
                id = item.optString("id"),
                characterId = item.optString("characterId"),
                title = item.optString("title"),
                preview = item.optString("preview"),
                createdAt = item.optLong("createdAt"),
                updatedAt = item.optLong("updatedAt"),
                messageCount = item.optInt("messageCount"),
            ))
        }
    }

    private fun parseCapabilities(value: JSONObject): CompanionCapabilities = CompanionCapabilities(
        privateHistory = value.optBoolean("privateHistory"),
        remoteChat = value.optBoolean("remoteChat"),
        tts = value.optBoolean("tts"),
        actions = value.optBoolean("actions"),
        llmStatus = value.optString("llmStatus", "unconfigured"),
        ttsStatus = value.optString("ttsStatus", "unconfigured"),
    )

    private fun parseMessages(array: JSONArray?): List<ChatMessage> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(ChatMessage(
                id = item.optString("id"),
                role = item.optString("role", "user"),
                content = item.optString("content"),
                timestamp = item.optLong("timestamp"),
            ))
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

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

            val conversations = runIoCatching {
                history.saveConversation(requestContext.toConversation(messages))
                history.setActiveConversation(model.characterId, conversationId)
                history.listConversations(model.characterId)
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
            var latestStreamingText = ""
            val pendingParserText = StringBuilder()
            var lastStreamingPublishAt = 0L
            var pendingStreamingUpdate: Job? = null

            fun flushParserText() {
                if (pendingParserText.isEmpty()) return
                latestStreamingText = parser.consume(pendingParserText.toString())
                pendingParserText.setLength(0)
            }

            fun publishStreamingText() {
                val now = System.nanoTime() / 1_000_000L
                val elapsed = now - lastStreamingPublishAt
                if (lastStreamingPublishAt == 0L || elapsed >= STREAMING_UI_UPDATE_INTERVAL_MS) {
                    flushParserText()
                    pendingStreamingUpdate?.cancel()
                    pendingStreamingUpdate = null
                    lastStreamingPublishAt = now
                    if (isActive(requestContext)) {
                        mutableState.value = mutableState.value.copy(
                            streamingText = latestStreamingText,
                            isThinking = false,
                        )
                    }
                } else if (pendingStreamingUpdate?.isActive != true) {
                    pendingStreamingUpdate = launch {
                        delay((STREAMING_UI_UPDATE_INTERVAL_MS - elapsed).coerceAtLeast(1L))
                        if (isActive(requestContext)) {
                            flushParserText()
                            lastStreamingPublishAt = System.nanoTime() / 1_000_000L
                            mutableState.value = mutableState.value.copy(
                                streamingText = latestStreamingText,
                                isThinking = false,
                            )
                        }
                    }
                }
            }

            try {
                client.streamCompletion(settings, settings.systemPromptWithCustom(characterPrompt.text), messages).collect { event ->
                    if (!isActive(requestContext)) return@collect
                    when (event) {
                        is LlmStreamEvent.Content -> {
                            pendingParserText.append(event.text)
                            publishStreamingText()
                        }
                        LlmStreamEvent.ReasoningStarted -> mutableState.value = mutableState.value.copy(isThinking = true)
                    }
                }
                pendingStreamingUpdate?.cancel()
                flushParserText()
                finalizeAssistant(requestContext, parser)
            } catch (cancelled: CancellationException) {
                pendingStreamingUpdate?.cancel()
                withContext(NonCancellable) {
                    flushParserText()
                    finalizeAssistant(requestContext, parser)
                }
                throw cancelled
            } catch (error: Throwable) {
                pendingStreamingUpdate?.cancel()
                if (!currentCoroutineContext().isActive) {
                    withContext(NonCancellable) {
                        flushParserText()
                        finalizeAssistant(requestContext, parser)
                    }
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
        val conversations = runIoCatching {
            history.saveConversation(updatedRequest.toConversation(finalMessages))
            history.listConversations(request.characterId)
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

    private suspend fun <T> runIoCatching(block: () -> T): Result<T> = try {
        Result.success(withContext(Dispatchers.IO) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
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
        private const val STREAMING_UI_UPDATE_INTERVAL_MS = 32L
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
