# The abstract-socket engine constructs Ktor's internal CIO call by reflection.
-keep class io.ktor.server.cio.CIOApplicationCall { *; }

# The helper is started by app_process via reflection on its main class.
-keep class net.die.phoneapi.helper.Main { public static void main(java.lang.String[]); }
-keep class net.die.phoneapi.helper.** { *; }

# Netty / Ktor reference optional classes that are not present on Android.
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn org.jboss.marshalling.**
-dontwarn com.google.protobuf.**
-dontwarn io.micrometer.**
-dontwarn java.lang.management.**
-keep class io.netty.** { *; }
-keepclassmembers class * extends io.netty.channel.ChannelInboundHandler { *; }

# R8 full mode shrinks Ktor's Netty engine: the first TLS handshake succeeds, later ones are reset.
-keep,allowobfuscation,allowoptimization class io.ktor.server.netty.** { *; }
