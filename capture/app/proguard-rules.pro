# kotlinx.serialization: keep the generated serializers for our models.
-keepclassmembers class fartingaround.capture.model.** {
    *** Companion;
}
-keepclasseswithmembers class fartingaround.capture.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fartingaround.capture.model.**$$serializer { *; }
