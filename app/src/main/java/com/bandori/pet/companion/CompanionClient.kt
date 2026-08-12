package com.bandori.pet.companion

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

enum class CompanionConnectionState { Disconnected, Connecting, Connected, ProfileUnavailable, Error }

data class CompanionBackendStatus(
    val llm: String = "unconfigured",
    val tts: String = "unconfigured",
)

sealed interface CompanionInboundFrame {
    data class Event(val name: String, val data: JSONObject) : CompanionInboundFrame
    data class Audio(val bytes: ByteString) : CompanionInboundFrame
}

object CompanionRuntimeStatus {
    val connection = MutableStateFlow(CompanionConnectionState.Disconnected)
    val backends = MutableStateFlow(CompanionBackendStatus())
    val remoteEnabled = MutableStateFlow(false)
    val ttsMuted = MutableStateFlow(false)
    val forgetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnectEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopAudioEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

class CompanionClient private constructor(private val context: Context) {
    private val mutableState = MutableStateFlow(CompanionConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)
    private val mutableInbound = MutableSharedFlow<CompanionInboundFrame>(extraBufferCapacity = 96)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var lastEventSequence = 0L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectTask: Runnable? = null
    private var reconnectAttempt = 0
    private var allowReconnect = false
    private var hostIndex = 0
    private var expectedInstanceId = ""

    val state: StateFlow<CompanionConnectionState> = mutableState
    val events: SharedFlow<Pair<String, JSONObject>> = mutableEvents
    val inbound: SharedFlow<CompanionInboundFrame> = mutableInbound

    private fun updateState(value: CompanionConnectionState) {
        mutableState.value = value
        CompanionRuntimeStatus.connection.value = value
        if (value !in setOf(CompanionConnectionState.Connected, CompanionConnectionState.ProfileUnavailable)) {
            CompanionRuntimeStatus.backends.value = CompanionBackendStatus()
        }
    }

    fun connect(desktop: StoredDesktop? = CompanionSettings.load(context), force: Boolean = false) {
        if (!force && webSocket != null && mutableState.value in setOf(
                CompanionConnectionState.Connecting,
                CompanionConnectionState.Connected,
                CompanionConnectionState.ProfileUnavailable,
            )) return
        allowReconnect = true
        reconnectTask?.let(reconnectHandler::removeCallbacks)
        reconnectTask = null
        if (desktop == null) {
            updateState(CompanionConnectionState.Error)
            return
        }
        closeSocket(clearState = false)
        lastEventSequence = 0L
        expectedInstanceId = desktop.instanceId
        updateState(CompanionConnectionState.Connecting)
        client = pinnedClient(desktop.pinSha256)
        val request = Request.Builder()
            .url(endpoint(desktop.hosts[Math.floorMod(hostIndex, desktop.hosts.size)], desktop.port))
            .header("X-Bandori-Device", desktop.deviceId)
            .header("Authorization", "Bearer ${desktop.credential}")
            .build()
        webSocket = client!!.newWebSocket(request, listener())
    }

    fun disconnect(clearState: Boolean = true) {
        allowReconnect = false
        reconnectTask?.let(reconnectHandler::removeCallbacks)
        reconnectTask = null
        closeSocket(clearState)
    }

    private fun closeSocket(clearState: Boolean) {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        if (clearState) updateState(CompanionConnectionState.Disconnected)
    }

