# R8 rules for the release build.
#
# AGP also combines everything under src/main/keepRules; this file holds the rules that are
# ours rather than tool-generated.

# kotlinx.serialization: the plugin generates a `Companion.serializer()` on each @Serializable
# class and looks it up reflectively in a few paths. Keep the generated serializers and the
# synthetic $$serializer classes, or wire encoding fails only in release — the worst way to
# find out, since the wire format is how the two apps talk (PLAN.md §2).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.jirofeingold.pairfortwo.**$$serializer { *; }
-keepclassmembers class com.jirofeingold.pairfortwo.** {
    *** Companion;
}
-keepclasseswithmembers class com.jirofeingold.pairfortwo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
