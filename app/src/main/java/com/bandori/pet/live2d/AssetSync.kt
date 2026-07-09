package com.bandori.pet.live2d

import android.content.Context
import com.bandori.pet.data.ZstModelArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AssetSync {
    private val runtimeCopyLock = Any()

    suspend fun prepareModel(context: Context, modelAssetPath: String): PreparedModel = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "live2d_assets")
        val runtimeRoot = File(root, "third_party/Live2D-v2-Lua")
        copyRuntimeIfNeeded(context, runtimeRoot)

        val archive = ZstModelArchive.readModelPrefix(context, modelAssetPath)
        if (archive != null) {
            PreparedModel(
                runtimeRoot = runtimeRoot.absolutePath,
                modelPath = archive.modelPath,
                resourcePaths = archive.resources.keys.toList(),
                resourceBytes = archive.resources.values.toList(),
            )
        } else {
            val modelDir = modelAssetPath.substringBeforeLast('/')
            val resourceMap = readAssetDirFiles(context, modelDir, File(root, modelDir))
            PreparedModel(
                runtimeRoot = runtimeRoot.absolutePath,
                modelPath = File(root, modelAssetPath).absolutePath,
                resourcePaths = resourceMap.keys.toList(),
                resourceBytes = resourceMap.values.toList(),
            )
        }
    }

    private fun copyRuntimeIfNeeded(context: Context, runtimeRoot: File) {
        synchronized(runtimeCopyLock) {
            val marker = File(runtimeRoot, ".copied")
            val packageTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (marker.exists() && marker.readText() == packageTime) return

            val parent = runtimeRoot.parentFile ?: return
            parent.mkdirs()
            val tempRoot = File(parent, "${runtimeRoot.name}.tmp")
            if (tempRoot.exists()) tempRoot.deleteRecursively()
            copyTree(context, "third_party/Live2D-v2-Lua", tempRoot)
            File(tempRoot, ".copied").writeText(packageTime)

            if (runtimeRoot.exists()) runtimeRoot.deleteRecursively()
            if (!tempRoot.renameTo(runtimeRoot)) {
                tempRoot.copyRecursively(runtimeRoot, overwrite = true)
                tempRoot.deleteRecursively()
            }
        }
    }

    private fun copyTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyTree(context, "$assetPath/$child", File(target, child))
        }
    }

    private fun readAssetDirFiles(context: Context, assetDir: String, targetDir: File): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        collectAssetFiles(context, assetDir, targetDir, result)
        return result
    }

    private fun collectAssetFiles(context: Context, assetPath: String, targetFile: File, result: MutableMap<String, ByteArray>) {
        val entries = context.assets.list(assetPath)
        if (entries == null) {
            runCatching {
                result[targetFile.absolutePath] = context.assets.open(assetPath).use { it.readBytes() }
            }
            return
        }
        if (entries.isEmpty()) return
        for (entry in entries) {
            collectAssetFiles(context, "$assetPath/$entry", File(targetFile, entry), result)
        }
    }
}

data class PreparedModel(
    val runtimeRoot: String,
    val modelPath: String,
    val resourcePaths: List<String> = emptyList(),
    val resourceBytes: List<ByteArray> = emptyList(),
)
