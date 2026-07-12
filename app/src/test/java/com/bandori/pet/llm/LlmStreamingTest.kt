package com.bandori.pet.llm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmStreamingTest {
    @Test
    fun parsesReasoningAndContentSseEvents() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }.toByteArray()
        withServer(200, body) { baseUrl ->
            val events = LlmChatClient().streamCompletion(
                LlmSettings(baseUrl = baseUrl, apiKey = "test-key"),
                "system",
                emptyList(),
            ).toList()
            assertEquals(LlmStreamEvent.ReasoningStarted, events[0])
            assertEquals(LlmStreamEvent.Content("你好"), events[1])
        }
    }

    @Test
    fun redactsApiKeyFromHttpError() = runBlocking {
        val key = "very-secret-key"
        withServer(401, "bad key: $key".toByteArray()) { baseUrl ->
            val error = runCatching {
                LlmChatClient().streamCompletion(
                    LlmSettings(baseUrl = baseUrl, apiKey = key),
                    "system",
                    emptyList(),
                ).toList()
            }.exceptionOrNull()
            assertTrue(error?.message?.contains("***") == true)
            assertTrue(error?.message?.contains(key) == false)
        }
    }

    private suspend fun withServer(code: Int, response: ByteArray, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(code, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}
