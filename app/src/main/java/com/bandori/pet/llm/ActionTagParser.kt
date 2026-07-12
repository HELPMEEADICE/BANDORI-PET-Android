package com.bandori.pet.llm

class ActionTagParser(tags: Set<String>) {
    private val allowed = tags.map { it.lowercase() }.toSet()
    private val raw = StringBuilder()

    fun consume(text: String): String {
        raw.append(text)
        return sanitized(final = false).text
    }

    fun finish(): Result = sanitized(final = true)

    private fun sanitized(final: Boolean): Result {
        var firstAction: String? = null
        val visible = TAG_REGEX.replace(raw.toString()) { match ->
            val tag = match.groupValues[1]
            if (tag.lowercase() in allowed) {
                if (firstAction == null) firstAction = tag
                ""
            } else {
                match.value
            }
        }
        val safeVisible = holdIncompleteTag(visible)
        return Result(
            text = if (final) safeVisible.trim() else safeVisible.trimStart(),
            action = firstAction,
        )
    }

    private fun holdIncompleteTag(value: String): String {
        val start = value.lastIndexOf('[')
        if (start < 0 || value.indexOf(']', start) >= 0) return value
        val token = value.substring(start + 1)
        if (!token.matches(Regex("[A-Za-z0-9_.]*"))) return value
        return if (allowed.any { it.startsWith(token.lowercase()) }) value.substring(0, start) else value
    }

    data class Result(val text: String, val action: String?)

    companion object {
        private val TAG_REGEX = Regex("\\[([A-Za-z0-9_.]+)]")

        fun tagsFrom(text: String): Set<String> = TAG_REGEX.findAll(text).map { it.groupValues[1] }.toSet()
    }
}
