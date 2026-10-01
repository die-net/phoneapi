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
import net.die.phoneapi.browser.HelperCdpPipes
import net.die.phoneapi.browser.HelperDevtoolsSocket
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.DeviceInfoProvider
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.SettingsStore
import net.die.phoneapi.core.TlsPasswordStore
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.helperclient.HelperLogcatFeed
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
import net.die.phoneapi.server.AudioFeed
import net.die.phoneapi.server.DeviceFacts
import net.die.phoneapi.server.MdnsAdvertiser
import net.die.phoneapi.server.NetworkWatcher
import net.die.phoneapi.server.PairingManager
import net.die.phoneapi.server.ServerController
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.TlsManager
import net.die.phoneapi.server.TokenStore
import net.die.phoneapi.server.VideoFeed
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
    val tls by lazy { TlsManager(filesDir, TlsPasswordStore(filesDir).chars()) }
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
    val touchBackends =
        TouchBackends(
            a11y = A11yTouchBackend(a11y),
            inject = InjectTouchBackend(helper, ioDispatcher),
        )
    val keyBackends =
        KeyBackends(ime = ImeKeyBackend(a11y), helper = InjectKeyBackend(helper, ioDispatcher))
    val touch = TouchInput(touchBackends, Humanizer()) { deviceInfo.display() }
    val targeting = NodeTargeting(::screenRect)
    val snapshots =
        SnapshotEngine(
            a11y = a11y,
            io = ioDispatcher,
            seq = uiTracker.seq,
            device = state,
            nodes = nodes,
            screenRect = ::screenRect,
        )
    val screenshots = Screenshotter(a11y, ioDispatcher, ::helperScreenshot)
    val power: PowerService =
        PowerServiceImpl(
            context = context,
            settings = settings,
            seq = uiTracker.seq,
            a11y = a11y,
            state = state,
            bus = bus,
            shell = shell,
            pins = pins,
            touch = touch,
            display = { deviceInfo.display() },
            io = ioDispatcher,
        )
    val ui: UiService =
        A11yUiService(
            prepare = power::prepareForAction,
            snapshots = snapshots,
            a11y = a11y,
            io = ioDispatcher,
            nodes = nodes,
            targeting = targeting,
            touch = touch,
            seq = uiTracker.seq,
        )
    val input: InputService =
        InputServiceImpl(
            prepare = power::prepareForAction,
            io = ioDispatcher,
            snapshots = snapshots,
            seq = uiTracker.seq,
            touch = touch,
            a11y = a11y,
            keyBackends = keyBackends,
            state = state,
            targeting = targeting,
            display = { deviceInfo.display() },
            touchBackends = touchBackends,
        )
    val apps: AppsService =
        AppsServiceImpl(
            context = context,
            io = ioDispatcher,
            prepare = power::prepareForAction,
            invalidateSnapshots = snapshots::invalidate,
            seq = uiTracker.seq,
            shell = shell,
            state = state,
            bus = bus,
        )
    val browser: BrowserService = browserService()
    val waits: WaitService =
        WaitServiceImpl(
            prepare = power::prepareForAction,
            uiTracker = uiTracker,
            state = state,
            bus = bus,
            snapshots = snapshots,
            a11y = a11y,
            io = ioDispatcher,
            browser = browser,
            helper = helper,
        )
    internal val video =
        VideoStream(
            StreamLease(context, settings),
            helper,
            { deviceInfo.display() },
            context,
            ioDispatcher,
        )
    internal val audio = AudioStream(StreamLease(context, settings), helper, ioDispatcher)

    val pairing = PairingManager(tokens, scope)
    val mdns = MdnsAdvertiser(context)

    val services =
        ServerServices(
            ui = ui,
            input = input,
            apps = apps,
            waits = waits,
            browser = browser,
            power = power,
            tokens = tokens,
            ioDispatcher = ioDispatcher,
            device = GraphDevice(deviceInfo),
            screenshots = { scale -> this.screenshots.png(scale) },
            writePin = { pin -> pins.write(pin) },
            events = bus.events,
            video = VideoFeed { session, spec -> this.video.serve(session, spec) },
            audio = AudioFeed { session -> this.audio.serve(session) },
            viewerHtml = { asset("viewer.html") },
            shell = { argv -> this.shell.exec(argv) },
            cdp = HelperCdpPipes(helper, ioDispatcher),
            viewerText = { viewerLink() },
            logcat = HelperLogcatFeed(helper, bus, ioDispatcher),
            pairing = pairing,
            pairHtml = { asset("pair.html") },
            pins = { tls.pins },
        )

    val server = ApiServer({ tls }, services)
    val serverController = ServerController(scope, server, network, settings, mdns)

    private suspend fun asset(name: String): ByteArray =
        withContext(ioDispatcher) { context.assets.open(name).use { it.readBytes() } }

    @Suppress("MissingUseCall")
    private fun browserService(): BrowserService =
        BrowserServiceImpl(
            io = ioDispatcher,
            listSockets = { helper.require().listDevtoolsSockets() },
            open = { name -> HelperDevtoolsSocket(helper, name, dispatcher = ioDispatcher) },
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
            prepare = { autoWake -> power.prepareForAction(autoWake) },
            sequence = { uiTracker.seq.value },
            invalidateSnapshots = { snapshots.invalidate() },
        )

    fun start() {
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

    fun screenRect(): Rect = deviceInfo.display().let { Rect(0, 0, it.widthPx, it.heightPx) }

    private fun viewerLink(): String {
        val port = settings.current.port
        val host = network.lanAddress.value?.hostAddress ?: "127.0.0.1"
        return "https://$host:$port/viewer?access_token=TOKEN\n" +
            "Replace TOKEN with this device's bearer token. " +
            "The query parameter is accepted only by GET /viewer and by WebSocket upgrades."
    }
}

private class GraphDevice(private val provider: DeviceInfoProvider) : DeviceFacts {
    override fun info() = provider.info()

    override fun capabilities() = provider.capabilities()

    override fun versionName() = provider.versionName()
}
