package com.bandori.pet.llm

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterPromptJsonTest {
    @Test
    fun containsAllCharactersAndResolvedRules() {
        val promptFile = listOf(File("prompt.json"), File("../prompt.json"))
            .firstOrNull(File::isFile) ?: error("prompt.json not found from ${File(".").absolutePath}")
        val root = JSONObject(promptFile.readText())
        val characters = root.getJSONObject("characters")
        val outfitCharacters = JSONObject(File(promptFile.parentFile, "outfit.json").readText())
            .getJSONObject("characters")
        assertEquals(outfitCharacters.keys().asSequence().toSet(), characters.keys().asSequence().toSet())
        assertTrue(root.getString("common_rules").contains("单次对话只允许携带一个动作标签"))
        val yukina = characters.getJSONObject("yukina")
        assertTrue(yukina.getString("action_rules").contains("[smile]"))
        assertTrue(yukina.getJSONArray("tags").toString().contains("yukina_default.exp"))
    }
}
