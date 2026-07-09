package com.bandori.pet

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bandori.pet.data.DataRepository
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.live2d.Live2DTransform

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
