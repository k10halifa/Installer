package io.i10nstaller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import io.i10nstaller.model.InstallRepository
import io.i10nstaller.model.InstallStage

@SuppressLint("RestrictedApi")
class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val executor = ArchTaskExecutor.getInstance()
    private val repository = InstallRepository(getApplication())
    val currentInstallStage: LiveData<InstallStage>
        get() = repository.installResult
    val stagingProgress: LiveData<Int>
        get() = repository.stagingProgress

    fun preprocessIntent(intent: Intent) {
        if (repository.preCheck(intent)) {
            executor.executeOnDiskIO {
                repository.parseUri()
            }
        }
    }

    fun cleanupInstall() {
        repository.cleanupInstall()
    }

    fun initiateInstall(
        setInstaller: Boolean,
        grantPermissions: Boolean,
        deleteSource: Boolean,
        commit: Boolean,
        full: Boolean,
        removeSplit: Boolean
    ) {
        executor.executeOnDiskIO {
            repository.install(
                setInstaller,
                grantPermissions,
                deleteSource,
                commit,
                full,
                removeSplit
            )
        }
    }

    fun archivePackage(info: PackageInfo, uninstall: Boolean) {
        executor.executeOnDiskIO {
            repository.archivePackage(info, uninstall)
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        executor.executeOnDiskIO {
            repository.setPackageEnabled(packageName, enabled)
        }
    }
}
