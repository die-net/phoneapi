package net.die.phoneapi

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import net.die.phoneapi.core.StoreIo

class PhoneApiApp : Application() {
    override fun onCreate() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
            )
        }
        super.onCreate()
        StoreIo.markMain(Thread.currentThread())
        // filesDir stats the directory. Do that off the main thread; reuse the File afterwards.
        val files = StoreIo.call { filesDir }
        graph = AppGraph(this, filesDir = files).also { it.start() }
    }

    companion object {
        // Holds only the Application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        lateinit var graph: AppGraph
            private set

        fun graphOrNull(): AppGraph? = if (this::graph.isInitialized) graph else null
    }
}
