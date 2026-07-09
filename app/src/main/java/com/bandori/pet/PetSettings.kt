package com.bandori.pet

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bandori.pet.data.DataRepository
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.live2d.Live2DTransform
import org.json.JSONArray
import org.json.JSONObject

const val SETTINGS_PREFS = "bandori_pet_settings"
const val KEY_FPS_LIMIT = "fps_limit"
const val KEY_VSYNC_ENABLED = "vsync_enabled"
const val KEY_LIVE2D_BACKGROUND_URI = "live2d_background_uri"
const val KEY_WALLPAPER_BACKGROUND_URI = "wallpaper_background_uri"
const val KEY_SELECTED_CHARACTER_ID = "selected_character_id"
const val KEY_SELECTED_MODEL_ASSET_PATH = "selected_model_asset_path"
const val KEY_WALLPAPER_ENABLED = "wallpaper_enabled"
const val KEY_WALLPAPER_OFFSET_X = "wallpaper_offset_x"
const val KEY_WALLPAPER_OFFSET_Y = "wallpaper_offset_y"
const val KEY_WALLPAPER_SCALE = "wallpaper_scale"
const val KEY_DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
const val KEY_DARK_MODE = "dark_mode"
const val KEY_FLOATING_OVERLAY_ENABLED = "floating_overlay_enabled"
const val KEY_FLOATING_OVERLAY_LOCKED = "floating_overlay_locked"
const val KEY_FLOATING_OVERLAY_ITEMS = "floating_overlay_items"

private const val DEFAULT_FLOATING_OVERLAY_X = 48
private const val DEFAULT_FLOATING_OVERLAY_Y = 160
private const val DEFAULT_FLOATING_OVERLAY_WIDTH = 360
private const val DEFAULT_FLOATING_OVERLAY_HEIGHT = 520
private const val FLOATING_OVERLAY_CASCADE_OFFSET = 36

data class FloatingLive2DItem(
    val id: String,
    val model: ModelChoice,
    val x: Int = DEFAULT_FLOATING_OVERLAY_X,
    val y: Int = DEFAULT_FLOATING_OVERLAY_Y,
    val width: Int = DEFAULT_FLOATING_OVERLAY_WIDTH,
    val height: Int = DEFAULT_FLOATING_OVERLAY_HEIGHT,
)

data class FloatingOverlaySettings(
    val enabled: Boolean = false,
    val locked: Boolean = true,
    val items: List<FloatingLive2DItem> = emptyList(),
) {
    fun save(context: Context) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FLOATING_OVERLAY_ENABLED, enabled)
            .putBoolean(KEY_FLOATING_OVERLAY_LOCKED, locked)
            .putString(KEY_FLOATING_OVERLAY_ITEMS, encodeFloatingItems(items))
            .apply()
    }

    companion object {
        fun load(context: Context): FloatingOverlaySettings {
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            return FloatingOverlaySettings(
                enabled = prefs.getBoolean(KEY_FLOATING_OVERLAY_ENABLED, false),
                locked = prefs.getBoolean(KEY_FLOATING_OVERLAY_LOCKED, true),
                items = decodeFloatingItems(prefs.getString(KEY_FLOATING_OVERLAY_ITEMS, null)),
            )
        }
    }
}

enum class DarkModeSetting(val value: String) {
    On("on"),
    Off("off"),
    System("system"),
    ;

    companion object {
        fun fromValue(value: String?): DarkModeSetting = entries.firstOrNull { it.value == value } ?: System
    }
}

data class ThemeSettings(
    val dynamicColorEnabled: Boolean = false,
    val darkMode: DarkModeSetting = DarkModeSetting.System,
) {
    fun save(context: Context) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DYNAMIC_COLOR_ENABLED, dynamicColorEnabled)
            .putString(KEY_DARK_MODE, darkMode.value)
            .apply()
    }

    companion object {
        fun load(context: Context): ThemeSettings {
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            return ThemeSettings(
                dynamicColorEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR_ENABLED, false),
                darkMode = DarkModeSetting.fromValue(prefs.getString(KEY_DARK_MODE, null)),
            )
        }
    }
}

