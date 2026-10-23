{
  description = "Glimpse native Android development environment";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/7fd4004d0511991fff3a0524856a9be09e28c5f0";
  outputs =
    { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      android = import ./nix/android.nix { inherit pkgs; };
    in
    {
      devShells.${system}.default = pkgs.mkShell (
        android.environment
        // {
          packages = [ pkgs.jdk21 pkgs.python3 pkgs.nodejs pkgs.scrcpy android.sdk android.emulator ];
        }
      );
      packages.${system}.emulator = android.emulator;
      apps.${system}.emulator = {
        type = "app";
        program = pkgs.lib.getExe android.emulator;
        meta = android.emulator.meta;
      };
      checks.${system}.emulator = import ./nix/tests/emulator.nix { inherit pkgs; };
    };
}
