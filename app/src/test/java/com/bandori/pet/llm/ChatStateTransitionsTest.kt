package com.bandori.pet.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateTransitionsTest {
    @Test
    fun newDraftKeepsHistoryWithoutSelectingOrPersistingAnEmptyConversation() {
        val summaries = listOf(summary("old", "kasumi", 10L))

        val state = ChatStateTransitions.newDraft("kasumi", summaries)

        assertNull(state.conversationId)
        assertTrue(state.messages.isEmpty())
        assertEquals(summaries, state.conversations)
    }

    @Test
    fun snapshotLoadsTheSelectedConversationMessages() {
        val conversation = conversation("selected", "kasumi", "hello")
        val snapshot = ChatHistorySnapshot(
            conversations = listOf(summary("selected", "kasumi", 10L), summary("other", "kasumi", 5L)),
            activeConversation = conversation,
        )

        val state = ChatStateTransitions.fromSnapshot("kasumi", snapshot)

        assertEquals("selected", state.conversationId)
        assertEquals(listOf("hello"), state.messages.map { it.content })
    }

    @Test
    fun deletingTheActiveConversationSelectsReplacementOrCreatesDraft() {
        val replacement = conversation("replacement", "kasumi", "older chat")
        val summaries = listOf(summary("replacement", "kasumi", 5L))

        val replaced = ChatStateTransitions.afterActiveDelete("kasumi", summaries, replacement)
        val empty = ChatStateTransitions.afterActiveDelete("kasumi", emptyList(), null)

        assertEquals("replacement", replaced.conversationId)
        assertNull(empty.conversationId)
        assertTrue(empty.conversations.isEmpty())
    }

    @Test
    fun requestIdentityDoesNotMatchAfterCharacterOrConversationSwitch() {
        val state = ChatStateTransitions.fromConversation(
            conversation("current", "kasumi", "message"),
            listOf(summary("current", "kasumi", 10L)),
        )

        assertTrue(ChatStateTransitions.matchesConversation(state, "kasumi", "current"))
        assertFalse(ChatStateTransitions.matchesConversation(state, "kasumi", "previous"))
        assertFalse(ChatStateTransitions.matchesConversation(state, "ran", "current"))
    }

    private fun conversation(id: String, characterId: String, content: String): ChatConversation =
        ChatConversation(
            id = id,
            characterId = characterId,
            title = content,
            createdAt = 1L,
            updatedAt = 10L,
            messages = listOf(ChatMessage("message-$id", "user", content, 10L)),
        )

    private fun summary(id: String, characterId: String, updatedAt: Long): ChatConversationSummary =
        ChatConversationSummary(
            id = id,
            characterId = characterId,
            title = id,
            preview = id,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            messageCount = 1,
        )
}
