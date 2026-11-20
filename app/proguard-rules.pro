# Add project specific ProGuard rules here.
-keep class com.smartdevicelink.** { *; }
-dontwarn com.smartdevicelink.**

# ★ kuromoji：词典以 classpath 资源（org/atilika/kuromoji/ipadic/*.bin）形式加载，
#   类本身不通过反射加载，但分词器内部会按固定类名读取资源，保守全保留。
-keep class org.atilika.kuromoji.** { *; }
-dontwarn org.atilika.kuromoji.**
