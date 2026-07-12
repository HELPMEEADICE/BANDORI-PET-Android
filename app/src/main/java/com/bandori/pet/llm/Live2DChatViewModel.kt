package com.bandori.pet.llm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bandori.pet.data.ModelChoice
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val characterId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val isThinking: Boolean = false,
    val error: String? = null,
)

class Live2DChatViewModel(application: Application) : AndroidViewModel(application) {
    private val history = ChatHistoryRepository(application)
    private val prompts = CharacterPromptRepository(application)
    private val client = LlmChatClient()
    private val mutableState = MutableStateFlow(ChatUiState())
    private val mutableActions = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var requestJob: Job? = null
    private var lastFailedInput: String? = null

    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    val actions: SharedFlow<String> = mutableActions.asSharedFlow()

    fun selectCharacter(model: ModelChoice, force: Boolean = false) {
        if (!force && mutableState.value.characterId == model.characterId) return
        requestJob?.cancel()
        viewModelScope.launch {
            val messages = withContext(Dispatchers.IO) { history.load(model.characterId) }
            mutableState.value = ChatUiState(characterId = model.characterId, messages = messages)
        }
    }

    fun send(model: ModelChoice, input: String) {
        val text = input.trim()
        if (text.isEmpty() || requestJob?.isActive == true) return
        startRequest(model, text, appendUser = true)
    }

    fun retry(model: ModelChoice) {
        val text = lastFailedInput ?: return
        if (requestJob?.isActive == true) return
        startRequest(model, text, appendUser = false)
    }

    fun stop() {
        requestJob?.cancel()
    }

    fun clearCurrent(characterId: String) {
        val activeRequest = requestJob
        viewModelScope.launch {
            activeRequest?.cancelAndJoin()
            withContext(Dispatchers.IO) { history.clearCharacter(characterId) }
            mutableState.value = ChatUiState(characterId = characterId)
        }
    }

    fun clearAll() {
        val activeRequest = requestJob
        viewModelScope.launch {
            activeRequest?.cancelAndJoin()
            withContext(Dispatchers.IO) { history.clearAll() }
            mutableState.value = mutableState.value.copy(messages = emptyList(), streamingText = "", error = null)
        }
    }

    private fun startRequest(model: ModelChoice, input: String, appendUser: Boolean) {
        requestJob = viewModelScope.launch {
            if (mutableState.value.characterId != model.characterId) {
                val loaded = withContext(Dispatchers.IO) { history.load(model.characterId) }
                mutableState.value = ChatUiState(characterId = model.characterId, messages = loaded)
            }
            var messages = mutableState.value.messages
            if (appendUser) {
                messages = (messages + newMessage("user", input)).takeLast(ChatHistoryRepository.MAX_STORED_MESSAGES)
                withContext(Dispatchers.IO) { history.save(model.characterId, messages) }
            }
            val settings = LlmSettings.load(getApplication())
            if (!settings.isConfigured) {
                mutableState.value = mutableState.value.copy(messages = messages, error = "LLM_NOT_CONFIGURED")
                return@launch
            }
            val characterPrompt = withContext(Dispatchers.IO) { prompts.buildSystemPrompt(model) }
            val parser = ActionTagParser(characterPrompt.allowedActionTags)
            mutableState.value = ChatUiState(
                characterId = model.characterId,
                messages = messages,
                isGenerating = true,
            )
            lastFailedInput = null
            try {
                client.streamCompletion(settings, characterPrompt.text, messages).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Content -> mutableState.value = mutableState.value.copy(
                            streamingText = parser.consume(event.text),
                            isThinking = false,
                        )
                        LlmStreamEvent.ReasoningStarted -> mutableState.value = mutableState.value.copy(isThinking = true)
                    }
                }
                finalizeAssistant(model.characterId, parser, messages)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finalizeAssistant(model.characterId, parser, messages) }
                throw cancelled
            } catch (error: Throwable) {
                if (!currentCoroutineContext().isActive) {
                    withContext(NonCancellable) { finalizeAssistant(model.characterId, parser, messages) }
                    return@launch
                }
                lastFailedInput = input
                mutableState.value = mutableState.value.copy(
                    isGenerating = false,
                    isThinking = false,
                    streamingText = "",
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private suspend fun finalizeAssistant(
        characterId: String,
        parser: ActionTagParser,
        priorMessages: List<ChatMessage>,
    ) {
        val result = parser.finish()
        val finalMessages = if (result.text.isNotBlank()) {
            (priorMessages + newMessage("assistant", result.text)).takeLast(ChatHistoryRepository.MAX_STORED_MESSAGES)
        } else {
            priorMessages
        }
        withContext(Dispatchers.IO) { history.save(characterId, finalMessages) }
        result.action?.let { mutableActions.emit(it) }
        mutableState.value = ChatUiState(characterId = characterId, messages = finalMessages)
    }

    private fun newMessage(role: String, content: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = role,
        content = content,
        timestamp = System.currentTimeMillis(),
    )
}
