package android.content.pm;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.os.Build;

import androidx.annotation.RequiresApi;

public class PackageManager_rename {

    public static final int GET_ACTIVITIES = 0x00000001;


    public static final int GET_RECEIVERS = 0x00000002;


    public static final int GET_SERVICES = 0x00000004;


    public static final int GET_PROVIDERS = 0x00000008;


    public static final int GET_INSTRUMENTATION = 0x00000010;


    public static final int GET_INTENT_FILTERS = 0x00000020;


    public static final int GET_SIGNATURES = 0x00000040;


    public static final int GET_RESOLVED_FILTER = 0x00000040;


    public static final int GET_META_DATA = 0x00000080;


    public static final int GET_GIDS = 0x00000100;


    public static final int GET_DISABLED_COMPONENTS = 0x00000200;


    public static final int MATCH_DISABLED_COMPONENTS = 0x00000200;


    public static final int GET_SHARED_LIBRARY_FILES = 0x00000400;


    public static final int GET_URI_PERMISSION_PATTERNS = 0x00000800;


    public static final int GET_PERMISSIONS = 0x00001000;


    public static final int GET_UNINSTALLED_PACKAGES = 0x00002000;


    public static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;


    public static final int GET_CONFIGURATIONS = 0x00004000;


    public static final int GET_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;


    public static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;


    public static final int MATCH_DEFAULT_ONLY = 0x00010000;


    public static final int MATCH_ALL = 0x00020000;


    public static final int MATCH_DIRECT_BOOT_UNAWARE = 0x00040000;


    public static final int MATCH_DIRECT_BOOT_AWARE = 0x00080000;


    public static final int MATCH_SYSTEM_ONLY = 0x00100000;


    public static final int MATCH_FACTORY_ONLY = 0x00200000;


    public static final int MATCH_ANY_USER = 0x00400000;


    public static final int MATCH_KNOWN_PACKAGES = 0x00402000;


    public static final int MATCH_INSTANT = 0x00800000;


    public static final int MATCH_VISIBLE_TO_INSTANT_APP_ONLY = 0x01000000;


    public static final int MATCH_EXPLICITLY_VISIBLE_ONLY = 0x02000000;


    public static final int MATCH_STATIC_SHARED_AND_SDK_LIBRARIES = 0x04000000;


    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;


    public static final int MATCH_DIRECT_BOOT_AUTO = 0x10000000;


    public static final int MATCH_DEBUG_TRIAGED_MISSING = 0x10000000;


    public static final int MATCH_CLONE_PROFILE = 0x20000000;


    public static final int MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS = 0x20000000;


    public static final int MATCH_APEX = 0x40000000;


    public static final int GET_ATTRIBUTIONS = 0x80000000;


    public static final long GET_ATTRIBUTIONS_LONG = 0x80000000L;


    public static final long MATCH_ARCHIVED_PACKAGES = 1L << 32;


    public static final long MATCH_QUARANTINED_COMPONENTS = 1L << 33;


    public static final long MATCH_CLONE_PROFILE_LONG = 1L << 34;


    public static final int INSTALL_REPLACE_EXISTING = 0x00000002;


    public static final int INSTALL_ALLOW_TEST = 0x00000004;


    public static final int INSTALL_INTERNAL = 0x00000010;


    public static final int INSTALL_FROM_ADB = 0x00000020;


    public static final int INSTALL_ALL_USERS = 0x00000040;


    public static final int INSTALL_REQUEST_DOWNGRADE = 0x00000080;


    public static final int INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS = 0x00000100;


    public static final int INSTALL_FORCE_VOLUME_UUID = 0x00000200;


    public static final int INSTALL_FORCE_PERMISSION_PROMPT = 0x00000400;


    public static final int INSTALL_INSTANT_APP = 0x00000800;


    public static final int INSTALL_DONT_KILL_APP = 0x00001000;


    public static final int INSTALL_FULL_APP = 0x00004000;


    public static final int INSTALL_ALLOCATE_AGGRESSIVE = 0x00008000;


    public static final int INSTALL_VIRTUAL_PRELOAD = 0x00010000;


    public static final int INSTALL_APEX = 0x00020000;


    public static final int INSTALL_ENABLE_ROLLBACK = 0x00040000;


    public static final int INSTALL_DISABLE_VERIFICATION = 0x00080000;


    public static final int INSTALL_ALLOW_DOWNGRADE = 0x00100000;


    public static final int INSTALL_STAGED = 0x00200000;


    public static final int INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS = 0x00400000;


    public static final int INSTALL_DISABLE_ALLOWED_APEX_UPDATE_CHECK = 0x00800000;


    public static final int INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK = 0x01000000;


    public static final int INSTALL_REQUEST_UPDATE_OWNERSHIP = 1 << 25;


    public static final int INSTALL_FROM_MANAGED_USER_OR_PROFILE = 1 << 26;


    public static final int INSTALL_ARCHIVED = 1 << 27;


    public static final int INSTALL_IGNORE_DEXOPT_PROFILE = 1 << 28;


    public static final int INSTALL_UNARCHIVE_DRAFT = 1 << 29;


    public static final int INSTALL_UNARCHIVE = 1 << 30;


    public static final int INSTALL_DEVELOPMENT_FORCE_NON_STAGED_APEX_UPDATE = 1;

    public static final int INSTALL_SUCCEEDED = 1;

    public static final int INSTALL_FAILED_INTERNAL_ERROR = -110;

    public static final int DELETE_KEEP_DATA = 0x00000001;

    public static final int DELETE_ALL_USERS = 0x00000002;

    public static final int DELETE_SYSTEM_APP = 0x00000004;

    public static final int DELETE_DONT_KILL_APP = 0x00000008;

    public static final int DELETE_ARCHIVE = 0x00000010;

    public static final int DELETE_CHATTY = 0x80000000;

    public static final int DELETE_SUCCEEDED = 1;

    public static final int DELETE_FAILED_INTERNAL_ERROR = -1;

    public static final int DELETE_FAILED_DEVICE_POLICY_MANAGER = -2;

    public static final int DELETE_FAILED_USER_RESTRICTED = -3;

    public static final int DELETE_FAILED_OWNER_BLOCKED = -4;

    public static final int DELETE_FAILED_ABORTED = -5;

    public native int installExistingPackage(String packageName, int installReason);

    @RequiresApi(Build.VERSION_CODES.S)
    public native void addUniquePreferredActivity(IntentFilter filter, int match,
                                                  ComponentName[] set, ComponentName activity);

    public native void deleteApplicationCacheFilesAsUser(String packageName,
                                                         int userId, IPackageDataObserver observer);

}