    private fun scheduleReconnect() {
        if (!allowReconnect || !CompanionSettings.remoteMode(context) || reconnectTask != null) return
        val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempt += 1
        reconnectTask = Runnable {
            reconnectTask = null
            if (allowReconnect) connect()
        }.also { reconnectHandler.postDelayed(it, delayMs) }
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val payload = JSONObject().put("v", 1).put("id", id).put("method", method).put("params", params)
        if (webSocket?.send(payload.toString()) != true) {
            pending.remove(id)
            error("NOT_CONNECTED")
        }
        return withTimeout(30_000) { deferred.await() }
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (this@CompanionClient.webSocket !== webSocket) return
            reconnectAttempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (payload.optInt("v", -1) != 1) {
                allowReconnect = false
                updateState(CompanionConnectionState.Error)
                pending.values.forEach { it.completeExceptionally(IllegalStateException("PROTOCOL_VERSION_MISMATCH")) }
                pending.clear()
                webSocket.close(4002, "protocol version mismatch")
                return
            }
            val id = payload.optString("id")
            if (id.isNotBlank()) {
                val deferred = pending.remove(id) ?: return
                if (payload.optBoolean("ok")) deferred.complete(payload.optJSONObject("result") ?: JSONObject())
                else deferred.completeExceptionally(IllegalStateException(payload.optJSONObject("error")?.optString("code") ?: "ERROR"))
                return
            }
            val event = payload.optString("event")
            val data = payload.optJSONObject("data") ?: JSONObject()
            val sequence = payload.optLong("seq", 0L)
            if (sequence > 0L && sequence <= lastEventSequence) return
            if (sequence > 0L) lastEventSequence = sequence
            if (event == "session.hello") {
                if (
                    data.optInt("protocolVersion", -1) != 1 ||
                    data.optString("desktopInstanceId") != expectedInstanceId
                ) {
                    allowReconnect = false
                    updateState(CompanionConnectionState.Error)
                    webSocket.close(4003, "desktop instance mismatch")
                    return
                }
                updateState(if (data.optBoolean("profileAvailable", true)) CompanionConnectionState.Connected else CompanionConnectionState.ProfileUnavailable)
            } else if (event == "profile.changed") {
                updateState(CompanionConnectionState.ProfileUnavailable)
            }
            if (event == "session.hello" || event == "capabilities.changed") {
                val capabilities = if (event == "session.hello") data.optJSONObject("capabilities") ?: JSONObject() else data
                CompanionRuntimeStatus.backends.value = CompanionBackendStatus(
                    llm = capabilities.optString("llmStatus", "unconfigured"),
                    tts = capabilities.optString("ttsStatus", "unconfigured"),
                )
            }
            mutableEvents.tryEmit(event to data)
            mutableInbound.tryEmit(CompanionInboundFrame.Event(event, data))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            mutableInbound.tryEmit(CompanionInboundFrame.Audio(bytes))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@CompanionClient.webSocket !== webSocket) return
            updateState(CompanionConnectionState.Error)
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
            if (generateSequence<Throwable>(t) { it.cause }.any { it is CertificateException }) {
                allowReconnect = false
                return
            }
            hostIndex += 1
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@CompanionClient.webSocket !== webSocket) return
            if (code == 4003) {
                allowReconnect = false
                updateState(CompanionConnectionState.Error)
                return
            }
            if (mutableState.value != CompanionConnectionState.Error) updateState(CompanionConnectionState.Disconnected)
            scheduleReconnect()
        }
    }

    companion object {
        @Volatile private var sharedInstance: CompanionClient? = null

        fun shared(context: Context): CompanionClient = sharedInstance ?: synchronized(this) {
            sharedInstance ?: CompanionClient(context.applicationContext).also { sharedInstance = it }
        }

        suspend fun pair(
            context: Context,
            payload: PairingPayload,
            deviceName: String = Build.MODEL,
            hostOverride: String? = null,
        ): StoredDesktop {
            val deviceId = CompanionSettings.newDeviceId()
            val credential = CompanionSettings.newCredential()
            val completed = CompletableDeferred<Unit>()
            val client = pinnedClient(payload.pinSha256)
            var socket: WebSocket? = null
            socket = client.newWebSocket(
                Request.Builder().url(payload.endpoint(hostOverride)).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val request = JSONObject()
                            .put("v", 1)
                            .put("id", UUID.randomUUID().toString())
                            .put("method", "pair.request")
                            .put("params", JSONObject()
                                .put("token", payload.token)
                                .put("deviceId", deviceId)
                                .put("deviceName", deviceName)
                                .put("credential", credential))
                        webSocket.send(request.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val response = runCatching { JSONObject(text) }.getOrNull() ?: return
                        if (response.optBoolean("ok")) completed.complete(Unit)
                        else completed.completeExceptionally(IllegalStateException(response.optJSONObject("error")?.optString("code") ?: "PAIRING_FAILED"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        completed.completeExceptionally(t)
                    }
                },
            )
            try {
                withTimeout(20_000) { completed.await() }
                CompanionSettings.save(context, payload, deviceId, credential)
                hostOverride?.takeIf(String::isNotBlank)?.let { CompanionSettings.updateHost(context, it) }
                return requireNotNull(CompanionSettings.load(context))
            } finally {
                socket?.close(1000, "paired")
                client.dispatcher.executorService.shutdown()
            }
        }

        private fun endpoint(hostValue: String, port: Int): String {
            val host = if (':' in hostValue && !hostValue.startsWith("[")) "[$hostValue]" else hostValue
            return "wss://$host:$port/v1/ws"
        }

        private fun pinnedClient(expectedPin: String): OkHttpClient {
            val trustManager = PinTrustManager(expectedPin)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            return OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

private class PinTrustManager(private val expectedPin: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        val actual = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedPin.toByteArray())) throw CertificateException("Desktop certificate pin mismatch")
        leaf.checkValidity()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
