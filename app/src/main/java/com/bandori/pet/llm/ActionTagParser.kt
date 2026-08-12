package com.bandori.pet.llm

class ActionTagParser(tags: Set<String>) {
    private val allowed = tags.map { it.lowercase() }.toSet()
    private val visible = StringBuilder()
    private var pendingTag: StringBuilder? = null
    private var firstAction: String? = null

    fun consume(text: String): String {
        text.forEach(::consumeCharacter)
        return visible.toString().trimStart()
    }

    fun finish(): Result {
        // A retained partial tag is a prefix of a supported action. Keep it hidden
        // instead of exposing protocol text at the end of a truncated response.
        pendingTag = null
        return Result(text = visible.toString().trim(), action = firstAction)
    }

    private fun consumeCharacter(character: Char) {
        val pending = pendingTag
        if (pending == null) {
            if (character == '[' && allowed.isNotEmpty()) {
                pendingTag = StringBuilder("[")
            } else {
                visible.append(character)
            }
            return
        }

        when {
            character == ']' -> {
                val token = pending.substring(1)
                if (token.lowercase() in allowed) {
                    if (firstAction == null) firstAction = token
                } else {
                    visible.append(pending).append(character)
                }
                pendingTag = null
            }
            character == '[' -> {
                visible.append(pending)
                pendingTag = StringBuilder("[")
            }
            character.isTagCharacter() -> {
                pending.append(character)
                val token = pending.substring(1).lowercase()
                if (allowed.none { it.startsWith(token) }) {
                    visible.append(pending)
                    pendingTag = null
                }
            }
            else -> {
                visible.append(pending).append(character)
                pendingTag = null
            }
        }
    }

    private fun Char.isTagCharacter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == '.'

    data class Result(val text: String, val action: String?)

    companion object {
        private val TAG_REGEX = Regex("\\[([A-Za-z0-9_.]+)]")

        fun tagsFrom(text: String): Set<String> = TAG_REGEX.findAll(text).map { it.groupValues[1] }.toSet()
    }
}
