# Contributing to Googlebook App Restorer

Thank you for contributing! Please follow the guidelines below when submitting issues or pull requests.

## Prerequisites

- **JDK 17+** (JDK 21 supported)
- **Android SDK** (`compileSdk = 36`, `minSdk = 33`)

## Building & Testing Locally

1. **Run all unit tests, Robolectric Compose UI tests, and deterministic fuzzers**:
   ```bash
   ./gradlew testDebugUnitTest
   ```

2. **Run only the fuzzing & property-based suite**:
   ```bash
   ./gradlew testDebugUnitTest --tests "com.google.android.desktop.apprestorer.WorkspaceFuzzAndPropertyTest"
   ```

3. **Run Android Lint static analysis**:
   ```bash
   ./gradlew lintDebug
   ```

4. **Assemble Debug & R8-minified Release APKs**:
   ```bash
   ./gradlew assembleDebug assembleRelease
   ```

## Architecture Guidelines

- Keep core state-machine, inspection, and planning logic (`capture/`, `inspect/`, `restore/`, `data/`) **pure Kotlin** behind lightweight interfaces (`UiNode`, `AppCatalog`, `LaunchCapabilities`, `RestoreDriver`, `UsageEventSource`) so every edge case can be tested and fuzzed on the host JVM in milliseconds.
- Add regression and property/fuzz tests alongside any changes to window classification, Virtual Desk reconciliation, or Chrome tab restoration.
- Never add network permissions, dynamic code loading, hidden/non-SDK reflection, or ADB/root dependencies.
