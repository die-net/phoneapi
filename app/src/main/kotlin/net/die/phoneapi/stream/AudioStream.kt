package net.die.phoneapi.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helperclient.HelperConnection

/**
 * One audio encoder shared by every audio viewer. The helper writes PCM from remote submix, and
 * this encodes Opus when the device has that encoder, otherwise AAC.
 */
internal class AudioStream(private val lease: StreamLease, private val helper: HelperConnection) {
    private val lifecycle = Any()
    private val subscribers = CopyOnWriteArrayList<Channel<ByteArray>>()
    private var codec: MediaCodec? = null
    private var pipe: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var headerJson: String = ""
    private var frameBytes: Int = OPUS_BYTES
    private var frameUs: Long = OPUS_US
    private val pcmLock = Any()
    private val pendingPcm = ArrayDeque<Pair<ByteArray, Long>>()
    private val freeInputs = ArrayDeque<Int>()

    @Volatile private var running = false

    @Volatile private var configFrame: ByteArray? = null

    suspend fun serve(session: DefaultWebSocketServerSession) {
        val channel = Channel<ByteArray>(CHANNEL_CAP, BufferOverflow.DROP_OLDEST)
        lease.opened()
        var attached = false
        try {
            val header =
                synchronized(lifecycle) {
                    subscribers.add(channel)
                    attached = true
                    if (subscribers.size == 1) startEncoder()
                    headerJson
                }
            session.send(Frame.Text(header))
            configFrame?.let { session.send(Frame.Binary(true, it)) }
            for (frame in channel) session.send(Frame.Binary(true, frame))
        } finally {
            if (attached) {
                synchronized(lifecycle) {
                    subscribers.remove(channel)
                    if (subscribers.isEmpty()) stopEncoder()
                }
            }
            channel.close()
            lease.closed()
        }
    }

    private fun startEncoder() {
        synchronized(pcmLock) {
            pendingPcm.clear()
            freeInputs.clear()
        }
        val opened = openCodec()
        frameBytes = opened.frameBytes
        frameUs = opened.frameUs
        headerJson =
            buildJsonObject {
                put("codec", opened.codecName)
                put("sampleRate", SAMPLE_RATE)
                put("channels", CHANNELS)
            }
                .toString()
        val encoder = opened.encoder
        codec = encoder
        encoder.setCallback(callback())
        configure(encoder, opened.format)
        encoder.start()
        running = true
        val capture =
            try {
                helper.require().startAudioCapture(SAMPLE_RATE, CHANNELS)
            } catch (e: CancellationException) {
                running = false
                throw e
            } catch (e: RemoteException) {
                running = false
                throw streamError(e)
            } catch (e: IllegalStateException) {
                running = false
                throw streamError(e)
            }
        if (capture == null) {
            running = false
            throw ApiException.unavailable("audio_error", "The helper did not return an audio pipe")
        }
        pipe = capture
        reader =
            Thread({ readPcm(capture) }, "phoneapi-pcm").also {
                it.isDaemon = true
                it.start()
            }
    }

