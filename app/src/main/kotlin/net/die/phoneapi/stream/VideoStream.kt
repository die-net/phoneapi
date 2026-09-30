package net.die.phoneapi.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.Display
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.DisplayInfo

internal data class VideoSpec(val maxSize: Int, val fps: Int, val bitRate: Int)

/**
 * One H.264 encoder shared by every video viewer. The helper mirrors the display onto the encoder's
 * input surface. A joining client receives the parameter sets and a new keyframe.
 */
internal class VideoStream(
    private val lease: StreamLease,
    private val helper: HelperConnection,
    private val display: () -> DisplayInfo,
) {
    private val lifecycle = Any()
    private val subscribers = CopyOnWriteArrayList<Channel<ByteArray>>()
    private var codec: MediaCodec? = null
    private var headerJson: String = ""
    private var spsPps: ByteArray? = null

    @Volatile private var running = false

    @Volatile private var configFrame: ByteArray? = null

    suspend fun serve(session: DefaultWebSocketServerSession, spec: VideoSpec) {
        val channel = Channel<ByteArray>(CHANNEL_CAP, BufferOverflow.DROP_OLDEST)
        lease.opened()
        var attached = false
        try {
            val header =
                synchronized(lifecycle) {
                    subscribers.add(channel)
                    attached = true
                    if (subscribers.size == 1) startEncoder(spec)
                    headerJson
                }
            session.send(Frame.Text(header))
            configFrame?.let { session.send(Frame.Binary(true, it)) }
            requestSync()
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

    private fun startEncoder(spec: VideoSpec) {
        val screen = display()
        val (width, height) = scaledSize(screen.widthPx, screen.heightPx, spec.maxSize)
        headerJson =
            buildJsonObject {
                put("codec", "avc")
                put("width", width)
                put("height", height)
                put("fps", spec.fps)
                put("bitRate", spec.bitRate)
            }
                .toString()
        val format = videoFormat(width, height, spec)
        val encoder =
            try {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: IOException) {
                throw streamError(e)
            }
        codec = encoder
        encoder.setCallback(callback())
        configure(encoder, format)
        val surface = encoder.createInputSurface()
        encoder.start()
        running = true
        try {
            val started =
                helper.require().startMirror(surface, width, height, Display.DEFAULT_DISPLAY)
            if (!started) {
                throw ApiException.unavailable("stream_error", "The helper did not start mirroring")
            }
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

    private fun videoFormat(width: Int, height: Int, spec: VideoSpec): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_US)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

    private fun callback(): MediaCodec.Callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                handleOutput(codec, index, info)
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                Log.e(TAG, "encoder", error)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val csd0 = format.getByteBuffer("csd-0")?.let(::copyBuffer) ?: return
                val csd1 = format.getByteBuffer("csd-1")?.let(::copyBuffer)
                publishParameterSets(csd0, csd1)
            }
        }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val data =
            try {
                readOutput(codec, index, info)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder output", e)
                return
            } ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            publishParameterSets(data, null)
            return
        }
        val annex = accessUnitToAnnexB(data)
        if (annex.isEmpty()) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val payload = if (key) withParameterSets(annex) else annex
        val type = if (key) FRAME_KEY else FRAME_DELTA
        emit(packFrame(type, info.presentationTimeUs, payload))
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

    private fun publishParameterSets(csd0: ByteArray, csd1: ByteArray?) {
        val annex =
            try {
                codecConfigAnnexB(csd0, csd1)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "codec config", e)
                return
            }
        if (annex.isEmpty()) return
        spsPps = annex
        val packed = packFrame(FRAME_CONFIG, 0, annex)
        configFrame = packed
        emit(packed)
    }

    private fun withParameterSets(annex: ByteArray): ByteArray {
        val sets = spsPps ?: return annex
        if (startsWithSps(annex)) return annex
        return sets + annex
    }

    private fun requestSync() {
        val current = codec ?: return
        if (!running) return
        val bundle = Bundle()
        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        try {
            current.setParameters(bundle)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "sync frame", e)
        }
    }

    private fun emit(frame: ByteArray) {
        for (subscriber in subscribers) subscriber.trySend(frame)
    }

    private fun stopEncoder() {
        running = false
        val current = codec
        codec = null
        configFrame = null
        spsPps = null
        try {
            helper.getOrNull()?.stopMirror()
        } catch (e: RemoteException) {
            Log.w(TAG, "stopMirror", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopMirror", e)
        }
        if (current != null) releaseCodec(current)
    }

    private fun releaseCodec(current: MediaCodec) {
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "encoder stop", e)
        }
        current.release()
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val view = buffer.duplicate()
        val out = ByteArray(view.remaining())
        view.get(out)
        return out
    }

    private fun streamError(error: Throwable): ApiException =
        ApiException(502, "stream_error", error.message ?: "encoder failed", cause = error)

    private companion object {
        const val TAG = "PhoneApiStream"
        const val CHANNEL_CAP = 4
        const val REPEAT_US = 100_000
    }
}
