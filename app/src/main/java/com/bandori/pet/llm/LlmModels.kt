package com.bandori.pet.llm

import android.content.Context
import com.bandori.pet.SETTINGS_PREFS

enum class ThinkingMode(val value: String) {
    Auto("auto"),
    Enabled("enabled"),
    Disabled("disabled"),
    ;

    companion object {
        fun fromValue(value: String?): ThinkingMode = entries.firstOrNull { it.value == value } ?: Auto
    }
}

data class LlmSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val thinkingMode: ThinkingMode = ThinkingMode.Auto,
    val temperature: Float = 0.8f,
    val maxTokens: Int = 1024,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun normalized(): LlmSettings = copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature.coerceIn(0f, 2f),
        maxTokens = maxTokens.coerceIn(1, 32_768),
    )

    fun endpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions", ignoreCase = true)) base else "$base/chat/completions"
    }

    fun save(context: Context) {
        val value = normalized()
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LLM_BASE_URL, value.baseUrl)
            .putString(KEY_LLM_API_KEY, value.apiKey)
            .putString(KEY_LLM_MODEL, value.model)
            .putString(KEY_LLM_THINKING_MODE, value.thinkingMode.value)
            .putFloat(KEY_LLM_TEMPERATURE, value.temperature)
            .putInt(KEY_LLM_MAX_TOKENS, value.maxTokens)
            .apply()
    }

    companion object {
        fun load(context: Context): LlmSettings {
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            return LlmSettings(
                baseUrl = prefs.getString(KEY_LLM_BASE_URL, null).orEmpty(),
                apiKey = prefs.getString(KEY_LLM_API_KEY, null).orEmpty(),
                model = prefs.getString(KEY_LLM_MODEL, null).orEmpty(),
                thinkingMode = ThinkingMode.fromValue(prefs.getString(KEY_LLM_THINKING_MODE, null)),
                temperature = prefs.getFloat(KEY_LLM_TEMPERATURE, 0.8f).coerceIn(0f, 2f),
                maxTokens = prefs.getInt(KEY_LLM_MAX_TOKENS, 1024).coerceIn(1, 32_768),
            )
        }
    }
}

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
)

sealed interface LlmStreamEvent {
    data class Content(val text: String) : LlmStreamEvent
    data object ReasoningStarted : LlmStreamEvent
}

private const val KEY_LLM_BASE_URL = "llm_base_url"
private const val KEY_LLM_API_KEY = "llm_api_key"
private const val KEY_LLM_MODEL = "llm_model"
private const val KEY_LLM_THINKING_MODE = "llm_thinking_mode"
private const val KEY_LLM_TEMPERATURE = "llm_temperature"
private const val KEY_LLM_MAX_TOKENS = "llm_max_tokens"
