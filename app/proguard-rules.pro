# Kijk – R8 voorzichtig: optimaliseren en ongebruikte code weghalen, maar niets hernoemen.
# Zo blijven crashlog (/crash) en foutmeldingen leesbaar en werkt alles wat op naam zoekt.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,InnerClasses,Signature,EnclosingMethod

# Deurbel live: WebRTC roept vanuit native code (JNI) Java-klassen aan
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Dolby/DTS-geluid: FFmpeg-decoder wordt via reflectie geladen en gebruikt native code
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**

# Alle native methoden behouden
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# ViewModel wordt via reflectie aangemaakt
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }
