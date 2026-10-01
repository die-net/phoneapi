package net.die.phoneapi.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.net.InetAddress
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.R
import net.die.phoneapi.ShellCommandReceiver
import net.die.phoneapi.helperclient.HelperLaunch
import net.die.phoneapi.helperclient.notificationsAllowed
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.server.ServerController

/**
 * Onboarding, pairing, and the list of paired computers. Written for someone who installed the APK
 * without reading the README. Keeps the server running while visible.
 */
class MainActivity : ComponentActivity() {
    private val graph
        get() = PhoneApiApp.graph

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) return@registerForActivityResult
        }

    private lateinit var status: TextView
    private lateinit var a11yState: TextView
    private lateinit var a11yRestricted: List<View>
    private lateinit var tokenList: LinearLayout
    private lateinit var pairing: PairingPanel
    private lateinit var helperShizuku: List<View>
    private lateinit var helperWireless: List<View>
    private lateinit var helperWirelessHow: TextView
    private lateinit var helperUsbHow: TextView
    private lateinit var helperAdb: List<View>
    private lateinit var helperMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        status = code("")
        a11yState = TextView(this).apply { setTypeface(typeface, Typeface.BOLD) }
        tokenList = vertical()
        pairing = PairingPanel(this, graph) { lifecycleScope.launch { showTokens() } }
        val column = vertical()
        column.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(32))
        column.addView(title(R.string.app_name))
        column.addView(body(R.string.intro))
        column.addView(status)
        addAccessibility(column)
        addPairing(column)
        addHelper(column)
        column.addView(heading(R.string.tokens_title))
        column.addView(body(R.string.tokens_how))
        column.addView(tokenList)
        setContentView(
            ScrollView(this).apply {
                // Android 15+ draws this activity edge to edge.
                fitsSystemWindows = true
                clipToPadding = false
                addView(column)
            }
        )
        requestNotificationPermission()
        observeStatus()
    }

    private fun addAccessibility(column: LinearLayout) {
        column.addView(heading(R.string.a11y_title))
        column.addView(body(R.string.a11y_why))
        column.addView(a11yState)
        column.addView(
            button(R.string.a11y_button) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        a11yRestricted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    body(R.string.a11y_restricted),
                    button(R.string.app_info_button) {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            )
                        )
                    },
                )
            } else {
                emptyList()
            }
        a11yRestricted.forEach(column::addView)
    }

    private fun addPairing(column: LinearLayout) {
        column.addView(heading(R.string.pair_title))
        column.addView(body(R.string.pair_how))
        pairing.views.forEach(column::addView)
        column.addView(body(R.string.pair_adb))
        val adb = ShellCommandReceiver.createTokenCommand(packageName)
        column.addView(code(adb))
        column.addView(copyButton(R.string.copy_adb) { adb })
    }

    private fun addHelper(column: LinearLayout) {
        column.addView(heading(R.string.helper_title))
        column.addView(body(R.string.helper_why))
        helperMessage = TextView(this)
        helperShizuku = shizukuHelperViews()
        helperWireless = wirelessHelperViews()
        helperUsbHow = body(R.string.helper_usb)
        helperAdb = usbHelperViews()
        helperShizuku.forEach(column::addView)
        helperWireless.forEach(column::addView)
        column.addView(helperUsbHow)
        helperAdb.forEach(column::addView)
        column.addView(helperMessage)
        showHelperPaths()
    }

    private fun shizukuHelperViews() =
        listOf(
            body(R.string.helper_shizuku),
            button(R.string.shizuku_start) {
                helperMessage.text = graph.helperSupervisor.requestShizuku()
            },
        )

    private fun wirelessHelperViews(): List<View> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        helperWirelessHow = body(R.string.helper_wireless)
        return listOf(
            helperWirelessHow,
            button(R.string.wireless_pair) { helperMessage.text = pairWireless() },
        )
    }

    private fun usbHelperViews(): List<View> {
        val command = HelperLaunch.command(packageName)
        return listOf(code(command), copyButton(R.string.copy_helper) { command })
    }

    private fun showHelperPaths() {
        val shizuku = graph.helperSupervisor.shizukuInstalled()
        val wirelessOn = graph.helperSupervisor.wirelessEnabled()
        val showWireless =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (wirelessOn || !shizuku)
        helperShizuku.forEach { it.shownIf(shizuku) }
        helperWireless.forEach { it.shownIf(showWireless) }
        if (showWireless) {
            helperWirelessHow.setText(
                if (wirelessOn) R.string.helper_wireless_on else R.string.helper_wireless
            )
        }
        val showUsb = !wirelessOn
        helperUsbHow.shownIf(showUsb)
        helperAdb.forEach { it.shownIf(showUsb) }
        if (showUsb) {
            helperUsbHow.setText(
                if (shizuku || showWireless) R.string.helper_usb_alt else R.string.helper_usb
            )
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.serverController.acquire(HOLDER)
                try {
                    showHelperPaths()
                    launch { showTokens() }
                    launch { pairing.run() }
                    launch { graph.a11y.collect { showAccessibility(it != null) } }
                    val fingerprint = withContext(graph.ioDispatcher) { graph.tls.fingerprint }
                    combine(
                            graph.serverController.endpoint,
                            graph.network.lanAddress,
                            graph.helper.status,
                        ) { endpoint, lan, helper ->
                            statusText(endpoint, lan, helper, fingerprint)
                        }
                        .collect { status.text = it }
                } finally {
                    graph.serverController.release(HOLDER)
                }
            }
        }
    }

    private fun showAccessibility(on: Boolean) {
        a11yState.setText(if (on) R.string.a11y_on else R.string.a11y_off)
        a11yRestricted.forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
    }

    private fun statusText(
        endpoint: ServerController.Endpoint?,
        lan: InetAddress?,
        helper: HelperStatus,
        fingerprint: String,
    ) = buildString {
        appendLine(
            if (endpoint != null) {
                getString(
                    R.string.status_listening,
                    lan?.hostAddress ?: endpoint.host,
                    endpoint.port,
                )
            } else {
                getString(R.string.status_offline)
            }
        )
        val helperState =
            when (helper) {
                HelperStatus.RUNNING -> R.string.helper_state_running
                HelperStatus.STARTING -> R.string.helper_state_starting
                HelperStatus.NEEDS_PAIRING,
                HelperStatus.NEEDS_USB,
                HelperStatus.STOPPED -> R.string.helper_state_off
            }
        appendLine(getString(R.string.status_helper, getString(helperState)))
        append(getString(R.string.status_cert, fingerprint))
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

    /**
     * Watches for the system pairing dialog, then opens Developer options at Wireless debugging.
     */
    private fun pairWireless(): String {
        val developer =
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        if (developer != 1) return getString(R.string.wireless_needs_developer)
        if (!notificationsAllowed()) {
            requestNotificationPermission()
            return getString(R.string.wireless_needs_notifications)
        }
        graph.wirelessPairing.start()
        return if (openWirelessSettings()) "" else getString(R.string.wireless_needs_developer)
    }

    private fun openWirelessSettings(): Boolean {
        val highlight = Bundle().apply { putString(FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY) }
        val intent =
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY)
                .putExtra(SHOW_FRAGMENT_ARGS, highlight)
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No developer options screen", e)
            false
        }
    }

    private suspend fun showTokens() {
        val infos = withContext(graph.ioDispatcher) { graph.tokens.list() }
        tokenList.removeAllViews()
        if (infos.isEmpty()) tokenList.addView(body(R.string.tokens_none))
        infos.forEach { tokenList.addView(tokenRow(it)) }
    }

    private fun tokenRow(info: TokenInfo): LinearLayout {
        val name =
            TextView(this).apply {
                text = info.name
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(name)
            addView(
                button(R.string.revoke) {
                    lifecycleScope.launch {
                        withContext(graph.ioDispatcher) { graph.tokens.revoke(info.id) }
                        showTokens()
                    }
                }
            )
        }
    }

    private companion object {
        const val HOLDER = "ui"
        const val TAG = "PhoneApi"

        // Settings extras that scroll to and highlight a preference; not public API.
        const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
        const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    }
}
