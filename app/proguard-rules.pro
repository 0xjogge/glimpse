# ML Kit and CameraX ship their consumer rules. Keep the platform-instantiated components.
-keep class app.glimpse.assistant.** { public <init>(...); }