fun DarkModeSetting.resolveDarkTheme(systemDark: Boolean): Boolean = when (this) {
    DarkModeSetting.On -> true
    DarkModeSetting.Off -> false
    DarkModeSetting.System -> systemDark
}

data class RenderSettings(
    val fpsLimit: Int = 60,
    val vsyncEnabled: Boolean = true,
    val backgroundUri: String? = null,
) {
    fun save(context: Context) {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS_LIMIT, fpsLimit)
            .putBoolean(KEY_VSYNC_ENABLED, vsyncEnabled)
            .apply {
                if (backgroundUri.isNullOrBlank()) {
                    remove(KEY_LIVE2D_BACKGROUND_URI)
                } else {
                    putString(KEY_LIVE2D_BACKGROUND_URI, backgroundUri)
                }
            }
            .apply()
    }

    companion object {
        fun load(context: Context): RenderSettings {
            val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            return RenderSettings(
                fpsLimit = prefs.getInt(KEY_FPS_LIMIT, 60).coerceIn(15, 120),
                vsyncEnabled = prefs.getBoolean(KEY_VSYNC_ENABLED, true),
                backgroundUri = prefs.getString(KEY_LIVE2D_BACKGROUND_URI, null),
            )
        }
    }
}

fun loadSelectedCharacterId(context: Context): String =
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_CHARACTER_ID, "kasumi") ?: "kasumi"

fun loadSelectedModelAssetPath(context: Context): String? =
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_MODEL_ASSET_PATH, null)

fun saveModelSelection(context: Context, characterId: String, model: ModelChoice?) {
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SELECTED_CHARACTER_ID, characterId)
        .apply {
            if (model == null) {
                remove(KEY_SELECTED_MODEL_ASSET_PATH)
            } else {
                putString(KEY_SELECTED_MODEL_ASSET_PATH, model.modelAssetPath)
            }
        }
        .apply()
}

fun loadPersistedModelChoice(context: Context): ModelChoice? {
    val repository = DataRepository(context)
    val data = repository.load()
    val selectedCharacterId = loadSelectedCharacterId(context)
    val activeCharacterId = when {
        data.characters.containsKey(selectedCharacterId) -> selectedCharacterId
        data.characters.containsKey("kasumi") -> "kasumi"
        else -> data.bands.firstOrNull()
            ?.characters
            ?.firstOrNull { it in data.characters }
            ?: data.characters.keys.firstOrNull()
    } ?: return null
    val models = data.characters[activeCharacterId]?.let { repository.availableModels(it) }.orEmpty()
    val selectedModelPath = loadSelectedModelAssetPath(context)
    return selectedModelPath?.let { path -> models.firstOrNull { it.modelAssetPath == path } }
        ?: models.firstOrNull()
}

fun persistBackgroundUri(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

fun isWallpaperEnabled(context: Context): Boolean =
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_WALLPAPER_ENABLED, false)

fun setWallpaperEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_WALLPAPER_ENABLED, enabled)
        .apply()
}

fun addFloatingLive2DItem(context: Context, model: ModelChoice): FloatingOverlaySettings {
    val settings = FloatingOverlaySettings.load(context)
    val index = settings.items.size
    val item = FloatingLive2DItem(
        id = "${System.currentTimeMillis()}_$index",
        model = model,
        x = DEFAULT_FLOATING_OVERLAY_X + index * FLOATING_OVERLAY_CASCADE_OFFSET,
        y = DEFAULT_FLOATING_OVERLAY_Y + index * FLOATING_OVERLAY_CASCADE_OFFSET,
    )
    return settings.copy(items = settings.items + item).also { it.save(context) }
}

