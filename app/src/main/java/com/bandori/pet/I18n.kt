package com.bandori.pet

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object I18n {

    private val strings = mutableMapOf<String, String>()
    private val placeholderRegex = Regex("\\{(\\d+)\\}")

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val locale = Locale.getDefault()
            val lang = when {
                locale.language.equals("zh", ignoreCase = true) -> "zh"
                locale.language.equals("ja", ignoreCase = true) -> "ja"
                else -> "en"
            }
            val assetPath = "lang/$lang.json"
            val fallbackPaths = listOfNotNull(
                if (lang != "zh") "zh" else null,
                if (lang != "en") "en" else null,
            )
            loadFromAssets(context, assetPath) || fallbackPaths.any { loadFromAssets(context, "lang/$it.json") }
            initialized = true
        }
    }

    private fun loadFromAssets(context: Context, path: String): Boolean {
        return runCatching {
            context.assets.open(path).bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    strings[key] = json.optString(key, key)
                }
            }
            true
        }.getOrDefault(false)
    }

    fun t(key: String, vararg args: Any?): String {
        val template = strings[key] ?: key
        if (args.isEmpty()) return template
        return placeholderRegex.replace(template) { match ->
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index < args.size) args[index].toString() else match.value
        }
    }
}
