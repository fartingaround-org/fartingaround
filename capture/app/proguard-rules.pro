# kotlinx.serialization: keep the generated serializers for our models.
-keepclassmembers class org.fartingaround.app.model.** {
    *** Companion;
}
-keepclasseswithmembers class org.fartingaround.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.fartingaround.app.model.**$$serializer { *; }

# The same for serializable classes anywhere else in the app (net/, and any
# added later), so a release build never loses one that a debug build has.
-keepclassmembers class org.fartingaround.app.** {
    *** Companion;
}
-keepclasseswithmembers class org.fartingaround.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.fartingaround.app.**$$serializer { *; }
