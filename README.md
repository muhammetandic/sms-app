# sms-app

Android SMS app. Kotlin + Jetpack Compose, `minSdk 30` (Android 11), `compileSdk`/`targetSdk` 37.

Sideloaded, not distributed through Play. SMS with dual-SIM send choice, a block list, a drafts screen, and a settings screen. No backend, no cross-device sync.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds install as `com.anttech.smsapp.debug` so they can sit alongside a release build.

## Toolchain

`JAVA_HOME`, `ANDROID_SDK_ROOT`, and `ANDROID_HOME` are exported from `~/.zshrc`. A JDK 17 is at `/opt/homebrew/opt/openjdk@17`. `local.properties` points at the SDK and is not committed.

## Default SMS app

Android grants `ROLE_SMS` only to an app that declares the full eligible component set, so the manifest carries all three even though only one is functionally used:

| Component | Purpose |
| --- | --- |
| `SmsDeliverReceiver` | Receives `SMS_DELIVER`, delivered only to the default SMS app |
| `WapPushDeliverReceiver` | Deliberate no-op, declared only to qualify for `ROLE_SMS`; the app processes SMS only |
| `RespondViaMessageService` | Required for role qualification; handles reply-with-message |

Requesting the role goes through `RoleManager.ROLE_SMS`. Losing the role mid-session revokes SMS permissions immediately, so the UI must degrade to read-only rather than crash.

## Release signing

Release signing is configured but inert until credentials are supplied. Create a keystore outside the repo, then pass the values as Gradle properties:

```bash
./gradlew :app:assembleRelease \
  -PSMS_STORE_FILE=/path/outside/repo/release.jks \
  -PSMS_STORE_PASSWORD=... \
  -PSMS_KEY_ALIAS=... \
  -PSMS_KEY_PASSWORD=...
```

Without those properties the release variant builds unsigned. Keep one keystore forever: a new key means Android refuses to install over the existing app, which is unrecoverable without uninstalling and losing local data.

## Layout

```
app/src/main/java/com/anttech/smsapp/
  MainActivity.kt          Compose entry point, role request flow
  SmsApp.kt                Application class
  data/
    DataGraph.kt           Construction of the stores
    MessageRepository.kt   Local device-only message store
    local/                 Room entities, DAOs, database, address normalization
    telephony/             Telephony provider reads
  sms/
    receive/               Incoming SMS: receiver, worker, reassembly, dedupe key
    block/                 Persisted block list applied on arrival
    respond/               Respond-via-message service
    role/                  ROLE_SMS wrapper
    send/                  SMS routing, multi-part send-result aggregation, SIM pick
  ui/theme/                Material 3 theme
  ui/settings/             Settings: default-app role, SIM choice, block list, about
```

## Data

Two independent stores, because they answer different questions.

**Room (`data/local`)** is the app's own record and is always readable, whether or not the app holds the default SMS role. Conversations are keyed by a normalized address rather than the Telephony provider's `thread_id`, which gets renumbered across devices and resets. `dedupe_key` is uniquely indexed so the duplicate delivery that a default SMS app sees does not land twice in a thread. Outgoing rows carry a nonce in that key instead, because sending the same text twice is two real messages.

**Telephony provider (`data/telephony`)** is readable only while the app holds `ROLE_SMS`, and returns an empty list rather than throwing when it does not. Column reads are guarded per column because OEM providers omit columns.

**Role transition** is the sharp edge: both stores are live at once, so losing the role mid-session means the UI's source of truth changes underneath it. Nothing reconciles the two. Phase 3 owns the UX for that — it must degrade to read-only rather than crash.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Pure logic (multipart reassembly, dedupe keys, send-result aggregation, address normalization) runs as plain JVM tests. `MessageRepositoryTest` uses Robolectric at SDK 30 to exercise the real Room schema, including the unique-index behavior the dedupe design depends on. `SmsDatabaseMigrationTest` migrates every exported schema in `app/schemas` up to the current version through Room's own migration machinery, validating each result against the current export, and asserts that existing conversation and message rows survive the trip. Room exports its schema to `app/schemas` for migration review; that directory is also wired into the debug variant's assets so Robolectric can feed it to `MigrationTestHelper` (debug-only, the release APK ships no schemas).

Not covered by unit tests: anything touching `SmsManager` or the Telephony provider. Those need a device on a real carrier.

## License

![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)

MIT — see [LICENSE](LICENSE). © 2026 Muhammet Andiç