# AGENTS.md

Android SMS client. Kotlin + Jetpack Compose, single `:app` module, no backend.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # run the unit test suite
./gradlew :app:lintDebug              # Android lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Always export `JAVA_HOME` first.** This project's toolchain is JDK 17; `~/.zshrc` points Java at the Android Studio JBR, so export Homebrew's `openjdk@17` before any Gradle invocation. The SDK path comes from `local.properties` (`sdk.dir`, gitignored).

Debug installs as `com.oaktech.smsapp.debug` alongside a release build.

Toolchain: AGP 9.4.1, AGP built-in Kotlin 2.4.21 (no `org.jetbrains.kotlin.android`), KSP, Gradle 9.8.1 wrapper, `minSdk 30` / `compileSdk` `targetSdk` 37, Java 17. Versions live in `gradle/libs.versions.toml` — add new dependencies there, never inline coordinates in `app/build.gradle.kts`. KSP registers generated sources via the (now-opted-back-in) `kotlin.sourceSets`, so keep `android.disallowKotlinSourceSets=false` in `gradle.properties`.

## Architecture

- **`data/DataGraph.kt`** — process-wide singleton entry point for all stores (`messageRepository`, `telephonyDataSource`, `roleManager`, `providerImport`). Nothing constructs a store directly; tests swap one in with `DataGraph.overrideForTest(context, repository)`.
- **Two stores, deliberately independent.** Room (`data/local`) is the app's own record, always readable. The Telephony provider (`data/telephony`) is readable only while the app holds `ROLE_SMS` and returns an empty list rather than throwing when it does not. Conversations key on a normalized `address_key`, not the provider's `thread_id`. `dedupe_key` is uniquely indexed: inbound rows dedupe on it, outgoing rows carry a nonce because the same text twice is two real messages.
- **`sms/`** — `receive/` (deliver receivers, multipart reassembly, dedupe key), `send/` (multi-part result aggregation, SIM selection), `block/`, `role/`, `respond/`. `receive/WapPushDeliverReceiver.kt` is a deliberate no-op: the manifest must declare a `WAP_PUSH_DELIVER` receiver for `ROLE_SMS`, but the app processes SMS only.
- **`sms/send/SmsTransport.kt`** — interface over `SmsManager` so send orchestration is testable off-device. Platform-only code sits behind such seams; keep it that way.
- **`ui/`** — one package per screen, each with `*Screen.kt` (Composables) + `*ViewModel.kt`. ViewModels expose `companion object fun factory(...) : ViewModelProvider.Factory`; the NavHost in `ui/SmsApp.kt` wires them. No DI framework — construction is explicit at the call site.
- **Role is the sharp edge.** `ROLE_SMS` can be revoked mid-session, which flips both stores' availability at once. UI must degrade to read-only, never crash; `ui/components/ReadOnlyBanner.kt` exists for this. Re-read permissions on every resume instead of caching a "granted" flag.

## Conventions

- Comments explain *why*, not *what*; they are dense and long by design. Match that style rather than narrating code.
- Room schema exports to `app/schemas`, wired into the debug variant's assets only (release ships none). Bumping `SmsDatabase` version requires a `MIGRATION_n_n` in `SmsDatabase.kt`, a new exported schema, and the migration test covers it automatically.
- `ALTER TABLE ... DROP COLUMN` is unavailable (SQLite 3.32 on API 30) — use the table-recreate recipe and toggle `PRAGMA foreign_keys`, as in `MIGRATION_12_13`.
- Strings go in `res/values/strings.xml`; `values-tr` is the Turkish translation and `TurkishTranslationTest` fails if it drifts.

## Tests

`./gradlew :app:testDebugUnitTest` — JUnit 4, all JVM, no device needed.

- Pure logic (reassembly, dedupe keys, send-result aggregation, address normalization) is plain JVM.
- Room tests use `testing/inlineInMemoryDatabase.kt` — inline executors so a `Flow` write/read is synchronous with Turbine. Do not use `Room.inMemoryDatabaseBuilder` directly when observing a DAO flow.
- ViewModels use `testing/MainDispatcherRule` and must pass its `testDispatcher` into `runTest`, so Main and the test share one scheduler.
- `MessageRepositoryTest` is Robolectric at SDK 30 (real Room schema + unique index). Default Robolectric SDK is 33 (`app/src/test/resources/robolectric.properties`) because it cannot run 36 yet; override per test with `@Config(sdk = [...])`.
- `SmsDatabaseMigrationTest` migrates every schema in `app/schemas` up through Room's own machinery and validates against the current export.
- Not covered: anything touching `SmsManager` or the Telephony provider — those need a device on a real carrier.

## Repo

Not a git repo until initialized. `.gitignore` covers `build/`, `.gradle/`, `local.properties`, keystores. Release signing is inert without `-PSMS_STORE_FILE=...` Gradle properties; never commit a keystore or its passwords.
