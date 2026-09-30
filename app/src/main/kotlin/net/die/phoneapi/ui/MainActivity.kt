package net.die.phoneapi.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.helperclient.HelperLaunch
import net.die.phoneapi.model.Scope

/** Status, onboarding and pairing. Keeps the server running while visible. */
class MainActivity : Activity() {
    private val graph
        get() = PhoneApiApp.graph

    private var uiScope: CoroutineScope? = null
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var pairingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        qr = ImageView(this).apply { adjustViewBounds = true }
        pairingText = TextView(this).apply { setTextIsSelectable(true) }
        val helperCommand =
            TextView(this).apply {
                text = HelperLaunch.command(packageName)
                setTextIsSelectable(true)
                textSize = 12f
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(status)
                addView(
                    button("Accessibility settings") {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
                addView(button("New pairing QR code") { showPairing() })
                addView(
                    button("Copy helper start command") {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(
                                ClipData.newPlainText("phoneapi helper", helperCommand.text)
                            )
                    }
                )
                addView(helperCommand)
                addRecovery(this)
                addView(qr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(pairingText)
            }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun addRecovery(column: LinearLayout) {
        val message = TextView(this)
        column.addView(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val code = input("Wireless debugging code", InputType.TYPE_CLASS_NUMBER)
            val port = input("Pairing port", InputType.TYPE_CLASS_NUMBER)
            column.addView(code)
            column.addView(port)
            column.addView(
                button("Pair wireless debugging") {
                    launchUi {
                        val typed = port.text.toString().trim().toIntOrNull()
                        message.text =
                            graph.helperSupervisor.pair(code.text.toString().trim(), typed)
                    }
                }
            )
        }
        column.addView(
            button("Start with Shizuku") { message.text = graph.helperSupervisor.requestShizuku() }
        )
    }

    private fun launchUi(block: suspend () -> Unit) {
        uiScope?.launch { block() }
    }

    private fun input(hintText: String, type: Int) =
        EditText(this).apply {
            hint = hintText
            inputType = type
        }

    override fun onStart() {
        super.onStart()
        graph.serverController.acquire(HOLDER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        uiScope = scope
        scope.launch {
            combine(graph.serverController.endpoint, graph.a11y, graph.helper.status) {
                    ep,
                    a11y,
                    helper ->
                    buildString {
                        appendLine(
                            if (ep != null) "Listening on ${ep.host}:${ep.port}"
                            else "Server not listening (no LAN address?)"
                        )
                        appendLine(
                            "Accessibility service: ${if (a11y != null) "connected" else "disabled"}"
                        )
                        appendLine("Helper: ${helper.name.lowercase()}")
                        append("Certificate SHA-256: ${graph.tls.fingerprint}")
                    }
                }
                .collect { status.text = it }
        }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        graph.serverController.release(HOLDER)
        super.onStop()
    }

    private fun showPairing() {
        val endpoint = graph.serverController.endpoint.value
        val host = endpoint?.host ?: return
        val created =
            graph.tokens.create("paired ${System.currentTimeMillis()}", Scope.entries.toSet())
        val uri =
            Uri.Builder()
                .scheme("phoneapi")
                .authority("pair")
                .appendQueryParameter("host", host)
                .appendQueryParameter("port", endpoint.port.toString())
                .appendQueryParameter("fp", graph.tls.fingerprint)
                .appendQueryParameter("token", created.token)
                .build()
                .toString()
        qr.setImageBitmap(qrBitmap(uri))
        pairingText.text = uri
    }

    private fun qrBitmap(text: String): Bitmap {
        val size = QR_SIZE
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels =
            IntArray(size * size) { i ->
                if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
            }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    private companion object {
        const val HOLDER = "ui"
        const val QR_SIZE = 720
    }
}
