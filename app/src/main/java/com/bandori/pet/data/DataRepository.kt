package com.bandori.pet.data

import android.content.Context
import com.bandori.pet.I18n
import org.json.JSONObject

class DataRepository(private val context: Context) {
    companion object {
        @Volatile
        private var cachedAppData: AppData? = null

        fun invalidateCache() {
            cachedAppData = null
        }
    }

    @Synchronized
    fun load(): AppData {
        cachedAppData?.let { return it }
        val data = loadInternal()
        cachedAppData = data
        return data
    }

    private fun loadInternal(): AppData {
        val bandsJson = JSONObject(readAssetText("band.json"))
        val bands = mutableListOf<Band>()
        bandsJson.getJSONArray("bands").let { array ->
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val characters = item.getJSONArray("characters")
                bands.add(
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

        val characterRoot = JSONObject(readAssetText("outfit.json")).getJSONObject("characters")
        val characters = linkedMapOf<String, CharacterInfo>()
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
            characters[id] = CharacterInfo(
                id = id,
                display = item.optString("display", id),
                costumes = costumeMap,
            )
        }

        val archiveCharacterIds = ZstModelArchive.listCharacters(context)
        for (characterId in archiveCharacterIds) {
            characters.getOrPut(characterId) {
                CharacterInfo(
                    id = characterId,
                    display = characterId,
                    costumes = emptyMap(),
                )
            }
        }

        val groupedCharacterIds = bands.flatMap { it.characters }.toSet()
        val ungroupedArchiveCharacterIds = archiveCharacterIds.filter { it !in groupedCharacterIds }
        if (ungroupedArchiveCharacterIds.isNotEmpty()) {
            val othersIndex = bands.indexOfFirst { it.id == "others" }
            if (othersIndex >= 0) {
                val others = bands[othersIndex]
                bands[othersIndex] = others.copy(characters = (others.characters + ungroupedArchiveCharacterIds).distinct())
            } else {
                bands.add(
                    Band(
                        id = "others",
                        display = I18n.t("model_band_others"),
                        logo = null,
                        characters = ungroupedArchiveCharacterIds,
                    ),
                )
            }
        }

        return AppData(bands = bands, characters = characters)
    }

    fun assetExists(path: String): Boolean = runCatching {
        context.assets.open(path).use { true }
    }.getOrDefault(false) || ZstModelArchive.assetExists(context, path)

    fun availableModels(character: CharacterInfo): List<ModelChoice> {
        val costumeChoices = if (character.costumes.isEmpty()) {
            linkedMapOf("live_default" to I18n.t("model_default_costume"))
        } else {
            character.costumes.toMutableMap()
        }

        val characterBase = "models/${character.id}"
        val localCostumes = (runCatching { context.assets.list(characterBase).orEmpty().toList() }
            .getOrDefault(emptyList()) + ZstModelArchive.listCostumes(context, character.id)).distinct().sorted()
        for (costumeId in localCostumes) {
            if (!costumeChoices.containsKey(costumeId)) {
                costumeChoices[costumeId] = costumeId
            }
        }

        return costumeChoices.mapNotNull { (costumeId, costumeName) ->
            val base = "$characterBase/$costumeId"
            val modelJson = findModelJson(base)
            val model3Json = findModel3Json(base)
            val modelPath = when {
                modelJson != null -> modelJson
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

    private fun findModelJson(base: String): String? = runCatching {
        val files = context.assets.list(base).orEmpty()
        files.firstOrNull { it == "model.json" }
            ?: files.firstOrNull { it.endsWith(".model.json") && !it.endsWith(".model3.json") }
    }.getOrNull()?.let { "$base/$it" }
        ?: ZstModelArchive.listFiles(context, base)
            .firstOrNull { it == "model.json" || (it.endsWith(".model.json") && !it.endsWith(".model3.json")) }
            ?.let { "$base/$it" }

    private fun findModel3Json(base: String): String? = runCatching {
        context.assets.list(base).orEmpty().firstOrNull { it.endsWith(".model3.json") }?.let { "$base/$it" }
    }.getOrNull()
        ?: ZstModelArchive.listFiles(context, base)
            .firstOrNull { it.endsWith(".model3.json") }
            ?.let { "$base/$it" }

    private fun readAssetText(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }
}
