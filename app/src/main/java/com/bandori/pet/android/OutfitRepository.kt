package com.bandori.pet.android

import org.json.JSONObject
import java.io.File

data class OutfitModel(
    val characterId: String,
    val characterName: String,
    val costumeId: String,
    val costumeName: String,
    val modelConfig: File,
) {
    val title: String get() = "$characterName / $costumeName"
}

object OutfitRepository {
    fun load(runtimeRoot: File): List<OutfitModel> {
        val outfitJson = File(runtimeRoot, "outfit.json")
        if (!outfitJson.exists()) return emptyList()

        val root = JSONObject(outfitJson.readText())
        val characters = root.optJSONObject("characters") ?: return emptyList()
        val result = mutableListOf<OutfitModel>()
        val characterIds = characters.keys().asSequence().toList().sorted()
        val modelsRoot = File(runtimeRoot, "models")

        for (characterId in characterIds) {
            val character = characters.optJSONObject(characterId) ?: continue
            val characterName = character.optString("display", characterId)
            val costumes = character.optJSONObject("costumes") ?: continue
            val costumeIds = costumes.keys().asSequence().toList().sorted()
            for (costumeId in costumeIds) {
                val costumeDir = File(File(modelsRoot, characterId), costumeId)
                val config = findModelConfig(costumeDir) ?: continue
                result += OutfitModel(
                    characterId = characterId,
                    characterName = characterName,
                    costumeId = costumeId,
                    costumeName = costumes.optString(costumeId, costumeId),
                    modelConfig = config,
                )
            }
        }
        return result.sortedWith(compareBy<OutfitModel> { it.characterName }.thenBy { it.costumeName })
    }

    private fun findModelConfig(costumeDir: File): File? {
        if (!costumeDir.isDirectory) return null
        val files = costumeDir.listFiles().orEmpty()
        files.firstOrNull { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }?.let { return it }
        files.firstOrNull { it.isFile && it.name.equals("model.json", ignoreCase = true) }?.let { return it }
        return files.firstOrNull { it.isDirectory }?.let { findModelConfig(it) }
    }
}
