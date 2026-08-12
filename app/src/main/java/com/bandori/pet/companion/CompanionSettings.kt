package com.bandori.pet.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class PairingPayload(
    val version: Int,
    val instanceId: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val pinSha256: String,
    val token: String,
    val expiresAt: Long,
) {
    fun endpoint(hostOverride: String? = null): String {
        val rawHost = hostOverride?.trim()?.takeIf(String::isNotEmpty) ?: hosts.firstOrNull().orEmpty()
        val host = if (':' in rawHost && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
        return "wss://$host:$port/v1/ws"
    }

    companion object {
        fun parse(value: String): PairingPayload {
            val uri = URI(value.trim())
            require(uri.scheme == "bandoripet" && uri.host == "pair") { "Invalid pairing URI" }
            val encoded = uri.rawQuery.orEmpty().split('&').asSequence()
                .mapNotNull { item -> item.split('=', limit = 2).takeIf { it.size == 2 } }
                .firstOrNull { it[0] == "data" }
                ?.get(1)
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                ?: error("Missing pairing data")
            val json = JSONObject(String(java.util.Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
            val hostsJson = json.getJSONArray("hosts")
            val hosts = buildList {
                for (index in 0 until hostsJson.length()) hostsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
            return PairingPayload(
                version = json.getInt("v"),
                instanceId = json.getString("instanceId"),
                name = json.optString("name", "BandoriPet Desktop"),
                hosts = hosts,
                port = json.getInt("port"),
                pinSha256 = json.getString("pinSha256"),
                token = json.getString("token"),
                expiresAt = json.getLong("expiresAt"),
            ).also {
                require(it.version == 1) { "Unsupported protocol version" }
                require(it.hosts.isNotEmpty()) { "No desktop address" }
                require(it.expiresAt >= System.currentTimeMillis()) { "Pairing code expired" }
            }
        }
    }
}

data class StoredDesktop(
    val instanceId: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val pinSha256: String,
    val deviceId: String,
    val credential: String,
)

object CompanionSettings {
    private const val PREFS = "bandori_pet_companion"
    private const val KEY_DESKTOP = "desktop"
    private const val KEY_SECRET = "credential"
    private const val KEY_REMOTE_MODE = "remote_mode"
    private const val KEY_TTS_MUTED = "tts_muted"

    private fun credentialAlias(instanceId: String): String = "bandori_pet_companion_${instanceId.replace("-", "").take(32)}"

    fun load(context: Context): StoredDesktop? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONObject(prefs.getString(KEY_DESKTOP, null) ?: return null)
        val hostsJson = json.getJSONArray("hosts")
        val hosts = buildList {
            for (index in 0 until hostsJson.length()) add(hostsJson.getString(index))
        }
        require(hosts.isNotEmpty())
        StoredDesktop(
            instanceId = json.getString("instanceId"),
            name = json.getString("name"),
            hosts = hosts,
            port = json.getInt("port"),
            pinSha256 = json.getString("pinSha256"),
            deviceId = json.getString("deviceId"),
            credential = CredentialVault.decrypt(
                credentialAlias(json.getString("instanceId")),
                prefs.getString(KEY_SECRET, null) ?: return null,
            ),
        )
    }.getOrNull()

    fun save(context: Context, payload: PairingPayload, deviceId: String, credential: String) {
        val hosts = org.json.JSONArray().apply { payload.hosts.forEach(::put) }
        val desktop = JSONObject()
            .put("instanceId", payload.instanceId)
            .put("name", payload.name)
            .put("hosts", hosts)
            .put("port", payload.port)
            .put("pinSha256", payload.pinSha256)
            .put("deviceId", deviceId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DESKTOP, desktop.toString())
            .putString(KEY_SECRET, CredentialVault.encrypt(credentialAlias(payload.instanceId), credential))
            .apply()
    }

    fun updateHost(context: Context, host: String) {
        if (load(context) == null) return
        val normalized = host.trim().removePrefix("[").removeSuffix("]")
        if (normalized.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONObject(prefs.getString(KEY_DESKTOP, null) ?: return)
        json.put("hosts", org.json.JSONArray().put(normalized))
        prefs.edit().putString(KEY_DESKTOP, json.toString()).apply()
    }

    fun forget(context: Context) {
        CompanionClient.shared(context).disconnect()
        load(context)?.let { CredentialVault.delete(credentialAlias(it.instanceId)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CompanionRuntimeStatus.remoteEnabled.value = false
        CompanionRuntimeStatus.ttsMuted.value = false
        CompanionRuntimeStatus.stopAudioEvents.tryEmit(Unit)
        CompanionRuntimeStatus.forgetEvents.tryEmit(Unit)
    }

    fun remoteMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REMOTE_MODE, false)

    fun setRemoteMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REMOTE_MODE, enabled).apply()
    }

    fun ttsMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TTS_MUTED, false)

    fun setTtsMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TTS_MUTED, muted).apply()
        CompanionRuntimeStatus.ttsMuted.value = muted
    }

    fun newDeviceId(): String = UUID.randomUUID().toString()

    fun newCredential(): String {
        val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

private object CredentialVault {
    private const val KEYSTORE = "AndroidKeyStore"

    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(alias: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(alias: String, value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        require(data.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), StandardCharsets.UTF_8)
    }

    fun delete(alias: String) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
