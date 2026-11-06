package io.i10nstaller.model

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.util.UUID

internal class PendingIntentInstallReceiver(
    context: Context,
    private val onResult: (Int, Int, String?, Intent) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val action = "${appContext.packageName}.INSTALL_RESULT.${UUID.randomUUID()}"
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
            val legacyStatus = intent.getIntExtra(
                EXTRA_LEGACY_STATUS,
                INSTALL_FAILED_INTERNAL_ERROR
            )
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            try {
                onResult(status, legacyStatus, message, intent)
            } finally {
                if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) close()
            }
        }
    }

    val pendingIntent: PendingIntent

    init {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true

        try {
            val intent = Intent(action).setPackage(appContext.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            pendingIntent = PendingIntent.getBroadcast(
                appContext,
                action.hashCode(),
                intent,
                flags
            )
        } catch (e: Exception) {
            appContext.unregisterReceiver(receiver)
            registered = false
            throw e
        } catch (e: LinkageError) {
            appContext.unregisterReceiver(receiver)
            registered = false
            throw e
        }
    }

    override fun close() {
        pendingIntent.cancel()
        if (registered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Install result receiver was already unregistered", e)
            }
            registered = false
        }
    }

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
