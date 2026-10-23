# ML Kit discovers Firebase component registrars by manifest metadata and invokes
# their no-argument constructors reflectively. AGP's optimized shrinking must
# preserve those entry points even when all ordinary calls have been inlined.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
