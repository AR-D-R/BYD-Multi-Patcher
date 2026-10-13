# Building

Open the top-level project folder in Android Studio. The project contains one Android application module: **app**.

Use the normal Android Studio **Build** action or:

```powershell
.\gradlew.bat :app:assembleDebug
```

Output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

The build automatically generates the standalone DEX assets used by Spotify AutoResume, the Spotify LHS hook and ABRP Direct BYD telemetry. There is no nested companion application to build.

Root helper task:

```powershell
.\gradlew.bat buildPatcher
```
