package com.bandori.pet.data

import android.content.Context
import com.bandori.pet.I18n
import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ZstModelArchive {
    private const val INDEX_MEMBER = ".bandori_zst_index.json"
    private const val LOGICAL_MODELS_ROOT = "models/"
    private const val VIRTUAL_ROOT = "archive://"
    private val indexCache = mutableMapOf<String, Set<String>>()

    fun assetExists(context: Context, logicalPath: String): Boolean = resolve(context, logicalPath) != null

    fun readLogicalPath(context: Context, logicalPath: String): ByteArray? {
        val resolved = resolve(context, logicalPath) ?: return null
        return readEntries(context, resolved.archiveAssetPath, setOf(resolved.innerPath))[resolved.virtualPath]
    }

    fun listCharacters(context: Context): List<String> = runCatching {
        val bundledCharacters = context.assets.list(LOGICAL_MODELS_ROOT.trimEnd('/')).orEmpty()
            .mapNotNull { entry -> entry.takeIf { it.endsWith(".zst") }?.removeSuffix(".zst") }
        val downloadedCharacters = downloadedModelsDir(context).listFiles { file ->
            file.isFile && file.name.endsWith(".zst")
        }.orEmpty().map { file -> file.name.removeSuffix(".zst") }
        (bundledCharacters + downloadedCharacters).filter { it.isNotBlank() }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun downloadCharacter(
        context: Context,
        characterId: String,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): File {
        val target = downloadedArchiveFile(context, characterId)
        val parent = target.parentFile ?: throw IOException(I18n.t("error_create_download_dir"))
        if (!parent.exists() && !parent.mkdirs()) throw IOException(I18n.t("error_create_download_dir"))
        val temp = File(parent, "${target.name}.download")
        if (temp.exists()) temp.delete()

        val connection = URL(downloadUrl(characterId)).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException(I18n.t("error_download_http", code))
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val startedAt = System.nanoTime()
                    var lastReportAt = 0L
                    var downloadedBytes = 0L

                    fun reportProgress(force: Boolean = false) {
                        val now = System.nanoTime()
                        if (!force && now - lastReportAt < 250_000_000L) return
                        val elapsedSeconds = ((now - startedAt).coerceAtLeast(1L)) / 1_000_000_000.0
                        onProgress?.invoke(
                            DownloadProgress(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                bytesPerSecond = downloadedBytes / elapsedSeconds,
                            )
                        )
                        lastReportAt = now
                    }

                    reportProgress(force = true)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        reportProgress()
                    }
                    reportProgress(force = true)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (temp.length() == 0L) {
            temp.delete()
            throw IOException(I18n.t("error_download_empty"))
        }
        if (target.exists() && !target.delete()) throw IOException(I18n.t("error_replace_model_file"))
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            if (!temp.delete()) temp.deleteOnExit()
        }
        synchronized(indexCache) { indexCache.remove(archiveAssetPath(characterId)) }
        return target
    }

    fun hasDownloadedCharacter(context: Context, characterId: String): Boolean =
        downloadedArchiveFile(context, characterId).isFile

    fun deleteDownloadedCharacter(context: Context, characterId: String): Boolean {
        val deleted = downloadedArchiveFile(context, characterId).delete()
        if (deleted) synchronized(indexCache) { indexCache.remove(archiveAssetPath(characterId)) }
        return deleted
    }

    fun listCostumes(context: Context, characterId: String): List<String> {
        val archiveAssetPath = archiveAssetPath(characterId)
        return runCatching {
            index(context, archiveAssetPath)
                .mapNotNull { entry -> entry.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotBlank() && it != entry } }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    fun listFiles(context: Context, logicalDir: String): List<String> {
        val location = logicalLocation(logicalDir) ?: return emptyList()
        val prefix = location.innerPath.trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        return runCatching {
            index(context, location.archiveAssetPath)
                .mapNotNull { entry ->
                    if (!entry.startsWith(prefix)) return@mapNotNull null
                    val rest = entry.removePrefix(prefix)
                    rest.takeIf { it.isNotBlank() && !it.contains('/') }
                }
                .sorted()
        }.getOrDefault(emptyList())
    }

    fun resolve(context: Context, logicalPath: String): ResolvedEntry? {
        val location = logicalLocation(logicalPath) ?: return null
        return runCatching {
            if (index(context, location.archiveAssetPath).contains(location.innerPath)) {
                ResolvedEntry(
                    archiveAssetPath = location.archiveAssetPath,
                    innerPath = location.innerPath,
                    virtualPath = virtualPath(location.characterId, location.innerPath),
                )
            } else {
                null
            }
        }.getOrNull()
    }

    fun readModelPrefix(context: Context, modelAssetPath: String): PreparedArchive? {
        val resolved = resolve(context, modelAssetPath) ?: return null
        val modelDir = resolved.innerPath.substringBeforeLast('/', missingDelimiterValue = "")
        val prefix = if (modelDir.isEmpty()) "" else "$modelDir/"
        val wanted = index(context, resolved.archiveAssetPath).filter { it.startsWith(prefix) }.toSet()
        val resources = readEntries(context, resolved.archiveAssetPath, wanted)
        return PreparedArchive(modelPath = resolved.virtualPath, resources = resources)
    }

    private fun logicalLocation(logicalPath: String): LogicalLocation? {
        val normalized = normalize(logicalPath).removePrefix(VIRTUAL_ROOT)
        if (!normalized.startsWith(LOGICAL_MODELS_ROOT)) return null
        val rest = normalized.removePrefix(LOGICAL_MODELS_ROOT)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val characterId = rest.substring(0, slash)
        val innerPath = rest.substring(slash + 1)
        if (innerPath.isBlank()) return null
        return LogicalLocation(characterId, archiveAssetPath(characterId), innerPath)
    }

    private fun archiveAssetPath(characterId: String): String = "models/$characterId.zst"

    private fun virtualPath(characterId: String, innerPath: String): String = "$VIRTUAL_ROOT${archiveAssetPath(characterId).removeSuffix(".zst")}/$innerPath"

    private fun index(context: Context, archiveAssetPath: String): Set<String> = synchronized(indexCache) {
        indexCache.getOrPut(archiveAssetPath) { readIndex(context, archiveAssetPath) }
    }

    private fun readIndex(context: Context, archiveAssetPath: String): Set<String> {
        val scannedFiles = linkedSetOf<String>()
        openArchive(context, archiveAssetPath).use { input ->
            while (true) {
                val entry = nextTarEntry(input) ?: break
                if (entry.name == INDEX_MEMBER) {
                    val data = input.readExactly(entry.size)
                    skipPadding(input, entry.size)
                    val indexedFiles = runCatching {
                        val files = JSONObject(data.toString(Charsets.UTF_8)).getJSONArray("files")
                        buildSet {
                            for (i in 0 until files.length()) add(normalize(files.getString(i)))
                        }
                    }.getOrNull()
                    if (indexedFiles != null) {
                        return indexedFiles
                    }
                } else {
                    if (entry.isFile) scannedFiles.add(entry.name)
                    skipEntry(input, entry.size)
                }
            }
        }
        return scannedFiles
    }

    private fun readEntries(context: Context, archiveAssetPath: String, innerPaths: Set<String>): Map<String, ByteArray> {
        if (innerPaths.isEmpty()) return emptyMap()
        val location = archiveAssetPath.removePrefix(LOGICAL_MODELS_ROOT).removeSuffix(".zst")
        val wanted = innerPaths.mapTo(mutableSetOf()) { normalize(it) }
        val result = linkedMapOf<String, ByteArray>()

        openArchive(context, archiveAssetPath).use { input ->
            while (wanted.isNotEmpty()) {
                val entry = nextTarEntry(input) ?: break
                val normalizedName = normalize(entry.name)
                if (normalizedName in wanted && entry.isFile) {
                    result["$VIRTUAL_ROOT$LOGICAL_MODELS_ROOT$location/$normalizedName"] = input.readExactly(entry.size)
                    wanted.remove(normalizedName)
                    skipPadding(input, entry.size)
                } else {
                    skipEntry(input, entry.size)
                }
            }
        }
        return result
    }

    private fun openArchive(context: Context, archiveAssetPath: String): InputStream {
        val characterId = archiveAssetPath.removePrefix(LOGICAL_MODELS_ROOT).removeSuffix(".zst")
        val downloaded = downloadedArchiveFile(context, characterId)
        val input = if (downloaded.isFile) downloaded.inputStream() else context.assets.open(archiveAssetPath)
        return ZstdInputStream(input)
    }

    private fun downloadedModelsDir(context: Context): File = File(context.filesDir, "models")

    private fun downloadedArchiveFile(context: Context, characterId: String): File =
        File(downloadedModelsDir(context), "${normalize(characterId).substringAfterLast('/')}.zst")

    private fun downloadUrl(characterId: String): String {
        val encodedName = URLEncoder.encode(characterId, Charsets.UTF_8.name()).replace("+", "%20")
        return "https://modelscope.cn/datasets/HELPMEEADICE/BanG-Dream-Live2D/resolve/master/models/$encodedName.zst"
    }

    private fun nextTarEntry(input: InputStream): TarEntry? {
        val header = ByteArray(512)
        val read = input.readFully(header, allowEof = true)
        if (read == 0 || header.all { it == 0.toByte() }) return null
        if (read != header.size) throw EOFException("Truncated tar header")

        val name = parseString(header, 0, 100)
        val prefix = parseString(header, 345, 155)
        val fullName = if (prefix.isBlank()) name else "$prefix/$name"
        val size = parseOctal(header, 124, 12)
        val type = header[156].toInt().toChar()
        return TarEntry(normalize(fullName), size, type == '0' || type == '\u0000')
    }

    private fun InputStream.readExactly(size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw IllegalArgumentException("Tar entry too large: $size")
        val output = ByteArrayOutputStream(size.toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Truncated tar entry")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
        return output.toByteArray()
    }

    private fun InputStream.readFully(buffer: ByteArray, allowEof: Boolean = false): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                if (allowEof && offset == 0) return 0
                throw EOFException("Unexpected end of stream")
            }
            offset += read
        }
        return offset
    }

    private fun skipEntry(input: InputStream, size: Long) {
        skipFully(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (512 - (size % 512)) % 512
        if (padding > 0) skipFully(input, padding)
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Unexpected end of stream")
                remaining -= read.toLong()
            }
        }
    }

    private fun parseString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: (offset + length)
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in offset until offset + length) {
            val c = bytes[i].toInt().toChar()
            if (c == '\u0000' || c == ' ') continue
            if (c !in '0'..'7') break
            value = value * 8 + (c - '0')
        }
        return value
    }

    private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./")

    data class PreparedArchive(
        val modelPath: String,
        val resources: Map<String, ByteArray>,
    )

    data class ResolvedEntry(
        val archiveAssetPath: String,
        val innerPath: String,
        val virtualPath: String,
    )

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double,
    )

    private data class LogicalLocation(
        val characterId: String,
        val archiveAssetPath: String,
        val innerPath: String,
    )

    private data class TarEntry(
        val name: String,
        val size: Long,
        val isFile: Boolean,
    )
}