    private fun openCodec(): Opened {
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            return Opened(encoder, "opus", opusFormat(), OPUS_BYTES, OPUS_US)
        } catch (e: IOException) {
            Log.w(TAG, "opus encoder unavailable", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "opus encoder unavailable", e)
        }
        val encoder =
            try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            } catch (e: IOException) {
                throw streamError(e)
            }
        return Opened(encoder, "aac", aacFormat(), AAC_BYTES, AAC_US)
    }

    private fun configure(encoder: MediaCodec, format: MediaFormat) {
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IllegalArgumentException) {
            throw streamError(e)
        } catch (e: MediaCodec.CodecException) {
            throw streamError(e)
        }
    }

    private fun callback(): MediaCodec.Callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                val frame = synchronized(pcmLock) { pendingPcm.removeFirstOrNull() }
                if (frame == null) {
                    synchronized(pcmLock) { freeInputs.addLast(index) }
                    return
                }
                writeInput(codec, index, frame.first, frame.second)
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                handleOutput(codec, index, info)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                Log.e(TAG, "audio encoder", error)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val csd = format.getByteBuffer("csd-0") ?: return
                val view = csd.duplicate()
                val bytes = ByteArray(view.remaining())
                view.get(bytes)
                publishConfig(bytes)
            }
        }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val data =
            try {
                readOutput(codec, index, info)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio output", e)
                return
            } ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            publishConfig(data)
            return
        }
        emit(packFrame(FRAME_DELTA, info.presentationTimeUs, data))
        lease.renew()
    }

    private fun readOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): ByteArray? {
        if (!running) {
            codec.releaseOutputBuffer(index, false)
            return null
        }
        val output = codec.getOutputBuffer(index)
        val bytes =
            if (output != null && info.size > 0) {
                output.position(info.offset)
                output.limit(info.offset + info.size)
                ByteArray(info.size).also { output.get(it) }
            } else {
                null
            }
        codec.releaseOutputBuffer(index, false)
        return bytes
    }

    private fun publishConfig(data: ByteArray) {
        if (data.isEmpty()) return
        val packed = packFrame(FRAME_CONFIG, 0, data)
        configFrame = packed
        emit(packed)
    }

    private fun readPcm(capture: ParcelFileDescriptor) {
        val frame = ByteArray(frameBytes)
        var pts = 0L
        try {
            ParcelFileDescriptor.AutoCloseInputStream(capture).use { input ->
                while (running && readFull(input, frame)) {
                    queue(frame, pts)
                    pts += frameUs
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "pcm ended", e)
        }
    }

    private fun readFull(input: InputStream, frame: ByteArray): Boolean {
        var offset = 0
        while (offset < frame.size) {
            val count = input.read(frame, offset, frame.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun queue(frame: ByteArray, pts: Long) {
        if (!running) return
        val copy = frame.copyOf()
        val index: Int
        val current: MediaCodec
        synchronized(pcmLock) {
            val encoder = codec
            val free = freeInputs.removeFirstOrNull()
            if (encoder == null || free == null) {
                while (pendingPcm.size >= MAX_PENDING) pendingPcm.removeFirst()
                pendingPcm.addLast(copy to pts)
                return
            }
            current = encoder
            index = free
        }
        writeInput(current, index, copy, pts)
    }

    private fun writeInput(encoder: MediaCodec, index: Int, frame: ByteArray, pts: Long) {
        if (!running) return
        try {
            val buffer = encoder.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(frame)
            encoder.queueInputBuffer(index, 0, frame.size, pts, 0)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "pcm queue", e)
        }
    }

    private fun emit(frame: ByteArray) {
        for (subscriber in subscribers) subscriber.trySend(frame)
    }

    private fun stopEncoder() {
        running = false
        synchronized(pcmLock) {
            pendingPcm.clear()
            freeInputs.clear()
        }
        try {
            helper.getOrNull()?.stopAudioCapture()
        } catch (e: RemoteException) {
            Log.w(TAG, "stopAudio", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopAudio", e)
        }
        pipe?.let { end ->
            try {
                end.close()
            } catch (e: IOException) {
                Log.i(TAG, "pcm close", e)
            }
        }
        pipe = null
        reader?.join(JOIN_MS)
        reader = null
        val current = codec
        codec = null
        configFrame = null
        if (current == null) return
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio encoder stop", e)
        }
        current.release()
    }

    private fun streamError(error: Throwable): ApiException =
        ApiException(502, "stream_error", error.message ?: "audio encoder failed", cause = error)

    private data class Opened(
        val encoder: MediaCodec,
        val codecName: String,
        val format: MediaFormat,
        val frameBytes: Int,
        val frameUs: Long,
    )

    private companion object {
        const val TAG = "PhoneApiStream"
        const val CHANNEL_CAP = 8
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val OPUS_BYTES = 960 * CHANNELS * 2
        const val OPUS_US = 20_000L
        const val AAC_BYTES = 1024 * CHANNELS * 2
        const val AAC_US = 1024L * 1_000_000L / SAMPLE_RATE
        const val JOIN_MS = 500L
        const val MAX_PENDING = 8
        const val BIT_RATE = 64_000

        fun opusFormat(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
                .apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                }

        fun aacFormat(): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
                .apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                }
    }
}
