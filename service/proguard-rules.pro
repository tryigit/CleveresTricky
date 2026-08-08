# Add project specific ProGuard rules here.

# Keep entry point
-keepclasseswithmembers class cleveres.tricky.cleverestech.MainKt {
    public static void main(java.lang.String[]);
}

# Keep JNI Callbacks (Critical for native binder interception)
-keep class cleveres.tricky.cleverestech.KeystoreInterceptor { *; }
-keep class cleveres.tricky.cleverestech.TelephonyInterceptor { *; }
# BinderInterceptor abstract class might be used
-keep class cleveres.tricky.cleverestech.binder.BinderInterceptor { *; }

# Keep BouncyCastle providers
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**

# Prevent compilation failure on older SDKs
-dontwarn java.net.http.**

# Aggressive Obfuscation
-repackageclasses 'x'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute 'SourceFile'

# Optimization
-optimizationpasses 5
-mergeinterfacesaggressively
