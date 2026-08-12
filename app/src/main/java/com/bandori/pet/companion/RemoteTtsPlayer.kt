package com.bandori.pet.companion

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okio.ByteString

class RemoteTtsPlayer(private val scope: CoroutineScope) {
    private data class AudioChunk(val generation: Long, val pcm: ByteArray)

    private val chunks = Channel<AudioChunk>(Channel.BUFFERED)
    private val mutableMouth = MutableStateFlow(0f to 0f)
    private val generation = AtomicLong(0L)
    private var sampleRate = 24_000
    private var channels = 1
    private var track: AudioTrack? = null
    private var currentStreamId: ByteArray? = null
    private var lastFrameSequence = -1L

    val mouth: StateFlow<Pair<Float, Float>> = mutableMouth

    init {
        scope.launch(Dispatchers.IO) {
            for (chunk in chunks) play(chunk)
        }
    }

    @Synchronized
    fun configure(sampleRate: Int, channels: Int) {
        // A lifecycle start always identifies a new stream. Invalidate queued PCM
        // from the previous stream even when its audio format is unchanged.
        stop()
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        this.channels = channels.coerceIn(1, 2)
    }

    @Synchronized
    fun accept(frame: ByteString) {
        val bytes = frame.toByteArray()
        if (
            bytes.size <= HEADER_BYTES ||
            !bytes.copyOfRange(0, 4).contentEquals(MAGIC) ||
            bytes[4].toInt() != 1 ||
            bytes[5].toInt() != 1
        ) return
        val streamId = bytes.copyOfRange(6, 22)
        val sequence = ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        if (currentStreamId?.contentEquals(streamId) != true) {
            currentStreamId = streamId
            lastFrameSequence = -1L
        }
        if (sequence <= lastFrameSequence) return
        lastFrameSequence = sequence
        chunks.trySend(AudioChunk(generation.get(), bytes.copyOfRange(HEADER_BYTES, bytes.size)))
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        currentStreamId = null
        lastFrameSequence = -1L
        mutableMouth.value = 0f to 0f
    }

    private fun play(chunk: AudioChunk) {
        if (chunk.generation != generation.get()) return
        val audioTrack = track ?: createTrack().also { track = it; it.play() }
        val frameBytes = ((sampleRate * channels * 2) / 50).coerceAtLeast(channels * 2)
        var offset = 0
        while (offset < chunk.pcm.size && chunk.generation == generation.get()) {
            val length = minOf(frameBytes, chunk.pcm.size - offset)
            mutableMouth.value = pcmRms(chunk.pcm, offset, length) to 0f
            val written = runCatching {
                audioTrack.write(chunk.pcm, offset, length, AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(-1)
            if (written <= 0) break
            offset += written
        }
        mutableMouth.value = 0f to 0f
    }

    private fun pcmRms(pcm: ByteArray, offset: Int, length: Int): Float {
        val shorts = ByteBuffer.wrap(pcm, offset, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var count = 0
        while (shorts.hasRemaining()) {
            val value = shorts.get().toDouble() / Short.MAX_VALUE
            sum += value * value
            count += 1
        }
        val rms = if (count > 0) kotlin.math.sqrt(sum / count).toFloat() else 0f
        return (rms * 4f).coerceIn(0f, 0.85f)
    }

    private fun createTrack(): AudioTrack {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channelMask).build())
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
        private const val HEADER_BYTES = 26
    }
}
