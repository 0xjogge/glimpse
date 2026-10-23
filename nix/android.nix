{ pkgs }:
let
  buildToolsVersion = "36.0.0";
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "36" "37" ];
    buildToolsVersions = [ buildToolsVersion ];
    cmakeVersions = [ ];
    includeEmulator = true;
    includeNDK = false;
    includeSources = false;
    includeSystemImages = true;
    systemImageTypes = [ "default" ];
    abiVersions = [ "x86_64" ];
  };
  sdk = android.androidsdk;
  sdkRoot = "${sdk}/libexec/android-sdk";
  environment = {
    JAVA_HOME = pkgs.jdk21.home;
    ANDROID_HOME = sdkRoot;
    ANDROID_SDK_ROOT = sdkRoot;
    GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
  };
in
{
  inherit sdk environment;
  emulator = import ./emulator.nix { inherit pkgs sdk environment; };
}
