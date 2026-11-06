package io.i10nstaller.model

import android.content.Intent
import android.content.IntentSender

internal object ModernInstallResultSender {
    @JvmStatic
    fun create(
        callback: (Int, Int, String?, Intent) -> Unit
    ): IntentSender {
        val receiver = LocalIntentReceiver(callback::invoke)
        return receiver.intentSender as IntentSender
    }
}
