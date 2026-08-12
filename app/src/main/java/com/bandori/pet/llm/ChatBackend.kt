package com.bandori.pet.llm

import android.content.Context
import com.bandori.pet.companion.CompanionClient
import com.bandori.pet.companion.CompanionConnectionState
import com.bandori.pet.companion.CompanionInboundFrame
import com.bandori.pet.companion.CompanionRuntimeStatus
import com.bandori.pet.companion.CompanionSettings
import com.bandori.pet.companion.RemoteTtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The UI-facing chat mode boundary. Local storage and desktop mirror state never cross it. */
sealed interface ChatBackend {
    val mode: ChatBackendMode
    fun deactivate()
}

class LocalChatBackend(context: Context) : ChatBackend {
    override val mode = ChatBackendMode.Local
    internal val history = ChatHistoryRepository(context)
    internal val prompts = CharacterPromptRepository(context)
    internal val client = LlmChatClient()

    override fun deactivate() = Unit
}

class DesktopCompanionBackend private constructor(context: Context, scope: CoroutineScope) : ChatBackend {
    override val mode = ChatBackendMode.Desktop
    private val appContext = context.applicationContext
    internal val client = CompanionClient.shared(appContext)
    internal val ttsPlayer = RemoteTtsPlayer(scope)

    init {
        CompanionRuntimeStatus.ttsMuted.value = CompanionSettings.ttsMuted(appContext)
        scope.launch {
            client.inbound.collect { frame ->
                when (frame) {
                    is CompanionInboundFrame.Audio -> if (!CompanionRuntimeStatus.ttsMuted.value) {
                        ttsPlayer.accept(frame.bytes)
                    }
                    is CompanionInboundFrame.Event -> when (frame.name) {
                        "tts.started" -> ttsPlayer.configure(
                            frame.data.optInt("sampleRate", 24_000),
                            frame.data.optInt("channels", 1),
                        )
                        "tts.error", "tts.stopped", "profile.changed" -> ttsPlayer.stop()
                        else -> Unit
                    }
                }
            }
        }
        scope.launch {
            CompanionRuntimeStatus.ttsMuted.collect { muted -> if (muted) ttsPlayer.stop() }
        }
        scope.launch {
            client.state.collect { state ->
                if (state in setOf(
                        CompanionConnectionState.Disconnected,
                        CompanionConnectionState.ProfileUnavailable,
                        CompanionConnectionState.Error,
                    )) ttsPlayer.stop()
            }
        }
        scope.launch {
            CompanionRuntimeStatus.stopAudioEvents.collect { ttsPlayer.stop() }
        }
    }

    override fun deactivate() {
        client.disconnect()
        ttsPlayer.stop()
    }

    companion object {
        @Volatile private var sharedInstance: DesktopCompanionBackend? = null

        fun shared(context: Context): DesktopCompanionBackend = sharedInstance ?: synchronized(this) {
            sharedInstance ?: DesktopCompanionBackend(
                context.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            ).also { sharedInstance = it }
        }
    }
}
