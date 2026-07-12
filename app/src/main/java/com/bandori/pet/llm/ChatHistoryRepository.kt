package com.bandori.pet.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatHistoryRepository(context: Context) {
    private val root = File(context.filesDir, "chat_history")

    fun load(characterId: String): List<ChatMessage> {
        val file = fileFor(characterId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ChatMessage(
                            id = item.optString("id", "$characterId-$index"),
                            role = item.optString("role", "user"),
                            content = item.optString("content"),
                            timestamp = item.optLong("timestamp", 0L),
                        ),
                    )
                }
            }.takeLast(MAX_STORED_MESSAGES)
        }.getOrDefault(emptyList())
    }

    fun save(characterId: String, messages: List<ChatMessage>) {
        if (!root.exists()) root.mkdirs()
        val file = fileFor(characterId)
        val temp = File(root, "${file.name}.tmp")
        val array = JSONArray()
        messages.takeLast(MAX_STORED_MESSAGES).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("timestamp", message.timestamp),
            )
        }
        temp.writeText(array.toString())
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun clearCharacter(characterId: String) {
        fileFor(characterId).delete()
    }

    fun clearAll() {
        root.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
    }

    private fun fileFor(characterId: String): File {
        val safeId = characterId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(root, "$safeId.json")
    }

    companion object {
        const val MAX_STORED_MESSAGES = 100
        const val MAX_REQUEST_MESSAGES = 40
    }
}
