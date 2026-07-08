package com.bandori.pet.data

import android.content.Context
import org.json.JSONObject

class DataRepository(private val context: Context) {
    fun load(): AppData {
        val bandsJson = JSONObject(readAssetText("band.json"))
        val bands = bandsJson.getJSONArray("bands").let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val characters = item.getJSONArray("characters")
                    add(
                        Band(
                            id = item.getString("id"),
                            display = item.getString("display"),
                            logo = item.optString("logo").takeIf { it.isNotBlank() },
                            characters = buildList {
                                for (characterIndex in 0 until characters.length()) {
                                    add(characters.getString(characterIndex))
                                }
                            },
                        ),
                    )
                }
            }
        }

        val characterRoot = JSONObject(readAssetText("outfit.json")).getJSONObject("characters")
        val characters = buildMap {
            val keys = characterRoot.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val item = characterRoot.getJSONObject(id)
                val costumesJson = item.optJSONObject("costumes") ?: JSONObject()
                val costumeMap = linkedMapOf<String, String>()
                val costumeKeys = costumesJson.keys()
                while (costumeKeys.hasNext()) {
                    val costumeId = costumeKeys.next()
                    costumeMap[costumeId] = costumesJson.optString(costumeId, costumeId)
                }
                put(
                    id,
                    CharacterInfo(
                        id = id,
                        display = item.optString("display", id),
                        costumes = costumeMap,
                    ),
                )
            }
        }

        return AppData(bands = bands, characters = characters)
    }

    fun assetExists(path: String): Boolean = runCatching {
        context.assets.open(path).use { true }
    }.getOrDefault(false)

    fun availableModels(character: CharacterInfo): List<ModelChoice> {
        val declaredCostumes = if (character.costumes.isEmpty()) {
            linkedMapOf("live_default" to "默认")
        } else {
            character.costumes
        }

        return declaredCostumes.mapNotNull { (costumeId, costumeName) ->
            val base = "models/${character.id}/$costumeId"
            val modelJson = "$base/model.json"
            val model3Json = findModel3Json(base)
            val modelPath = when {
                assetExists(modelJson) -> modelJson
                model3Json != null -> model3Json
                else -> null
            }
            modelPath?.let {
                ModelChoice(
                    characterId = character.id,
                    characterName = character.display,
                    costumeId = costumeId,
                    costumeName = costumeName,
                    modelAssetPath = it,
                )
            }
        }
    }

    private fun findModel3Json(base: String): String? = runCatching {
        context.assets.list(base).orEmpty().firstOrNull { it.endsWith(".model3.json") }?.let { "$base/$it" }
    }.getOrNull()

    private fun readAssetText(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
}