fun resetFloatingLive2DItemPositions(context: Context): FloatingOverlaySettings {
    val settings = FloatingOverlaySettings.load(context)
    return settings.copy(
        items = settings.items.mapIndexed { index, item ->
            item.copy(
                x = DEFAULT_FLOATING_OVERLAY_X + index * FLOATING_OVERLAY_CASCADE_OFFSET,
                y = DEFAULT_FLOATING_OVERLAY_Y + index * FLOATING_OVERLAY_CASCADE_OFFSET,
            )
        },
    ).also { it.save(context) }
}

fun removeFloatingLive2DItem(context: Context, itemId: String): FloatingOverlaySettings {
    val settings = FloatingOverlaySettings.load(context)
    return settings.copy(items = settings.items.filterNot { it.id == itemId }).also { it.save(context) }
}

fun saveFloatingLive2DItemBounds(context: Context, itemId: String, x: Int, y: Int, width: Int, height: Int) {
    val settings = FloatingOverlaySettings.load(context)
    settings.copy(
        items = settings.items.map { item ->
            if (item.id == itemId) {
                item.copy(
                    x = x,
                    y = y,
                    width = width.coerceIn(180, 1200),
                    height = height.coerceIn(240, 1600),
                )
            } else {
                item
            }
        },
    ).save(context)
}

fun loadWallpaperBackgroundUri(context: Context): String? =
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_WALLPAPER_BACKGROUND_URI, null)

fun saveWallpaperBackgroundUri(context: Context, uri: String?) {
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .apply {
            if (uri.isNullOrBlank()) {
                remove(KEY_WALLPAPER_BACKGROUND_URI)
            } else {
                putString(KEY_WALLPAPER_BACKGROUND_URI, uri)
            }
        }
        .apply()
}

fun loadWallpaperTransform(context: Context): Live2DTransform {
    val prefs = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    return Live2DTransform(
        offsetX = prefs.getFloat(KEY_WALLPAPER_OFFSET_X, 0f),
        offsetY = prefs.getFloat(KEY_WALLPAPER_OFFSET_Y, 0f),
        scale = prefs.getFloat(KEY_WALLPAPER_SCALE, 1f).coerceIn(0.4f, 3f),
    )
}

fun saveWallpaperTransform(context: Context, transform: Live2DTransform) {
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(KEY_WALLPAPER_OFFSET_X, transform.offsetX)
        .putFloat(KEY_WALLPAPER_OFFSET_Y, transform.offsetY)
        .putFloat(KEY_WALLPAPER_SCALE, transform.scale.coerceIn(0.4f, 3f))
        .apply()
}

private fun encodeFloatingItems(items: List<FloatingLive2DItem>): String {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("characterId", item.model.characterId)
                .put("characterName", item.model.characterName)
                .put("costumeId", item.model.costumeId)
                .put("costumeName", item.model.costumeName)
                .put("modelAssetPath", item.model.modelAssetPath)
                .put("x", item.x)
                .put("y", item.y)
                .put("width", item.width)
                .put("height", item.height),
        )
    }
    return array.toString()
}

private fun decodeFloatingItems(value: String?): List<FloatingLive2DItem> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val model = ModelChoice(
                    characterId = item.getString("characterId"),
                    characterName = item.getString("characterName"),
                    costumeId = item.getString("costumeId"),
                    costumeName = item.getString("costumeName"),
                    modelAssetPath = item.getString("modelAssetPath"),
                )
                add(
                    FloatingLive2DItem(
                        id = item.optString("id", "${System.currentTimeMillis()}_$index"),
                        model = model,
                        x = item.optInt("x", DEFAULT_FLOATING_OVERLAY_X),
                        y = item.optInt("y", DEFAULT_FLOATING_OVERLAY_Y),
                        width = item.optInt("width", DEFAULT_FLOATING_OVERLAY_WIDTH).coerceIn(180, 1200),
                        height = item.optInt("height", DEFAULT_FLOATING_OVERLAY_HEIGHT).coerceIn(240, 1600),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
