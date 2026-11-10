# Keep kotlinx.serialization @Serializable models and generated serializers for Schema v6 persistence.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.google.android.desktop.apprestorer.model.**$$serializer { *; }
-keepclassmembers class com.google.android.desktop.apprestorer.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.google.android.desktop.apprestorer.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.google.android.desktop.apprestorer.model.** { *; }
