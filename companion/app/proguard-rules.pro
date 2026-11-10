# Aggressive R8 rules for minimal APK size
-repackageclasses ''
-allowaccessmodification
-mergeinterfacesaggressively

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep native methods if any
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom JSON/Data models if needed
-keepclassmembers class * {
    void *(android.view.View);
}
