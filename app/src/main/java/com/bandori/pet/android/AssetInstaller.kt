package com.bandori.pet.android

import android.content.Context
import java.io.File

object AssetInstaller {
    fun install(context: Context): File {
        val targetRoot = File(context.filesDir, "live2d_runtime")
        val marker = File(targetRoot, ".installed-v1")
        if (marker.exists()) return targetRoot

        if (targetRoot.exists()) targetRoot.deleteRecursively()
        targetRoot.mkdirs()

        copyAssetPath(context, "outfit.json", File(targetRoot, "outfit.json"))
        copyAssetPath(context, "models", File(targetRoot, "models"))
        copyAssetPath(context, "live2d-lua", File(targetRoot, "live2d-lua"))
        marker.writeText("ok")
        return targetRoot
    }

    private fun copyAssetPath(context: Context, assetPath: String, out: File) {
        val children = context.assets.list(assetPath)?.toList().orEmpty()
        if (children.isEmpty()) {
            out.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        out.mkdirs()
        for (child in children) {
            val childAssetPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
            copyAssetPath(context, childAssetPath, File(out, child))
        }
    }
}
