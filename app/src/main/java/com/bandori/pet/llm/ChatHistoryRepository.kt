package com.bandori.pet.llm

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ChatHistoryRepository internal constructor(
    private val root: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "chat_history"))

    @Synchronized
    fun loadSnapshot(characterId: String): ChatHistorySnapshot {
        val conversations = listConversations(characterId)
        val requestedId = readActiveIds()[characterId]
        val selectedSummary = conversations.firstOrNull { it.id == requestedId } ?: conversations.firstOrNull()
        val selected = selectedSummary?.let { loadConversation(characterId, it.id) }
        if (selected?.id != requestedId) {
            runCatching { setActiveConversation(characterId, selected?.id) }
        }
        return ChatHistorySnapshot(conversations = conversations, activeConversation = selected)
    }

    @Synchronized
    fun listConversations(characterId: String): List<ChatConversationSummary> {
        ensureLegacyMigrated(characterId)
        val directory = characterDirectory(characterId)
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file -> readConversation(file, characterId)?.toSummary() }
            ?.sortedWith(
                compareByDescending<ChatConversationSummary> { it.updatedAt }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id },
            )
            ?.toList()
            .orEmpty()
    }

    @Synchronized
    fun loadConversation(characterId: String, conversationId: String): ChatConversation? {
        ensureLegacyMigrated(characterId)
        val file = conversationFile(characterId, conversationId) ?: return null
        return readConversation(file, characterId)
    }

    @Synchronized
    fun saveConversation(conversation: ChatConversation): ChatConversation {
        val normalized = conversation.copy(
            title = conversation.title.ifBlank { titleFromMessages(conversation.messages) },
            messages = conversation.messages.takeLast(MAX_STORED_MESSAGES),
        )
        val file = conversationFile(normalized.characterId, normalized.id)
            ?: throw IllegalArgumentException("Invalid conversation id")
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", normalized.id)
            .put("characterId", normalized.characterId)
            .put("title", normalized.title)
            .put("createdAt", normalized.createdAt)
            .put("updatedAt", normalized.updatedAt)
            .put("messages", messagesToJson(normalized.messages))
        writeAtomically(file, payload.toString())
        return normalized
    }

    @Synchronized
    fun setActiveConversation(characterId: String, conversationId: String?) {
        val activeIds = readActiveIds().toMutableMap()
        if (conversationId == null) activeIds.remove(characterId) else activeIds[characterId] = conversationId
        val payload = JSONObject()
        activeIds.forEach { (id, activeConversationId) -> payload.put(id, activeConversationId) }
        writeAtomically(activeStateFile(), payload.toString())
    }

    @Synchronized
    fun deleteConversation(characterId: String, conversationId: String): Boolean {
        val file = conversationFile(characterId, conversationId) ?: return false
        val deleted = !file.exists() || file.delete()
        if (deleted && readActiveIds()[characterId] == conversationId) {
            setActiveConversation(characterId, null)
        }
        return deleted
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        val legacy = legacyFile(characterId)
        check(!legacy.exists() || legacy.delete()) { "Cannot delete legacy chat history" }
        val directory = characterDirectory(characterId)
        check(!directory.exists() || directory.deleteRecursively()) { "Cannot delete character chat history" }
        setActiveConversation(characterId, null)
    }

    @Synchronized
    fun clearAll() {
        check(!root.exists() || root.deleteRecursively()) { "Cannot delete chat history" }
    }

    private fun ensureLegacyMigrated(characterId: String) {
        val legacy = legacyFile(characterId)
        if (!legacy.isFile) return
        val messages = readLegacyMessages(legacy, characterId) ?: return
        if (messages.isEmpty()) {
            legacy.delete()
            return
        }

        val migratedFile = conversationFile(characterId, LEGACY_CONVERSATION_ID) ?: return
        if (readConversation(migratedFile, characterId) == null) {
            val validTimestamps = messages.map(ChatMessage::timestamp).filter { it > 0L }
            val fallbackTimestamp = legacy.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val conversation = ChatConversation(
                id = LEGACY_CONVERSATION_ID,
                characterId = characterId,
                title = titleFromMessages(messages),
                createdAt = validTimestamps.firstOrNull() ?: fallbackTimestamp,
                updatedAt = validTimestamps.lastOrNull() ?: fallbackTimestamp,
                messages = messages,
            )
            saveConversation(conversation)
        }
        if (readActiveIds()[characterId] == null) {
            runCatching { setActiveConversation(characterId, LEGACY_CONVERSATION_ID) }
        }
        if (migratedFile.isFile && readConversation(migratedFile, characterId) != null) legacy.delete()
    }

    private fun readLegacyMessages(file: File, characterId: String): List<ChatMessage>? = runCatching {
        val array = JSONArray(file.readText())
        parseMessages(array, characterId).takeLast(MAX_STORED_MESSAGES)
    }.getOrNull()

    private fun readConversation(file: File, expectedCharacterId: String): ChatConversation? = runCatching {
        if (!file.isFile) return@runCatching null
        val payload = JSONObject(file.readText())
        val characterId = payload.optString("characterId", expectedCharacterId)
        if (characterId != expectedCharacterId) return@runCatching null
        val id = payload.optString("id", file.nameWithoutExtension)
        if (!VALID_CONVERSATION_ID.matches(id) || id != file.nameWithoutExtension) return@runCatching null
        val messages = parseMessages(payload.optJSONArray("messages") ?: JSONArray(), id)
            .takeLast(MAX_STORED_MESSAGES)
        val fallbackTimestamp = file.lastModified().takeIf { it > 0L } ?: 0L
        val createdAt = payload.optLong("createdAt", fallbackTimestamp)
        val updatedAt = payload.optLong("updatedAt", createdAt)
        ChatConversation(
            id = id,
            characterId = characterId,
            title = payload.optString("title").ifBlank { titleFromMessages(messages) },
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = messages,
        )
    }.getOrNull()

    private fun parseMessages(array: JSONArray, fallbackPrefix: String): List<ChatMessage> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                ChatMessage(
                    id = item.optString("id", "$fallbackPrefix-$index"),
                    role = item.optString("role", "user"),
                    content = item.optString("content"),
                    timestamp = item.optLong("timestamp", 0L),
                ),
            )
        }
    }

    private fun messagesToJson(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
        messages.forEach { message ->
            put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("timestamp", message.timestamp),
            )
        }
    }

    private fun ChatConversation.toSummary(): ChatConversationSummary = ChatConversationSummary(
        id = id,
        characterId = characterId,
        title = title.ifBlank { titleFromMessages(messages) },
        preview = messages.lastOrNull { it.content.isNotBlank() }
            ?.content
            ?.let { compactAndTruncate(it, PREVIEW_MAX_CODE_POINTS) }
            .orEmpty(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        messageCount = messages.size,
    )

    private fun readActiveIds(): Map<String, String> = runCatching {
        val file = activeStateFile()
        if (!file.isFile) return@runCatching emptyMap()
        val payload = JSONObject(file.readText())
        buildMap {
            val keys = payload.keys()
            while (keys.hasNext()) {
                val characterId = keys.next()
                payload.optString(characterId).takeIf(String::isNotBlank)?.let { put(characterId, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeAtomically(file: File, text: String) {
        val directory = file.parentFile ?: throw IllegalStateException("History file has no parent")
        check(directory.exists() || directory.mkdirs()) { "Cannot create chat history directory" }
        val temp = File(directory, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(text)
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun legacyFile(characterId: String): File = File(root, "${safeCharacterId(characterId)}.json")

    private fun characterDirectory(characterId: String): File = File(File(root, "v2"), safeCharacterId(characterId))

    private fun activeStateFile(): File = File(File(root, "v2"), "_active.json")

    private fun conversationFile(characterId: String, conversationId: String): File? {
        if (!VALID_CONVERSATION_ID.matches(conversationId)) return null
        return File(characterDirectory(characterId), "$conversationId.json")
    }

    private fun safeCharacterId(characterId: String): String =
        characterId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    companion object {
        const val MAX_STORED_MESSAGES = 100
        const val MAX_REQUEST_MESSAGES = 40
        private const val SCHEMA_VERSION = 2
        private const val LEGACY_CONVERSATION_ID = "legacy"
        private const val TITLE_MAX_CODE_POINTS = 32
        private const val PREVIEW_MAX_CODE_POINTS = 64
        private val VALID_CONVERSATION_ID = Regex("[A-Za-z0-9_.-]+")
        private val WHITESPACE = Regex("\\s+")

        fun titleFromMessages(messages: List<ChatMessage>): String {
            val source = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: messages.firstOrNull { it.content.isNotBlank() }
            return source?.content?.let { compactAndTruncate(it, TITLE_MAX_CODE_POINTS) }.orEmpty()
        }

        fun titleFromFirstMessage(content: String): String = compactAndTruncate(content, TITLE_MAX_CODE_POINTS)

        private fun compactAndTruncate(content: String, maxCodePoints: Int): String {
            val compact = WHITESPACE.replace(content.trim(), " ")
            var end = 0
            var codePoints = 0
            while (end < compact.length && codePoints < maxCodePoints) {
                val current = compact[end]
                end += if (
                    current.isHighSurrogate() &&
                    end + 1 < compact.length &&
                    compact[end + 1].isLowSurrogate()
                ) {
                    2
                } else {
                    1
                }
                codePoints += 1
            }
            if (end == compact.length) return compact
            return compact.substring(0, end).trimEnd() + "…"
        }
    }
}
