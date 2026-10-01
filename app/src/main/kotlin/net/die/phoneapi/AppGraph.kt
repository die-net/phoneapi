package net.die.phoneapi

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import net.die.phoneapi.a11y.A11yUiService
import net.die.phoneapi.a11y.NodeRegistry
import net.die.phoneapi.a11y.NodeTargeting
import net.die.phoneapi.a11y.PhoneAccessibilityService
import net.die.phoneapi.a11y.Screenshotter
import net.die.phoneapi.a11y.SnapshotEngine
import net.die.phoneapi.a11y.UiChangeTracker
import net.die.phoneapi.a11y.require
import net.die.phoneapi.apps.AppsServiceImpl
import net.die.phoneapi.browser.BrowserServiceImpl
import net.die.phoneapi.browser.ContentFrame
import net.die.phoneapi.browser.HelperDevtoolsSocket
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.DeviceInfoProvider
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.SettingsStore
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.helperclient.HelperShell
import net.die.phoneapi.helperclient.HelperStatusNotifier
import net.die.phoneapi.helperclient.HelperSupervisor
import net.die.phoneapi.helperclient.KeystorePrivateKeyStore
import net.die.phoneapi.input.A11yTouchBackend
import net.die.phoneapi.input.Humanizer
import net.die.phoneapi.input.ImeKeyBackend
import net.die.phoneapi.input.InjectKeyBackend
import net.die.phoneapi.input.InjectTouchBackend
import net.die.phoneapi.input.InputServiceImpl
import net.die.phoneapi.input.KeyBackends
import net.die.phoneapi.input.TouchBackends
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.power.PinStore
import net.die.phoneapi.power.PowerServiceImpl
import net.die.phoneapi.server.ApiServer
import net.die.phoneapi.server.MdnsAdvertiser
import net.die.phoneapi.server.NetworkWatcher
import net.die.phoneapi.server.ServerController
import net.die.phoneapi.server.TlsManager
import net.die.phoneapi.server.TokenStore
import net.die.phoneapi.stream.AudioStream
import net.die.phoneapi.stream.StreamLease
import net.die.phoneapi.stream.VideoStream
import net.die.phoneapi.wait.WaitServiceImpl

/** Process-wide object graph, created once by [PhoneApiApp]. */
class AppGraph(
    val context: Context,
    filesDir: File = context.filesDir,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val bus = EventBus()
    val settings = SettingsStore(filesDir)
    val tokens = TokenStore(filesDir)
    val tls by lazy {
        TlsManager(filesDir, settings.current.keystorePassword.toCharArray())
    }
    val state = DeviceStateTracker(context, bus)
    val helper =
        HelperConnection(
            bus,
            idleStatus =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HelperStatus.NEEDS_PAIRING
                } else {
                    HelperStatus.NEEDS_USB
                },
        )
    val shell = HelperShell(helper, ioDispatcher)
    val network = NetworkWatcher(context)
    internal val helperSupervisor =
        HelperSupervisor(
            context = context,
            helper = helper,
            keys = KeystorePrivateKeyStore(filesDir),
            network = network,
            scope = scope,
            ioDispatcher = ioDispatcher,
            accessibilityComponent =
                ComponentName(context, PhoneAccessibilityService::class.java).flattenToString(),
        )

    /** The lock-screen PIN, provisioned over ADB and never returned by the API. */
    val pins = PinStore(filesDir)

    /** The connected accessibility service, or null when it is disabled. */
    val a11y = MutableStateFlow<PhoneAccessibilityService?>(null)

    /** Wake/unlock handling; installed by [start]. */
    @Volatile var power: PowerService? = null

    val deviceInfo =
        DeviceInfoProvider(
            context = context,
            state = state,
            isHelperRunning = { helper.isRunning },
            isA11yConnected = { a11y.value != null },
            helperStatus = { helper.status.value },
            helperRecoveredAtMs = { helper.recoveredAtMs },
        )

    val uiTracker = UiChangeTracker(bus, state, scope, ioDispatcher)
    val nodes = NodeRegistry()

    /** The helper registers its injection backends in these. */
    val touchBackends = TouchBackends(A11yTouchBackend(a11y))
    val keyBackends = KeyBackends(ImeKeyBackend(a11y))

    init {
        touchBackends.inject = InjectTouchBackend(helper, ioDispatcher)
        keyBackends.helper = InjectKeyBackend(helper, ioDispatcher)
    }

    val touch = TouchInput(touchBackends, Humanizer()) { deviceInfo.display() }
    val targeting = NodeTargeting(::screenRect)
    val snapshots = SnapshotEngine(this)
    val screenshots = Screenshotter(a11y, ioDispatcher, ::helperScreenshot)
    val ui: UiService = A11yUiService(this)
    val input: InputService = InputServiceImpl(this)
    val apps: AppsService = AppsServiceImpl(this)
    val waits: WaitService = WaitServiceImpl(this)
    val browser: BrowserService = browserService()
    internal val video =
        VideoStream(
            StreamLease(context, settings),
            helper,
            { deviceInfo.display() },
            context,
            ioDispatcher,
        )
    internal val audio = AudioStream(StreamLease(context, settings), helper, ioDispatcher)

    val server = ApiServer(this)
    val serverController =
        ServerController(scope, server, network, settings, MdnsAdvertiser(context))

    @Suppress("MissingUseCall")
    private fun browserService(): BrowserService =
        BrowserServiceImpl(
            io = ioDispatcher,
            listSockets = { helper.require().listDevtoolsSockets() },
            open = { name -> HelperDevtoolsSocket(helper, name) },
            contentBounds = { pkg -> ContentFrame.find(a11y.require().windows, pkg.orEmpty()) },
            touchAt = { rect, humanize, backend ->
                val outcome = touch.tap(rect, humanize = humanize, backend = backend)
                ActionResult(
                    ok = outcome.ok,
                    backend = outcome.backend,
                    points = outcome.points,
                    message = if (outcome.ok) null else "The system cancelled the gesture",
                )
            },
        )

    fun start() {
        power = PowerServiceImpl(this)
        state.start()
        network.start()
        HelperStatusNotifier(context, helper).start(scope)
        helperSupervisor.start()
    }

    private suspend fun helperScreenshot(): ByteArray? {
        val proxy = helper.getOrNull() ?: return null
        val pipe = withContext(ioDispatcher) { proxy.screencap() } ?: return null
        return withContext(ioDispatcher) {
            try {
                FileInputStream(pipe.fileDescriptor).use { it.readBytes() }
            } finally {
                pipe.close()
            }
        }
    }

    fun requirePower(): PowerService =
        power ?: throw ApiException.unavailable("power_unavailable", "Power control is not ready")

    /** Runs before every action and snapshot; returns whether the device was woken. */
    suspend fun prepareForAction(autoWake: Boolean, allowLocked: Boolean = false): Boolean =
        power?.prepareForAction(autoWake, allowLocked) == true

    fun screenRect(): Rect = deviceInfo.display().let { Rect(0, 0, it.widthPx, it.heightPx) }
}
