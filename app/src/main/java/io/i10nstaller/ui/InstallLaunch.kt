package io.i10nstaller.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import io.i10nstaller.R
import io.i10nstaller.model.InstallAborted
import io.i10nstaller.model.InstallOptions
import io.i10nstaller.model.InstallStage
import io.i10nstaller.model.InstallUserAction
import io.i10nstaller.ui.fragments.BaseDialogFragment
import io.i10nstaller.ui.fragments.InstallErrorFragment
import io.i10nstaller.ui.fragments.InstallFlowDialogFragment
import io.i10nstaller.viewmodel.InstallViewModel
import java.lang.reflect.Constructor

class InstallLaunch : FragmentActivity() {

    private lateinit var installViewModel: InstallViewModel
    private lateinit var fragmentManager: FragmentManager
    private var silentApkInstall = false
    private var silentInstallStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        fragmentManager = supportFragmentManager
        installViewModel = ViewModelProvider(this)[InstallViewModel::class.java]
        fragmentManager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                val cls = loadFragmentClass(classLoader, className)
                val constructor = cls.constructors[0]!! as Constructor<Fragment>
                return if (constructor.parameterCount == 1) {
                    constructor.newInstance(installViewModel.currentInstallStage.value)
                } else {
                    constructor.newInstance()
                }
            }
        }

        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && isOptionsIntent(intent)) {
            showDialogInner(InstallErrorFragment(InstallAborted(InstallAborted.ABORT_INFO)))
            return
        }
        silentApkInstall = InstallOptions.getBoolean(
            this,
            InstallOptions.KEY_SILENT_MODE
        ) && isApkIntent(intent)
        if (silentApkInstall) makeActivityInvisible()
        if (savedInstanceState == null) {
            installViewModel.preprocessIntent(this.intent)
        }
        installViewModel.currentInstallStage.observe(this) { installStage: InstallStage ->
            onInstallStageChange(installStage)
        }
    }

    private fun onInstallStageChange(installStage: InstallStage) {
        when (installStage.stageCode) {
            InstallStage.Companion.STAGE_ABORTED -> {
                val aborted = installStage as InstallAborted
                if (silentApkInstall) {
                    if (aborted.abortReason != InstallAborted.ABORT_CLOSE) {
                        failSilentInstall()
                    } else {
                        finishSilentInstall()
                    }
                    return
                }
                if (aborted.abortReason == InstallAborted.ABORT_CLOSE) {
                    showDialogInner(null)
                    finish()
                } else {
                    showInstallStageDialog(installStage)
                }
            }

            InstallStage.Companion.STAGE_PARSE -> {
                if (!silentApkInstall) {
                    showInstallStageDialog(installStage)
                }
            }

            InstallStage.Companion.STAGE_USER_ACTION -> {
                val uar = installStage as InstallUserAction
                if (silentApkInstall) {
                    if (!silentInstallStarted) {
                        silentInstallStarted = true
                        installViewModel.initiateInstall(
                            InstallOptions.getBoolean(this, InstallOptions.KEY_SET_INSTALLER),
                            InstallOptions.getBoolean(this, InstallOptions.KEY_GRANT_PERMISSIONS),
                            InstallOptions.getBoolean(this, InstallOptions.KEY_DELETE_APK),
                            true,
                            uar.fullInstall,
                            false
                        )
                    }
                } else {
                    showInstallStageDialog(uar)
                }
            }

            InstallStage.Companion.STAGE_ARCHIVE -> {
                if (silentApkInstall) {
                    failSilentInstall()
                    return
                }
                showInstallStageDialog(installStage)
            }

            InstallStage.Companion.STAGE_INSTALLING -> {
                if (!silentApkInstall) {
                    showInstallStageDialog(installStage)
                }
            }

            InstallStage.Companion.STAGE_SUCCESS -> {
                if (silentApkInstall) {
                    finishSilentInstall()
                    return
                }
                showInstallStageDialog(installStage)
            }

            InstallStage.Companion.STAGE_FAILED -> {
                if (silentApkInstall) {
                    failSilentInstall()
                    return
                }
                showInstallStageDialog(installStage)
            }
        }
    }

    private fun showInstallStageDialog(stage: InstallStage) {
        if (fragmentManager.isDestroyed) return
        val currentDialog = fragmentManager.findFragmentByTag("dialog")
        if (currentDialog is InstallFlowDialogFragment) {
            currentDialog.updateStage(stage)
            return
        }
        val transaction = fragmentManager.beginTransaction()
        currentDialog?.let(transaction::remove)
        transaction.add(InstallFlowDialogFragment(stage), "dialog")
        transaction.commitNowAllowingStateLoss()
    }

    private fun showDialogInner(newDialog: DialogFragment?) {
        if (fragmentManager.isDestroyed) return
        val currentDialog = fragmentManager.findFragmentByTag("dialog") as DialogFragment?
        if (currentDialog is BaseDialogFragment && newDialog is BaseDialogFragment
            && currentDialog.mDialogData.stageCode == newDialog.mDialogData.stageCode
        ) {
            return
        }
        val transaction = fragmentManager.beginTransaction()
        currentDialog?.let(transaction::remove)
        newDialog?.let { transaction.add(it, "dialog") }
        transaction.commitAllowingStateLoss()
    }

    private fun isOptionsIntent(intent: Intent): Boolean =
        intent.data == null && intent.action in setOf(
            Intent.ACTION_MAIN,
            Intent.ACTION_APPLICATION_PREFERENCES,
            Intent.ACTION_SHOW_APP_INFO
        )

    private fun isApkIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_INSTALL_PACKAGE) {
            return false
        }
        val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: return false
        if (uri.scheme == "package" || uri.scheme == "market") return false
        if (intent.type == APK_MIME_TYPE || contentResolver.getType(uri) == APK_MIME_TYPE) {
            return true
        }
        val name = uri.lastPathSegment.orEmpty().ifBlank {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
        return name.substringBefore('?').endsWith(".apk", ignoreCase = true)
    }

    private fun makeActivityInvisible() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.START or Gravity.TOP)
        window.setLayout(1, 1)
        window.attributes = window.attributes.apply { alpha = 0f }
    }

    private fun finishSilentInstall() {
        installViewModel.cleanupInstall()
        finish()
    }

    private fun failSilentInstall() {
        Toast.makeText(this, R.string.silent_install_failed, Toast.LENGTH_SHORT).show()
        finishSilentInstall()
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }

}
