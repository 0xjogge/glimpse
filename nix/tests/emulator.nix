{ pkgs }:
let
  fakeEmulator = pkgs.writeShellScript "fake-emulator" ''
    [[ "$1" != -accel-check ]] || exit 0
    [[ -z "''${QT_PLUGIN_PATH:-}" ]] || exit 1
    printf '%s\n' "$$" > "$ANDROID_AVD_HOME/emulator.pid"
    trap 'exit 0' TERM
    while true; do sleep 0.1; done
  '';
  fakeAdb = pkgs.writeShellScript "fake-adb" ''
    if [[ "$1" == devices ]]; then
      printf 'List of devices attached\n'
    elif [[ -f "$ANDROID_AVD_HOME/emulator.pid" && "''${NEVER_BOOT:-0}" != 1 ]]; then
      printf '1\r\n'
    fi
  '';
  fakeAvdmanager = pkgs.writeShellScript "fake-avdmanager" ''
    directory="$ANDROID_AVD_HOME/glimpse-aosp-api-36.avd"
    mkdir -p "$directory"
    printf 'image.sysdir.1=/nix/store/old-sdk/image/\nhw.ramSize=2048\n' > "$directory/config.ini"
    printf 'path=%s\n' "$directory" > "$ANDROID_AVD_HOME/glimpse-aosp-api-36.ini"
    printf 'persistent user data\n' > "$directory/userdata-qemu.img"
  '';
  sdk = pkgs.runCommand "fake-android-sdk" { } ''
    mkdir -p "$out/bin" "$out/libexec/android-sdk/emulator" "$out/libexec/android-sdk/system-images/android-36/default/x86_64"
    ln -s ${fakeEmulator} "$out/libexec/android-sdk/emulator/emulator"
    ln -s ${fakeAdb} "$out/bin/adb"
    ln -s ${fakeAvdmanager} "$out/bin/avdmanager"
  '';
  emulator = import ../emulator.nix {
    inherit pkgs sdk;
    environment = {
      ANDROID_HOME = "${sdk}/libexec/android-sdk";
      ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
      GRADLE_OPTS = "-Dtest-sdk=true";
    };
  };
in
pkgs.runCommand "glimpse-emulator-check" { nativeBuildInputs = [ emulator pkgs.bash pkgs.coreutils pkgs.gnugrep ]; } ''
  trap 'cat ./*.log >&2' ERR
  export HOME="$TMPDIR/home"
  export ANDROID_AVD_HOME="$HOME/custom avds"
  export QT_PLUGIN_PATH=/desktop/qt/plugins
  glimpse-emulator --headless -- bash -c '
    [[ "$ANDROID_SERIAL" == emulator-5554 ]]
    [[ "$QT_PLUGIN_PATH" == /desktop/qt/plugins ]]
    printf "clean stdout\n"
  ' > stdout 2> success.log
  [[ "$(cat stdout)" == 'clean stdout' ]]
  ! kill -0 "$(cat "$ANDROID_AVD_HOME/emulator.pid")" 2>/dev/null
  glimpse-emulator --headless --port 5556 -- bash -c 'exit 7' > stdout 2> failure.log || status=$?
  [[ "$status" == 7 ]]
  [[ "$(cat "$ANDROID_AVD_HOME/glimpse-aosp-api-36.avd/userdata-qemu.img")" == 'persistent user data' ]]
  grep -qxF 'image.sysdir.1=system-images/android-36/default/x86_64/' "$ANDROID_AVD_HOME/glimpse-aosp-api-36.avd/config.ini"
  NEVER_BOOT=1 glimpse-emulator --headless --boot-timeout 1 -- touch unexpected > stdout 2> timeout.log || status=$?
  [[ "$status" == 1 && ! -e unexpected ]]
  ! kill -0 "$(cat "$ANDROID_AVD_HOME/emulator.pid")" 2>/dev/null
  glimpse-emulator --headless -- sleep 60 > stdout 2> signal.log &
  launcher=$!
  sleep 1
  kill -TERM "$launcher"
  wait "$launcher" || status=$?
  [[ "$status" == 143 ]]
  ! kill -0 "$(cat "$ANDROID_AVD_HOME/emulator.pid")" 2>/dev/null
  touch "$out"
''
