{
  description = "ReelyRelay — Android build shell (JDK + Android SDK, nothing else)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config.android_sdk.accept_license = true;
        config.allowUnfree = true;
      }));
    in
    {
      devShells = forAllSystems (pkgs:
        let
          # Keep in step with app/build.gradle.kts (compileSdk) and the
          # build-tools revision the Android Gradle Plugin asks for - the SDK
          # lives in the read-only Nix store, so Gradle can't fetch a missing
          # component into it on its own.
          platformVersion = "37";
          buildToolsVersion = "36.0.0";

          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ platformVersion ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
            includeNDK = false;
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            # gradle/gradle-daemon-jvm.properties pins the Gradle daemon to
            # Java 25; providing it here stops Gradle from downloading a
            # generic JDK that a stock NixOS can't run.
            buildInputs = [ pkgs.jdk25 ];

            JAVA_HOME = "${pkgs.jdk25}";
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;

            # Same reason as the JDK: the aapt2 that Gradle pulls from Maven
            # is a generic Linux binary. Use the Nix-patched one instead.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
          };
        });
    };
}
