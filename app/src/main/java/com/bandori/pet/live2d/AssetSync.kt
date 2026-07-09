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
        if (archive == null) {
            val modelDir = modelAssetPath.substringBeforeLast('/')
            copyTree(context, modelDir, File(root, modelDir))
        }

        PreparedModel(
            runtimeRoot = runtimeRoot.absolutePath,
            modelPath = archive?.modelPath ?: File(root, modelAssetPath).absolutePath,
            resourcePaths = archive?.resources?.keys?.toList().orEmpty(),
            resourceBytes = archive?.resources?.values?.toList().orEmpty(),
        )
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
}

data class PreparedModel(
    val runtimeRoot: String,
    val modelPath: String,
    val resourcePaths: List<String> = emptyList(),
    val resourceBytes: List<ByteArray> = emptyList(),
)
