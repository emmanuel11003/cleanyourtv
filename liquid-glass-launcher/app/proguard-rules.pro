# Version 6: maximum shrinking. Names are obfuscated and classes repackaged for a smaller dex
# and a faster class load on a slow TV CPU. Crash stack traces are mapped back with
# app/build/outputs/mapping/release/mapping.txt (keep it with each released APK):
#   retrace mapping.txt stacktrace.txt
-repackageclasses ''
-allowaccessmodification

# Line numbers cost nothing here (R8 encodes them in the instruction offsets for minSdk 26+),
# so keep them for a retraced crash that points at the right line.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Debug and info logging never runs in the release build. Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
