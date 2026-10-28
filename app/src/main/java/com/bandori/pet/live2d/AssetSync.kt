package com.bandori.pet.live2d

import android.content.Context
import com.bandori.pet.data.ZstModelArchive
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AssetSync {
    suspend fun prepareModel(context: Context, modelAssetPath: String): PreparedModel = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "live2d_assets")

        val archive = ZstModelArchive.readModelPrefix(context, modelAssetPath)
        if (archive != null) {
            PreparedModel(
                modelPath = archive.modelPath,
                resourcePaths = archive.resources.keys.toList(),
                resourceBytes = archive.resources.values.toList(),
            )
        } else {
            val modelDir = modelAssetPath.substringBeforeLast('/')
            val resourceMap = readAssetDirFiles(context, modelDir, File(root, modelDir))
            PreparedModel(
                modelPath = File(root, modelAssetPath).absolutePath,
                resourcePaths = resourceMap.keys.toList(),
                resourceBytes = resourceMap.values.toList(),
            )
        }
    }

    private fun readAssetDirFiles(context: Context, assetDir: String, targetDir: File): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        collectAssetFiles(context, assetDir, targetDir, result)
        return result
    }

    private fun collectAssetFiles(context: Context, assetPath: String, targetFile: File, result: MutableMap<String, ByteArray>) {
        val entries = context.assets.list(assetPath)
        if (entries.isNullOrEmpty()) {
            runCatching {
                result[targetFile.absolutePath] = context.assets.open(assetPath).use { it.readBytes() }
            }
            return
        }
        for (entry in entries) {
            collectAssetFiles(context, "$assetPath/$entry", File(targetFile, entry), result)
        }
    }
}

data class PreparedModel(
    val modelPath: String,
    val resourcePaths: List<String> = emptyList(),
    val resourceBytes: List<ByteArray> = emptyList(),
)
