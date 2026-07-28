# BYDMate DiLink 2 / BYD Seagull — handoff

This document is the starting context for an agent continuing the port on
another computer.

## Repository and goal

- Repository: `https://github.com/hix83/BYDMate-DiLink2-Seagull`
- Main development branch: `dilink2-seagull`
- Upstream: `https://github.com/AndyShaman/BYDMate`
- Target head unit: BYD DiLink 2, assumed Android 9 (API 28), arm64-v8a.
- Target vehicle: BYD Seagull.
- Primary vehicle integration on DiLink 2: Di+ (`com.van.diplus`).

The current goal is to keep the modern BYDMate UI and application logic while
restoring the older Di+ data/command path used before the DiLink 5 native
autoservice integration.

## Current implementation

### Android 9 and ABI support

`app/build.gradle.kts` uses:

- `minSdk = 28`;
- `targetSdk = 29` to retain legacy external-storage behavior;
- debug ABIs: `arm64-v8a`, `x86_64`;
- release ABI: `arm64-v8a`.

The x86_64 native libraries make the debug APK much larger, but allow it to run
in the Android 9 emulator. Release remains arm64-only for the head unit.

### Platform selection

Files under `app/src/main/kotlin/com/bydmate/app/data/platform/` classify API 28
as `DILINK2` and API 29+ as `MODERN_DILINK`.

`PlatformParsReader` selects transports in this order:

- DiLink 2: Di+ -> native reader -> debug emulator mock;
- newer DiLink: native reader -> Di+.

After three consecutive Di+ failures, the watchdog attempts to start:

`com.van.diplus/com.van.diplus.activity.StartMainServiceActivity`

Relaunch attempts have a five-minute cooldown.

### Di+ integration

- `DiPlusParsReader`: reads `http://127.0.0.1:8988/api/getDiPars`.
- `DiParsControlClient`: sends commands to
  `http://127.0.0.1:8988/api/sendCmd`.
- `DiPlusDbReader`: imports trip history from
  `/storage/emulated/0/vandiplus/db/van_bm_db`, table `TripInfo`.
- Imported Di+ trips use `TripSource.DIPLUS`.
- Dangerous raw commands containing `发送CAN`, `执行SHELL`, or `下电` are
  blocked by the command client.

The Chinese Di+ field template was restored from an older BYDMate version. It
must be validated against the exact Di+ build installed on the vehicle.

### Emulator mock

`MockDiPlusParsReader` generates a repeating 90-second Seagull city-drive
scenario with changing speed, power, SOC, odometer, battery temperatures,
climate state, doors, and tire pressure.

It is available only when both conditions are true:

1. `BuildConfig.DEBUG`;
2. Android identifies the device as an emulator.

It is therefore unavailable in release builds and cannot replace real Di+ on a
physical head unit.

## Development environment

Known working setup:

- Windows 11 / PowerShell;
- Temurin JDK 17;
- Android Studio 2026.1;
- Android SDK Platform 34 and Build Tools 34;
- Android Emulator with Google APIs Android 9 / API 28 x86_64.

Set these variables for the current PowerShell session:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
```

This checkout contains the POSIX `gradlew` script but no `gradlew.bat`. On
Windows, invoke the wrapper directly:

```powershell
& "$env:JAVA_HOME\bin\java.exe" `
  -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/BYDMate-v3.8.1.apk`

Run unit tests with:

```powershell
& "$env:JAVA_HOME\bin\java.exe" `
  -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain testDebugUnitTest
```

If Gradle is interrupted and locks
`app/build/test-results/testDebugUnitTest/binary/output.bin`, stop its daemons
before retrying:

```powershell
& "$env:JAVA_HOME\bin\java.exe" `
  -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain --stop
```

## Emulator

Known AVD name: `BYDMate_DiLink2_API28`.

A conservative launch command that remained stable on the original machine:

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" `
  -avd BYDMate_DiLink2_API28 `
  -no-snapshot -no-boot-anim `
  -gpu swiftshader_indirect -memory 2048
```

Install and launch:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r `
  app\build\outputs\apk\debug\BYDMate-v3.8.1.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell monkey `
  -p com.bydmate.app -c android.intent.category.LAUNCHER 1
```

Expected emulator warnings:

- Di+ cannot connect to `127.0.0.1:8988`; the debug mock then supplies data.
- Native autoservice/ADB helper is unavailable.
- GPS fixes are unavailable unless a location is injected into the AVD.

These warnings are expected and are not application crashes.

## Validation already performed

- Debug APK compiles successfully.
- Android 9 x86_64 installation succeeds.
- Main dashboard renders at 1920x1080 landscape.
- Mock data reaches the normal trip tracker and dashboard.
- No `FATAL EXCEPTION`, `UnsatisfiedLinkError`, or `NoSuchMethodError` was
  observed during the emulator run.
- Platform detector, Di+ parser, watchdog, vehicle API, history importer, and
  trip-source tests were added or updated.

## Next work on a real vehicle

The emulator proves Android 9 compatibility and application data flow, but not
the proprietary vehicle interface. On a DiLink 2 head unit:

1. Confirm the Android API level and CPU ABI with ADB.
2. Record the installed Di+ package/version and verify port 8988.
3. Capture a redacted response from `/api/getDiPars` and compare every field
   with `DiPlusParsReader`.
4. Verify whether Di+ requires authentication, a foreground service, or
   storage permissions on that firmware.
5. Test read-only signals first: SOC, speed, odometer, 12 V voltage, battery
   temperature.
6. Validate command syntax using a harmless climate or UI command before any
   vehicle-control expansion.
7. Copy and inspect the Di+ `TripInfo` schema before relying on history import.
8. Add Seagull-specific signal mappings as captured evidence becomes
   available; do not guess CAN identifiers.

Keep the native modern-DiLink path intact so upstream improvements can still be
merged. Vehicle-specific behavior should remain behind the platform layer.
