# Keep the onnxruntime bridge (accessed via direct calls; belt-and-braces).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Service + receiver are referenced from the manifest; keep them and their entry points.
-keep public class com.adwaithvarma.heyron.WakeService { *; }
-keep public class com.adwaithvarma.heyron.BootReceiver { *; }
-keep public class com.adwaithvarma.heyron.MainActivity { *; }
# The engine is loaded via the WakeWordEngine interface; keep implementations.
-keep public class com.adwaithvarma.heyron.OpenWakeWordEngine { *; }
