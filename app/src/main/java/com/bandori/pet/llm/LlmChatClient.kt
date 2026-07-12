package com.bandori.pet.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LlmChatClient {
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun streamCompletion(
        settings: LlmSettings,
        systemPrompt: String,
        messages: List<ChatMessage>,
    ): Flow<LlmStreamEvent> = flow {
        val normalized = settings.normalized()
        val connection = (URL(normalized.endpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${normalized.apiKey}")
        }
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause ->
            if (cause != null) connection.disconnect()
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(buildRequestBody(normalized, systemPrompt, messages).toString())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val rawDetail = connection.errorStream?.bufferedReader()?.use { it.readText().take(2048) }.orEmpty()
                val detail = if (normalized.apiKey.isEmpty()) rawDetail else rawDetail.replace(normalized.apiKey, "***")
                throw IOException("HTTP $code${if (detail.isBlank()) "" else ": $detail"}")
            }
            var reasoningEmitted = false
            val dataLines = mutableListOf<String>()

            fun takeEvent(): ParsedSseEvent? {
                if (dataLines.isEmpty()) return null
                val data = dataLines.joinToString("\n").trim()
                dataLines.clear()
                if (data == "[DONE]") return ParsedSseEvent(done = true)
                if (data.isBlank()) return null
                val delta = runCatching {
                    JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                }.getOrNull() ?: return null
                val reasoning = delta.opt("reasoning_content")
                val content = delta.opt("content")
                return ParsedSseEvent(
                    reasoning = reasoning is String && reasoning.isNotEmpty(),
                    content = content as? String,
                )
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> {
                            val event = takeEvent()
                            if (event?.done == true) break
                            if (event?.reasoning == true && !reasoningEmitted) {
                                reasoningEmitted = true
                                emit(LlmStreamEvent.ReasoningStarted)
                            }
                            event?.content?.takeIf(String::isNotEmpty)?.let { emit(LlmStreamEvent.Content(it)) }
                        }
                        line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                    }
                }
                val event = takeEvent()
                if (event?.reasoning == true && !reasoningEmitted) emit(LlmStreamEvent.ReasoningStarted)
                event?.content?.takeIf(String::isNotEmpty)?.let { emit(LlmStreamEvent.Content(it)) }
            }
        } finally {
            cancellationHandle.dispose()
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    internal fun buildRequestBody(
        settings: LlmSettings,
        systemPrompt: String,
        messages: List<ChatMessage>,
    ): JSONObject {
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        messages.takeLast(ChatHistoryRepository.MAX_REQUEST_MESSAGES).forEach { message ->
            requestMessages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        return JSONObject()
            .put("model", settings.model)
            .put("messages", requestMessages)
            .put("stream", true)
            .put("temperature", settings.temperature.toDouble())
            .put("max_tokens", settings.maxTokens)
            .apply {
                when (settings.thinkingMode) {
                    ThinkingMode.Auto -> Unit
                    ThinkingMode.Enabled -> put("thinking", JSONObject().put("type", "enabled"))
                    ThinkingMode.Disabled -> put("thinking", JSONObject().put("type", "disabled"))
                }
            }
    }

    private data class ParsedSseEvent(
        val done: Boolean = false,
        val reasoning: Boolean = false,
        val content: String? = null,
    )
}
