-dontwarn javax.annotation.**

# JNI bridge — keep native methods and callback interface
-keep class com.translive.app.engine.TranslationEngine {
    native <methods>;
    *;
}
-keep class com.translive.app.engine.TranslationEngine$TokenCallback { *; }
-keep class com.translive.app.engine.TranslationEngine$StreamResult { *; }

# Sherpa-ONNX (TTS + STT)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ML Kit text recognition
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }

# OcrEngine
-keep class com.translive.app.engine.OcrEngine { *; }

# Room entities
-keep class com.translive.app.data.model.** { *; }
-keep class com.translive.app.data.db.** { *; }
