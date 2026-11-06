package io.i10nstaller.model

internal const val MATCH_ANY_USER = 0x00400000
internal const val MATCH_KNOWN_PACKAGES = 0x00402000
internal const val INSTALL_FROM_ADB = 0x00000020
internal const val INSTALL_REPLACE_EXISTING = 0x00000002
internal const val INSTALL_ALLOW_TEST = 0x00000004
internal const val INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS = 0x00000100
internal const val INSTALL_REQUEST_DOWNGRADE = 0x00000080
internal const val INSTALL_FULL_APP = 0x00004000
internal const val INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK = 0x01000000
internal const val INSTALL_REQUEST_UPDATE_OWNERSHIP = 0x02000000
internal const val INSTALL_SUCCEEDED = 1
internal const val INSTALL_FAILED_INTERNAL_ERROR = -110
internal const val DELETE_KEEP_DATA = 0x00000001
internal const val EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"
