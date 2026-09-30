package net.die.phoneapi.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import kotlinx.coroutines.withContext
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.awaitDeviceState
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.LaunchRequest

/**
 * Launching, stopping, clearing and deep-linking apps. Installing is out of scope.
 *
 * Launching goes through the platform, so it works without the helper; `stop` and `clear` are
 * privileged and need the helper's shell UID (`apps.manage` in the capability map).
 */
class AppsServiceImpl(private val graph: AppGraph) : AppsService {
    private val packages: PackageManager = graph.context.packageManager

    override suspend fun list(launchableOnly: Boolean): List<AppInfo> =
        withContext(graph.ioDispatcher) {
            val launchable = launchablePackages()
            val names = if (launchableOnly) launchable.toList() else installedPackages()
            names
                .mapNotNull { name -> appInfo(name, name in launchable) }
                .sortedBy { it.label.lowercase() }
        }

    override suspend fun launch(packageName: String, request: LaunchRequest): ActionResult {
        val woke = graph.prepareForAction(autoWake = true)
        val intent = launchIntent(packageName, request)
        start(intent)
        val arrived = !request.wait || awaitForeground(packageName)
        graph.snapshots.invalidate()
        return ActionResult(
            ok = arrived,
            backend = "activity",
            woke = woke,
            seq = graph.uiTracker.seq.value,
            message = if (arrived) null else notForeground(intent, packageName),
        )
    }

    override suspend fun stop(packageName: String): ActionResult =
        manage(packageName, "stop", listOf("am", "force-stop", packageName))

    override suspend fun clear(packageName: String): ActionResult =
        manage(packageName, "clear", listOf("pm", "clear", packageName))

    override suspend fun intent(request: IntentRequest): ActionResult {
        val woke = graph.prepareForAction(autoWake = true)
        val intent = buildIntent(request)
        start(intent)
        // Null once the target is showing, or when the intent doesn't name a package to wait for.
        val missing =
            (request.packageName ?: intent.component?.packageName)?.takeIf {
                !awaitForeground(it)
            }
        graph.snapshots.invalidate()
        return ActionResult(
            ok = missing == null,
            backend = "activity",
            woke = woke,
            seq = graph.uiTracker.seq.value,
            message = missing?.let { notForeground(intent, it) },
        )
    }

    private suspend fun manage(
        packageName: String,
        what: String,
        argv: List<String>,
    ): ActionResult {
        if (packageName == graph.context.packageName) {
            throw ApiException.badRequest("Refusing to $what PhoneAPI itself")
        }
        if (packageInfo(packageName) == null) {
            throw ApiException.notFound("$packageName is not installed")
        }
        val result = graph.shell.exec(argv)
        if (!result.ok) {
            throw ApiException(
                409,
                "${what}_failed",
                "`${argv.joinToString(" ")}` exited ${result.exit}: ${result.failureMessage()}",
            )
        }
        graph.snapshots.invalidate()
        return ActionResult(
            ok = true,
            backend = "shell",
            seq = graph.uiTracker.seq.value,
            message = result.stdout.trim().takeIf { it.isNotEmpty() },
        )
    }

    private fun launchIntent(packageName: String, request: LaunchRequest): Intent {
        val activity = request.activity
        val intent =
            if (activity == null) {
                packages.getLaunchIntentForPackage(packageName)
                    ?: throw ApiException.notFound(
                        "$packageName is not installed, or has no launcher activity"
                    )
            } else {
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(packageName, qualify(packageName, activity)))
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (request.fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return intent
    }

    private fun buildIntent(request: IntentRequest): Intent {
        val intent = Intent(request.action)
        request.data?.let { intent.data = it.toUri() }
        request.packageName?.let(intent::setPackage)
        request.component?.let {
            intent.component =
                ComponentName.unflattenFromString(it)
                    ?: throw ApiException.badRequest(
                        "component must look like com.example/.MainActivity"
                    )
        }
        request.categories.forEach(intent::addCategory)
        request.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        request.flags?.let(intent::addFlags)
        return intent
    }

    private fun start(intent: Intent) {
        try {
            graph.context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw ApiException(
                404,
                "not_found",
                "Nothing handles ${intent.action ?: "this intent"}",
                cause = e,
            )
        } catch (e: SecurityException) {
            throw ApiException(
                403,
                "forbidden",
                "${describe(intent)} is not exported, so it can only be started by its own app",
                cause = e,
            )
        }
    }

    private suspend fun awaitForeground(packageName: String): Boolean =
        awaitDeviceState(graph.state, graph.bus, FOREGROUND_WAIT_MS) {
            it.foregroundPackage == packageName
        }

    private fun appInfo(packageName: String, launchable: Boolean): AppInfo? {
        val info = packageInfo(packageName) ?: return null
        val app = info.applicationInfo ?: return null
        return AppInfo(
            packageName = packageName,
            label = app.loadLabel(packages).toString(),
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            enabled = app.enabled,
            launchable = launchable,
        )
    }

    /** Apps with a launcher entry, which is what the manifest's `<queries>` makes visible. */
    @Suppress("DEPRECATION")
    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(intent, 0).mapNotNullTo(LinkedHashSet()) {
            it.activityInfo?.packageName
        }
    }

    /**
     * Android 11 hides packages an app hasn't declared an interest in, so this is everything this
     * app can see rather than everything installed. The helper's `pm list packages` sees them all.
     */
    @Suppress("DEPRECATION", "QueryPermissionsNeeded")
    private fun installedPackages(): List<String> =
        packages.getInstalledPackages(0).map { it.packageName }

    // The PackageInfoFlags overloads only exist from API 33, and this behaves identically.
    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo? =
        try {
            packages.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun notForeground(intent: Intent, packageName: String) =
        "Started ${describe(intent)}, but $packageName did not come to the foreground. Android " +
            "blocks background activity starts for some apps; check /v1/ui/snapshot."

    private fun describe(intent: Intent): String =
        intent.component?.flattenToShortString() ?: intent.`package` ?: intent.action.orEmpty()

    private fun qualify(packageName: String, activity: String): String =
        if (activity.startsWith(".")) packageName + activity else activity

    private companion object {
        const val FOREGROUND_WAIT_MS = 5_000L
    }
}
