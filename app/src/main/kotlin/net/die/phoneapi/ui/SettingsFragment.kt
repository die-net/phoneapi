package net.die.phoneapi.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.R
import net.die.phoneapi.helperclient.HelperLaunch
import net.die.phoneapi.helperclient.notificationsAllowed
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.server.PairingManager
import net.die.phoneapi.server.ServerController

/** Status, the HTTPS switch, the adb command, pairing, and tokens. */
class SettingsFragment : PreferenceFragmentCompat() {
    private val graph
        get() = PhoneApiApp.graph

    private var pairing: PairingPanel? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            findPreference<Preference>(KEY_TLS)?.isVisible = false
            findPreference<Preference>(KEY_WIRELESS)?.isVisible = false
            findPreference<Preference>(KEY_PAIR)?.isVisible = false
        }
        val tls = findPreference<SwitchPreferenceCompat>(KEY_TLS)
        tls?.isChecked = graph.settings.current.tlsEnabled
        tls?.setOnPreferenceChangeListener { _, value ->
            if (value == true && !graph.helperSupervisor.wireless.value) {
                Toast.makeText(requireContext(), R.string.tls_needs_wireless, Toast.LENGTH_LONG)
                    .show()
                return@setOnPreferenceChangeListener false
            }
            graph.settings.update { it.copy(tlsEnabled = value as Boolean) }
            true
        }
        findPreference<Preference>(KEY_FORWARD)?.setOnPreferenceClickListener {
            copy(forwardCommand())
            true
        }
        findPreference<Preference>(KEY_HELPER)?.setOnPreferenceClickListener {
            copy(helperCommand())
            true
        }
        findPreference<Preference>(KEY_PAIR)?.setOnPreferenceClickListener {
            if (graph.pairing.state.value is PairingManager.State.Open) {
                graph.pairing.close()
            } else {
                graph.pairing.open()
            }
            true
        }
        findPreference<Preference>(KEY_WIRELESS)?.setOnPreferenceClickListener {
            pairWireless()
            true
        }
    }

    override fun onStart() {
        super.onStart()
        val activity = requireActivity() as AppCompatActivity
        val panel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PairingPanel(activity, graph) { refreshTokens() }
            } else {
                null
            }
        pairing = panel
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (panel != null) launch { panel.run() }
                launch { watchStatus() }
                launch { refreshTokens() }
            }
        }
    }

    override fun onStop() {
        pairing = null
        super.onStop()
    }

    private suspend fun watchStatus() {
        combine(
                graph.serverController.endpoint,
                graph.network.lanAddress,
                graph.helper.status,
                graph.settings.settings,
                graph.helperSupervisor.wireless,
            ) { endpoint, _, helper, settings, wireless ->
                Status(endpoint, helper, settings.tlsEnabled, settings.port, wireless)
            }
            .collect { status ->
                findPreference<Preference>(KEY_STATUS)?.summary = statusText(status)
                findPreference<Preference>(KEY_FORWARD)?.summary = forwardCommand()
                findPreference<Preference>(KEY_HELPER)?.summary = helperCommand()
                findPreference<SwitchPreferenceCompat>(KEY_TLS)?.apply {
                    isEnabled = status.wireless
                    summary =
                        getString(
                            if (status.wireless) R.string.tls_summary
                            else R.string.tls_needs_wireless
                        )
                }
                val open = graph.pairing.state.value is PairingManager.State.Open
                findPreference<Preference>(KEY_PAIR)?.summary =
                    if (open) getString(R.string.pair_stop) else getString(R.string.pair_how)
            }
    }

    private fun statusText(status: Status): String {
        val helperState =
            when (status.helper) {
                HelperStatus.RUNNING -> R.string.helper_state_running
                HelperStatus.STARTING -> R.string.helper_state_starting
                HelperStatus.NEEDS_PAIRING,
                HelperStatus.NEEDS_USB,
                HelperStatus.STOPPED -> R.string.helper_state_off
            }
        val listener =
            if (status.tls && status.endpoint != null) {
                getString(
                    R.string.status_listening,
                    status.endpoint.host,
                    status.endpoint.port,
                )
            } else {
                getString(R.string.status_abstract, status.port, requireContext().packageName)
            }
        return listener + "\n" + getString(R.string.status_helper, getString(helperState))
    }

    private fun forwardCommand(): String {
        val port = graph.settings.current.port
        val name = requireContext().packageName
        return "adb forward tcp:$port localabstract:$name"
    }

    private fun helperCommand(): String {
        val pkg = requireContext().packageName
        val service = "adb shell am start-foreground-service -n $pkg/.server.ListenerService"
        return service + "\n" + HelperLaunch.command(pkg)
    }

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("phoneapi", text))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTokens() {
        viewLifecycleOwner.lifecycleScope.launch {
            val infos = withContext(graph.ioDispatcher) { graph.tokens.list() }
            val category = findPreference<PreferenceCategory>(KEY_TOKENS) ?: return@launch
            category.removeAll()
            if (infos.isEmpty()) {
                category.addPreference(
                    Preference(requireContext()).apply {
                        title = getString(R.string.tokens_none)
                        isSelectable = false
                    }
                )
                return@launch
            }
            infos.forEach { info -> category.addPreference(tokenPreference(info)) }
        }
    }

    private fun tokenPreference(info: TokenInfo) =
        Preference(requireContext()).apply {
            key = "token-${info.id}"
            title = info.name
            summary = getString(R.string.revoke)
            setOnPreferenceClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(graph.ioDispatcher) { graph.tokens.revoke(info.id) }
                    refreshTokens()
                }
                true
            }
        }

    private fun pairWireless() {
        val context = requireContext()
        val developer =
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            )
        if (developer != 1 || !context.notificationsAllowed()) {
            Toast.makeText(context, R.string.wireless_needs_developer, Toast.LENGTH_LONG).show()
            return
        }
        graph.wirelessPairing.start()
        val highlight = Bundle().apply { putString(FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY) }
        val intent =
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY)
                .putExtra(SHOW_FRAGMENT_ARGS, highlight)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No developer options screen", e)
        }
    }

    private data class Status(
        val endpoint: ServerController.Endpoint?,
        val helper: HelperStatus,
        val tls: Boolean,
        val port: Int,
        val wireless: Boolean,
    )

    private companion object {
        const val KEY_STATUS = "status"
        const val KEY_TLS = "tls"
        const val KEY_FORWARD = "forward"
        const val KEY_HELPER = "helper"
        const val KEY_WIRELESS = "wireless"
        const val KEY_PAIR = "pair"
        const val KEY_TOKENS = "tokens"
        const val TAG = "PhoneApi"
        const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
        const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    }
}
