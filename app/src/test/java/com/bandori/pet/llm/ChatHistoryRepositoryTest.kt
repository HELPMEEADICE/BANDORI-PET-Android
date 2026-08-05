package com.bandori.pet.llm

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyHistoryMigratesOnceAndRemainsSelected() {
        val root = temporaryFolder.newFolder("chat_history")
        val legacy = File(root, "kasumi.json")
        legacy.writeText(
            JSONArray()
                .put(messageJson("m1", "user", "  第一次\n聊天  ", 10L))
                .put(messageJson("m2", "assistant", "你好！", 20L))
                .toString(),
        )

        val repository = ChatHistoryRepository(root)
        val first = repository.loadSnapshot("kasumi")
        val second = repository.loadSnapshot("kasumi")

        assertEquals(1, first.conversations.size)
        assertEquals("第一次 聊天", first.conversations.single().title)
        assertEquals(listOf("m1", "m2"), first.activeConversation?.messages?.map { it.id })
        assertEquals(first.conversations, second.conversations)
        assertFalse(legacy.exists())
    }

    @Test
    fun conversationsAreSortedAndIsolatedByCharacter() {
        val repository = ChatHistoryRepository(temporaryFolder.newFolder("history"))
        repository.saveConversation(conversation("old", "kasumi", 10L, "old message"))
        repository.saveConversation(conversation("new", "kasumi", 30L, "new message"))
        repository.saveConversation(conversation("other", "ran", 50L, "other character"))

        assertEquals(listOf("new", "old"), repository.listConversations("kasumi").map { it.id })
        assertEquals(listOf("other"), repository.listConversations("ran").map { it.id })
    }

    @Test
    fun selectedConversationRestoresAndFallsBackAfterDelete() {
        val repository = ChatHistoryRepository(temporaryFolder.newFolder("history"))
        repository.saveConversation(conversation("first", "kasumi", 10L, "first"))
        repository.saveConversation(conversation("latest", "kasumi", 20L, "latest"))
        repository.setActiveConversation("kasumi", "first")

        assertEquals("first", repository.loadSnapshot("kasumi").activeConversation?.id)
        assertTrue(repository.deleteConversation("kasumi", "first"))

        assertEquals("latest", repository.loadSnapshot("kasumi").activeConversation?.id)
    }

    @Test
    fun messagesAreCappedPerConversationAndTitlesAreUnicodeSafe() {
        val repository = ChatHistoryRepository(temporaryFolder.newFolder("history"))
        val messages = (0 until 120).map { index -> message("m$index", "user", "内容$index", index.toLong()) }
        val longTitle = "😀".repeat(40)
        val saved = repository.saveConversation(
            ChatConversation(
                id = "capped",
                characterId = "kasumi",
                title = ChatHistoryRepository.titleFromFirstMessage(longTitle),
                createdAt = 1L,
                updatedAt = 120L,
                messages = messages,
            ),
        )

        assertEquals(ChatHistoryRepository.MAX_STORED_MESSAGES, saved.messages.size)
        assertEquals("m20", saved.messages.first().id)
        assertEquals("😀".repeat(32) + "…", saved.title)
    }

    @Test
    fun corruptConversationDoesNotHideHealthyHistory() {
        val root = temporaryFolder.newFolder("history")
        val repository = ChatHistoryRepository(root)
        repository.saveConversation(conversation("healthy", "kasumi", 10L, "hello"))
        val characterDirectory = File(root, "v2/kasumi").apply { mkdirs() }
        File(characterDirectory, "corrupt.json").writeText("{not-json")

        val conversations = repository.listConversations("kasumi")

        assertEquals(listOf("healthy"), conversations.map { it.id })
        assertTrue(File(characterDirectory, "corrupt.json").exists())
        assertNull(repository.loadConversation("kasumi", "corrupt"))
    }

    @Test
    fun clearAllRemovesLegacyAndVersionedHistory() {
        val root = temporaryFolder.newFolder("history")
        val repository = ChatHistoryRepository(root)
        File(root, "kasumi.json").writeText("[]")
        repository.saveConversation(conversation("one", "kasumi", 10L, "hello"))

        repository.clearAll()

        assertFalse(root.exists())
        assertTrue(repository.listConversations("kasumi").isEmpty())
    }

    private fun conversation(id: String, characterId: String, updatedAt: Long, content: String): ChatConversation =
        ChatConversation(
            id = id,
            characterId = characterId,
            title = "",
            createdAt = updatedAt,
            updatedAt = updatedAt,
            messages = listOf(message("$id-message", "user", content, updatedAt)),
        )

    private fun message(id: String, role: String, content: String, timestamp: Long): ChatMessage =
        ChatMessage(id = id, role = role, content = content, timestamp = timestamp)

    private fun messageJson(id: String, role: String, content: String, timestamp: Long): JSONObject =
        JSONObject()
            .put("id", id)
            .put("role", role)
            .put("content", content)
            .put("timestamp", timestamp)
}
