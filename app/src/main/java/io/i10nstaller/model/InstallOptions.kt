package io.i10nstaller.model

import android.content.Context
import android.content.SharedPreferences

object InstallOptions {
    private const val PREFERENCES_NAME = "install_options"

    const val KEY_SET_INSTALLER = "set_installer"
    const val KEY_GRANT_PERMISSIONS = "grant_permissions_and_battery"
    const val KEY_DELETE_APK = "delete_apk_after_install"
    const val KEY_SILENT_MODE = "silent_mode"

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean =
        preferences(context).getBoolean(key, defaultValue)

    @JvmStatic
    fun hasValue(context: Context, key: String): Boolean = preferences(context).contains(key)

    @JvmStatic
    fun setBoolean(context: Context, key: String, value: Boolean) {
        preferences(context).edit().putBoolean(key, value).apply()
    }
}
