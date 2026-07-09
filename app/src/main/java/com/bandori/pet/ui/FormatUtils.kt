package com.bandori.pet.ui

import com.bandori.pet.I18n
import com.bandori.pet.data.ModelChoice
import com.bandori.pet.data.ZstModelArchive
import java.util.Locale
import kotlin.math.roundToInt

val ModelChoice.isMoc3: Boolean
    get() = modelAssetPath.endsWith(".model3.json") || modelAssetPath.contains("moc3", ignoreCase = true)

fun formatTransferProgress(progress: ZstModelArchive.DownloadProgress): String {
    val downloaded = formatBytes(progress.downloadedBytes.toDouble())
    val total = progress.totalBytes.takeIf { it > 0 }?.let { formatBytes(it.toDouble()) } ?: I18n.t("model_unknown_size")
    val percent = progress.totalBytes.takeIf { it > 0 }
        ?.let { ((progress.downloadedBytes * 100.0) / it).roundToInt().coerceIn(0, 100) }
    return if (percent != null) "$downloaded / $total，$percent%" else "$downloaded / $total"
}

fun formatTransferSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond)}/s"

fun formatBytes(bytes: Double): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.coerceAtLeast(0.0)
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val valueText = if (value >= 10.0 || unitIndex == 0) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$valueText ${units[unitIndex]}"
}
