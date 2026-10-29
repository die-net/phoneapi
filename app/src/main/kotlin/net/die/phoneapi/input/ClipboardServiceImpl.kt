package net.die.phoneapi.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ClipImage
import net.die.phoneapi.core.ClipboardService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.ClipboardContent
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.KeyRequest

/**
 * The clipboard through the helper, which reads it as the shell user: this app is never in front,
 * and Android 10 and later let only the app in front or the keyboard read it. Copy and paste press
 * KEYCODE_COPY and KEYCODE_PASTE, which text fields and Chrome handle. An image is put on the
 * clipboard by this app rather than the helper, since the clip's URI is this app's [ClipProvider]
 * and only its owner may share it.
 */
class ClipboardServiceImpl(
    private val context: Context,
    private val helper: HelperConnection,
    private val input: InputService,
    private val io: CoroutineContext,
) : ClipboardService {
    override suspend fun read(request: ClipboardReadRequest): ClipboardContent {
        if (!request.copy) return content(call { primaryClip() })
        val seen = call { awaitClipChange(-1, 0) }
        input.key(KeyRequest("KEYCODE_COPY", autoWake = request.autoWake))
        val now = call { awaitClipChange(seen, COPY_WAIT_MS) }
        if (now > seen) settle(now)
        return content(call { primaryClip() }).copy(changed = now > seen)
    }

    override suspend fun write(request: ClipboardWriteRequest): ActionResult {
        if (request.text.length > MAX_TEXT) {
            throw ApiException.badRequest("text is longer than $MAX_TEXT characters")
        }
        // No label, as when a person copies from a text field, so the clip looks like theirs.
        call { setPrimaryClip(ClipData.newPlainText(null, request.text)) }
        return pasted(request.paste, request.autoWake)
    }

    override suspend fun image(): ClipImage? {
        val clip = call { primaryClip() } ?: return null
        val description = clip.description
        val declared =
            (0 until description.mimeTypeCount)
                .map { description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") } ?: return null
        val uri =
            (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return null
        val bytes = readAll(call { readContent(uri.toString()) })
        if (bytes.isEmpty()) {
            throw ApiException(409, "clipboard_unreadable", "The helper could not read the image")
        }
        return ClipImage(bytes, ImageType.sniff(bytes)?.mimeType ?: declared)
    }

    override suspend fun writeImage(bytes: ByteArray, paste: Boolean): ActionResult {
        if (bytes.size > ClipImage.MAX_BYTES) {
            throw ApiException(413, "too_large", "The image is over ${ClipImage.MAX_BYTES} bytes")
        }
        val type =
            ImageType.sniff(bytes)
                ?: throw ApiException.badRequest("The image is not PNG, JPEG, GIF, or WebP")
        withContext(io) {
            val dir = ClipProvider.dir(context)
            dir.mkdirs()
            // Only the newest image stays readable, under a name no earlier clip used.
            dir.listFiles()?.forEach { it.delete() }
            val name = "${UUID.randomUUID().toString().replace("-", "")}.${type.extension}"
            File(dir, name).writeBytes(bytes)
            val uri =
                Uri.Builder()
                    .scheme("content")
                    .authority(ClipProvider.authority(context))
                    .appendPath(name)
                    .build()
            val clip = ClipData.newUri(context.contentResolver, null, uri)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
        return pasted(paste, autoWake = true)
    }

    private suspend fun pasted(paste: Boolean, autoWake: Boolean): ActionResult {
        if (!paste) return ActionResult(ok = true, backend = BACKEND)
        return input.key(KeyRequest("KEYCODE_PASTE", autoWake = autoWake)).copy(backend = BACKEND)
    }

    /**
     * Waits for the clipboard to stop changing. On Android 16, Chrome's first copy after it starts
     * can read back empty right after the change, and holds the text 100 ms later.
     */
    private suspend fun settle(changes: Long) {
        var last = changes
        repeat(COPY_WAIT_MS.toInt() / SETTLE_MS.toInt()) {
            val next = call { awaitClipChange(last, SETTLE_MS) }
            if (next == last) return
            last = next
        }
    }

    private suspend fun readAll(pipe: ParcelFileDescriptor): ByteArray =
        withContext(io) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > ClipImage.MAX_BYTES) {
                            throw ApiException(
                                413,
                                "too_large",
                                "The clipboard's image is over ${ClipImage.MAX_BYTES} bytes",
                            )
                        }
                    }
                    out.toByteArray()
                }
            } catch (e: IOException) {
                throw ApiException.helperDropped(e)
            }
        }

    private suspend fun <T> call(block: IHelper.() -> T): T {
        val proxy = helper.require()
        return try {
            withContext(io) { proxy.block() }
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        }
    }

    private fun content(clip: ClipData?): ClipboardContent {
        if (clip == null) return ClipboardContent()
        val description = clip.description
        val texts = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
        return ClipboardContent(
            text = texts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            mimeTypes = (0 until description.mimeTypeCount).map { description.getMimeType(it) },
            sensitive = description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true,
        )
    }

    private companion object {
        const val BACKEND = "clipboard"
        /** Binder calls carry at most about 1 MB, and text is two bytes a character. */
        const val MAX_TEXT = 100_000
        /** How long an app gets to put its selection on the clipboard after KEYCODE_COPY. */
        const val COPY_WAIT_MS = 1_000L
        /** How long the clipboard has to stay unchanged after a copy before it is read. */
        const val SETTLE_MS = 100L
        const val BUFFER_BYTES = 64 * 1024
        /** ClipDescription.EXTRA_IS_SENSITIVE, which Android 13 named; apps set it earlier. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
