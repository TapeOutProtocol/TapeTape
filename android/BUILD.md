# Building TapeTape v2 for Android

## Toolchain

| Tool | Version used |
|---|---|
| Rust | 1.100 (stable) with `cargo-ndk` 4.1 |
| Android NDK | 29.0.14206865 (the script picks the newest one under `~/Library/Android/sdk/ndk`, or uses `ANDROID_NDK_HOME`) |
| JDK | 17 |
| Gradle | 9.8 (wrapper) |
| Android Gradle Plugin | 9.5 |
| Kotlin | 2.4 |
| SDK levels | compileSdk 37, targetSdk 36, minSdk 26 |

## 1. Build the Rust core

```
../core/scripts/build-android.sh
```

The script:
- Installs `cargo-ndk` and the four Rust Android targets if they are missing.
- Builds `libtapetape_core.so` for armeabi-v7a, arm64-v8a, x86 and x86_64.
- Generates the UniFFI Kotlin bindings.

Outputs:
- `core/out/android/jniLibs/<abi>/libtapetape_core.so`
- `core/out/android/kotlin/uniffi/tapetape_core/tapetape_core.kt`

Run it again whenever the core changes. The app build picks up both folders directly.

## 2. Build the app

Run these from `android/`.

| Build | Command | Notes |
|---|---|---|
| Debug, real core | `./gradlew assembleDebug` | Needs step 1. If the core outputs are missing, Gradle falls back to the fake core. |
| Debug, fake core | `./gradlew assembleDebug -Ptapetape.core=fake` | In-memory demo data for UI work; nothing is kept after the process dies. |
| Release APK + AAB | `./gradlew assembleRelease bundleRelease` | R8 minify and resource shrinking. `Log.d`/`Log.v` are stripped. |

Outputs:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`: universal, all four ABIs, about 42 MB.
- `app/build/outputs/bundle/release/app-release.aab`: about 26 MB; an arm64 device downloads about 9 MB.

## 3. Release signing: `keystore.properties`

Copy `keystore.properties.example` to `android/keystore.properties` and fill it in:

```
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

- The file, `*.jks` and `*.keystore` are git-ignored. Never commit them.
- Without the file, `assembleRelease` produces an unsigned APK.

## 4. Large attachments: `tapetape.filesBackend`

- Large attachments need a relay files backend. Set it with the Gradle property `tapetape.filesBackend`.
- It defaults to empty, which leaves each account's setting unchanged.
- Value: the backend origin, `https://<backend-domain>`, with no path. The core adds `/v1/objects` itself.
- Where to set it:
  - In `android/gradle.properties`: `tapetape.filesBackend=https://files.example.com`
  - Or per build: `./gradlew assembleRelease -Ptapetape.filesBackend=https://files.example.com`
- It becomes `BuildConfig.FILES_BACKEND`. When non-empty, the app calls `setRelayFilesBackend` on every account at startup and on each account it activates, which covers new and restored profiles.

## 5. Versioning

- `versionCode` and `versionName` are in `app/build.gradle.kts`, under `defaultConfig`. They are currently `10` and `2.0.8`.
- Bump `versionCode` for every build that gets distributed. Upgrading in place keeps the account data.
