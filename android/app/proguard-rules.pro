# R8 keep rules for the release build.
#
# The app has four kinds of reflective client the shrinker cannot see through:
#
#   1. JNA, which maps classes onto native memory and registers native methods
#      at runtime;
#   2. the uniffi bindings in `computer.iroh`, whose `Native.register` looks each
#      native method up by its exact name and JVM signature;
#   3. kotlinx.serialization, which reaches serializers through a generated
#      `Companion`, and SavedState, which restores navigation keys by class;
#   4. the Android components instantiated from a class name: the Glance widget
#      and the ML Kit component registrars.
#
# Compose, Media3, Hilt, Room, OkHttp and kotlinx.coroutines ship their own
# consumer rules; only what those rules do not cover is repeated here.  The
# annotations are read at runtime by JNA (`@Structure.FieldOrder`) and by the
# serialization plugin's generated descriptors.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# --- Kotlin null checks ------------------------------------------------------
# Every Kotlin call site already enforces its declared nullability at compile time; the
# `Intrinsics` helpers are the compiler's runtime belt-and-braces and only spend instructions and
# dex bytes in a release build. Removing them keeps the behaviour Kotlin code relies on
# (`throwUninitializedPropertyAccessException`, the `!!` operator and the range checks are *not*
# listed and stay): what goes is the argument and expression guards a compiled call cannot fail
# without a Java caller having lied about a type.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
}

# --- JNA ---------------------------------------------------------------------
# A `Structure` subclass declares its layout as fields; renaming or removing one
# moves the native offsets and the call silently reads the wrong memory.  A
# `Library` interface is proxied by name, and a `Callback` is invoked from native
# code through its method.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <init>();
}
-keepclassmembers interface * extends com.sun.jna.Library {
    <methods>;
}
-keepclassmembers class * implements com.sun.jna.Callback {
    <methods>;
}
# JNA's AWT helpers are desktop-only; the classes do not exist on Android and the
# code that references them is unreachable here.
-dontwarn java.awt.**

# --- iroh uniffi bindings ----------------------------------------------------
# `Native.register(UniffiLib::class.java, "iroh_ffi")` matches every `external
# fun` against the symbol table by name and signature.  The generated data
# classes are the exact shapes those functions copy across the boundary.
-keep class computer.iroh.** { *; }

# --- kotlinx.serialization ---------------------------------------------------
# Every `@Serializable` class keeps its companion so `serializer()` stays
# reachable.  The wire model is kept whole: an enum is looked up by its constants
# at runtime (`EnumSerializer` reads `enumConstants`), and a field removed by
# optimization turns that into a `null` array instead of a decode error.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-keep class com.phonxiety.bridge.model.** { *; }

# --- Navigation 3 keys -------------------------------------------------------
# `rememberNavBackStack` restores the back stack from a Bundle through
# SavedStateConfiguration.  The keys are kept whole, companions included, because
# `serializer(KClass)` is a reflective lookup and a restore must not depend on
# what the optimizer could see.
-keep class com.phonxiety.bridge.*Key { *; }
-keep class com.phonxiety.bridge.*Key$* { *; }
-keep,allowobfuscation,allowshrinking class * implements androidx.navigation3.runtime.NavKey
-keepclassmembers class * implements androidx.navigation3.runtime.NavKey {
    static ** Companion;
}

# --- Glance widget -----------------------------------------------------------
# The host binds the widget from its receiver; Glance instantiates the
# `GlanceAppWidget` from the generic type at runtime.
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { <init>(); }

# --- ML Kit component registrars --------------------------------------------
# ML Kit initialises itself from the merged manifest: `MlKitContext` reads the
# `<meta-data>` entries of `MlKitComponentDiscoveryService` and instantiates each
# `ComponentRegistrar` with `Class.getDeclaredConstructor().newInstance()`.
# firebase-components 16.1.0 only ships `-keep class * implements
# ComponentRegistrar`, which keeps the class but lets R8 delete the no-arg
# constructor; the registrar is then dropped with "Invalid component registrar",
# `MlKitContext` has no `SharedPrefManager`, and the Google code scanner dies in
# its constructor with the null `SharedPrefManager` (the wizard crash at
# `GmsBarcodeScanning.getClient`). ML Kit 17.3.0 still pulls firebase-components
# 16.1.0, so the application has to keep the constructors itself.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# --- Room --------------------------------------------------------------------
# Entities are instantiated by the generated database implementation, and the
# schema validation compares column names against the generated metadata.
-keep @androidx.room.Entity class * { *; }

# --- Hilt --------------------------------------------------------------------
# The generated component is instantiated by name from the application class and
# every `@AndroidEntryPoint` is discovered by the activity/service that extends
# it; the view-model constructor is called by the generated factory.
-keepclassmembers @dagger.hilt.android.lifecycle.HiltViewModel class * {
    <init>(...);
}
