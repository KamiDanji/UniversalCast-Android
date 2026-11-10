# Java-WebSocket and slf4j are used reflectively in a few places.
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-keep class com.universalcast.** { *; }
