# Keep Acme Payments class names so Henosis Insights can attribute stack frames to this SDK
# in apps that minify with R8 (frames with obfuscated names cannot be matched and are dropped).
-keepnames class com.acme.payments.** { *; }
