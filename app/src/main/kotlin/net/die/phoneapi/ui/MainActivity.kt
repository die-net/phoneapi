package net.die.phoneapi.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.a11y.PhoneAccessibilityService
import net.die.phoneapi.helperclient.HelperLaunch
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.server.ServerController

/** Status, onboarding and pairing. Keeps the server running while visible. */
class MainActivity : ComponentActivity() {
    private val graph
        get() = PhoneApiApp.graph

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) return@registerForActivityResult
        }

    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var pairingText: TextView
    private lateinit var tokenList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        qr = ImageView(this).apply { adjustViewBounds = true }
        pairingText = TextView(this).apply { setTextIsSelectable(true) }
        tokenList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                addView(caption("Tokens"))
                addView(tokenList)
                addView(qr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(pairingText)
            }
        setContentView(ScrollView(this).apply { addView(column) })
        requestNotificationPermission()
        observeStatus()
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.serverController.acquire(HOLDER)
                try {
                    launch { showTokens() }
                    val fingerprint = withContext(graph.ioDispatcher) { graph.tls.fingerprint }
                    combine(graph.serverController.endpoint, graph.a11y, graph.helper.status) {
                            ep,
                            a11y,
                            helper ->
                            statusText(ep, a11y, helper, fingerprint)
                        }
                        .collect { status.text = it }
                } finally {
                    graph.serverController.release(HOLDER)
                }
            }
        }
    }

    private fun statusText(
        endpoint: ServerController.Endpoint?,
        a11y: PhoneAccessibilityService?,
        helper: HelperStatus,
        fingerprint: String,
    ) = buildString {
        appendLine(
            if (endpoint != null) "Listening on ${endpoint.host}:${endpoint.port}"
            else "Server not listening (no LAN address?)"
        )
        appendLine("Accessibility service: ${if (a11y != null) "connected" else "disabled"}")
        appendLine("Helper: ${helper.name.lowercase()}")
        append("Certificate SHA-256: $fingerprint")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        lifecycleScope.launch { block() }
    }

    private fun input(hintText: String, type: Int) =
        EditText(this).apply {
            hint = hintText
            inputType = type
        }

    private fun showPairing() {
        lifecycleScope.launch {
            val rendered =
                withContext(graph.ioDispatcher) {
                    val endpoint = graph.serverController.endpoint.value ?: return@withContext null
                    val created =
                        graph.tokens.replaceNamed(PAIRING_TOKEN_NAME, Scope.entries.toSet())
                    val uri =
                        Uri.Builder()
                            .scheme("phoneapi")
                            .authority("pair")
                            .appendQueryParameter("host", endpoint.host)
                            .appendQueryParameter("port", endpoint.port.toString())
                            .appendQueryParameter("fp", graph.tls.fingerprint)
                            .appendQueryParameter("token", created.token)
                            .build()
                            .toString()
                    uri to qrBitmap(uri)
                } ?: return@launch
            qr.setImageBitmap(rendered.second)
            pairingText.text = rendered.first
            showTokens()
        }
    }

    private suspend fun showTokens() {
        val infos = withContext(graph.ioDispatcher) { graph.tokens.list() }
        tokenList.removeAllViews()
        infos.forEach { tokenList.addView(tokenRow(it)) }
    }

    private fun tokenRow(info: TokenInfo): LinearLayout {
        val name =
            TextView(this).apply {
                text = info.name
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name)
            addView(
                button("Revoke") {
                    lifecycleScope.launch {
                        withContext(graph.ioDispatcher) { graph.tokens.revoke(info.id) }
                        showTokens()
                    }
                }
            )
        }
    }

    private fun caption(value: String) = TextView(this).apply { text = value }

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
        const val PAIRING_TOKEN_NAME = "In-app pairing"
    }
}
