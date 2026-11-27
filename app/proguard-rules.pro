# R8 rules for the release build.

# Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable

# Strip every android.util.Log call from the release build, so nothing (secrets included) can reach logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
    public static java.lang.String getStackTraceString(...);
}

# Bouncy Castle: only the Argon2 generator is used, and it is referenced directly, so R8 can shrink the rest.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
