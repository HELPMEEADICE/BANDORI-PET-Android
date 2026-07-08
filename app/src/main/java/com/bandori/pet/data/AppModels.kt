package com.bandori.pet.data

data class Band(
    val id: String,
    val display: String,
    val logo: String?,
    val characters: List<String>,
)

data class CharacterInfo(
    val id: String,
    val display: String,
    val costumes: Map<String, String>,
)

data class ModelChoice(
    val characterId: String,
    val characterName: String,
    val costumeId: String,
    val costumeName: String,
    val modelAssetPath: String,
) {
    val title: String = "$characterName / $costumeName"
}

data class AppData(
    val bands: List<Band>,
    val characters: Map<String, CharacterInfo>,
)
