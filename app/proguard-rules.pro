-repackageclasses
-allowaccessmodification
-overloadaggressively
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    ** requireNonNull(...);
}
-dontwarn dalvik.system.VMRuntime
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keepclassmembers class io.i10nstaller.viewmodel.InstallViewModel {
    <init>(android.app.Application);
}
-keepnames class moe.shizuku.api.BinderContainer
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
