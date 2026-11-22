# android-use

## Overview
Android Computer Use project providing Android screen operations (screen capture, text input, tap, gestures, etc.) for AI Agents.

## Tech Stack
- **Language / Platform**: Kotlin / Android (Gradle, AccessibilityService, MediaProjection, etc.)
- **SDK**: Android SDK at `$ANDROID_HOME` (`/opt/android-sdk`, API 34–36, Build-Tools 33–36, `adb`)
- **JDK**: OpenJDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`)

## Git Workflow
- **Development branch**: `main`
- **Change management**: Manage changes directly via commits on `main` rather than opening pull requests (unless explicitly requested otherwise).

## Environment Notes
- Hardware-accelerated Android Emulator (`/dev/kvm`) is not available in the cloud VM; verify builds and behavior using Gradle (`./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew lint`, Robolectric/unit tests).
