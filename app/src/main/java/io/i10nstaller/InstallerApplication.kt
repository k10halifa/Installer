package io.i10nstaller

import android.app.Application
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class InstallerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", error)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private companion object {
        const val TAG = "InstallerCrash"
    }
}
