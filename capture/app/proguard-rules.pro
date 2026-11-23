# kotlinx.serialization: keep the generated serializers for our models.
-keepclassmembers class org.fartingaround.app.model.** {
    *** Companion;
}
-keepclasseswithmembers class org.fartingaround.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.fartingaround.app.model.**$$serializer { *; }
