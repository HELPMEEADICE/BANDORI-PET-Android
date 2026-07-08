package com.bandori.pet.live2d

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AssetSync {
    suspend fun prepareModel(context: Context, modelAssetPath: String): PreparedModel = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "live2d_assets")
        val runtimeRoot = File(root, "third_party/Live2D-v2-Lua")
        copyRuntimeIfNeeded(context, runtimeRoot)

        val modelDir = modelAssetPath.substringBeforeLast('/')
        copyTree(context, modelDir, File(root, modelDir))

        PreparedModel(
            runtimeRoot = runtimeRoot.absolutePath,
            modelPath = File(root, modelAssetPath).absolutePath,
        )
    }

    private fun copyRuntimeIfNeeded(context: Context, runtimeRoot: File) {
        val marker = File(runtimeRoot, ".copied")
        val packageTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == packageTime) return

        if (runtimeRoot.exists()) runtimeRoot.deleteRecursively()
        copyTree(context, "third_party/Live2D-v2-Lua", runtimeRoot)
        marker.writeText(packageTime)
    }

    private fun copyTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyTree(context, "$assetPath/$child", File(target, child))
        }
    }
}

data class PreparedModel(
    val runtimeRoot: String,
    val modelPath: String,
)
