package com.bandori.pet.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRequestTest {
    @Test
    fun endpointIsNormalized() {
        assertEquals("https://api.example.com/v1/chat/completions", LlmSettings(baseUrl = "https://api.example.com/v1/").endpoint())
        assertEquals("http://192.168.1.2:11434/chat/completions", LlmSettings(baseUrl = "http://192.168.1.2:11434").endpoint())
        assertEquals(
            "https://api.example.com/chat/completions",
            LlmSettings(baseUrl = "https://api.example.com/chat/completions").endpoint(),
        )
    }

    @Test
    fun thinkingModesUseDeepSeekExtensionOnlyWhenExplicit() {
        val client = LlmChatClient()
        val base = LlmSettings(apiKey = "secret")
        val auto = client.buildRequestBody(base, "system", emptyList())
        val enabled = client.buildRequestBody(base.copy(thinkingMode = ThinkingMode.Enabled), "system", emptyList())
        val disabled = client.buildRequestBody(base.copy(thinkingMode = ThinkingMode.Disabled), "system", emptyList())
        assertFalse(auto.has("thinking"))
        assertEquals("enabled", enabled.getJSONObject("thinking").getString("type"))
        assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"))
        assertFalse(auto.toString().contains("secret"))
    }

    @Test
    fun requestContainsSystemAndLatestFortyMessages() {
        val messages = (0 until 45).map { index -> ChatMessage("$index", "user", "m$index", index.toLong()) }
        val body = LlmChatClient().buildRequestBody(LlmSettings(), "persona", messages)
        val requestMessages = body.getJSONArray("messages")
        assertEquals(41, requestMessages.length())
        assertEquals("system", requestMessages.getJSONObject(0).getString("role"))
        assertEquals("m5", requestMessages.getJSONObject(1).getString("content"))
        assertTrue(body.getBoolean("stream"))
    }
}
