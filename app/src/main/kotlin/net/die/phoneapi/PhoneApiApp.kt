package net.die.phoneapi

import android.annotation.SuppressLint
import android.app.Application

class PhoneApiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }

    companion object {
        // Holds only the Application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        lateinit var graph: AppGraph
            private set
    }
}
