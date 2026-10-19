# LiteRT-LM uses JNI + reflection (tools, gson)
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class app.kelimo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.kelimo.**$$serializer { *; }
