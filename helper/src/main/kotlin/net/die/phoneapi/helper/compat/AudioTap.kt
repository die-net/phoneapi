package net.die.phoneapi.helper.compat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

/**
 * Records device audio through [MediaRecorder.AudioSource.REMOTE_SUBMIX] and writes PCM s16le to a
 * pipe the app encodes. The source is a public constant; opening it requires
 * [android.Manifest.permission.CAPTURE_AUDIO_OUTPUT], which the shell uid holds and the app does
 * not.
 */
internal class AudioTap {
    private val lock = Any()
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var writeEnd: ParcelFileDescriptor? = null

    @Volatile private var running = false

    @Volatile private var generation = 0

    @Suppress("MissingUseCall") // The app owns the read end of this pipe.
    fun start(sampleRate: Int, channels: Int): ParcelFileDescriptor =
        synchronized(lock) {
            val token = ++generation
            releaseLocked()
            require(sampleRate in SAMPLE_RATE_RANGE) { "sampleRate $sampleRate" }
            require(channels == 1 || channels == 2) { "channels $channels" }
            val mask =
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minimum =
                AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "No audio buffer for $sampleRate Hz" }
            val audio = newRecord(mask, sampleRate, minimum * 2)
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                error("AudioRecord did not initialize")
            }
            val pipe = ParcelFileDescriptor.createPipe()
            audio.startRecording()
            running = true
            record = audio
            writeEnd = pipe[1]
            thread =
                Thread({ pump(audio, pipe[1], minimum, token) }, "phoneapi-audio").also {
                    it.isDaemon = true
                    it.start()
                }
            pipe[0]
        }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        running = false
        writeEnd?.let { end ->
            try {
                end.close()
            } catch (e: IOException) {
                Log.i(TAG, "audio pipe close", e)
            }
        }
        writeEnd = null
        record?.let { audio ->
            try {
                audio.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio stop", e)
            }
            audio.release()
        }
        record = null
        thread?.join(JOIN_MS)
        thread = null
    }

    // REMOTE_SUBMIX is public, but setAudioSource's IntDef omits it. Capturing it requires
    // CAPTURE_AUDIO_OUTPUT, which the shell uid holds; this process never requests RECORD_AUDIO.
    @SuppressLint("MissingPermission", "WrongConstant")
    private fun newRecord(mask: Int, sampleRate: Int, bufferBytes: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

    private fun pump(audio: AudioRecord, end: ParcelFileDescriptor, chunk: Int, token: Int) {
        val buffer = ByteArray(chunk)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(end).use { output ->
                // token rejects a pump that outlives join() once start() has replaced it.
                while (running && token == generation) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "audio reader stopped", e)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "audio reader stopped", e)
        }
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val JOIN_MS = 500L
        val SAMPLE_RATE_RANGE = 8_000..96_000
    }
}
