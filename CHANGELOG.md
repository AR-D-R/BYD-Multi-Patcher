## What changed in v2.1

- Added **BYD instrument-cluster track display** for Spotify 8.9 and 9.1. The feature is enabled by default and can be turned off if required.
- Added a separate **Patch & Save APKM** option for supported apps. Patched APKMs are timestamped and saved to:
  **Downloads/BYD-Multi-Patcher/** They can then be copied to another vehicle or reinstalled using an APKM-capable file manager such as CX File Explorer.
- Added a separate **Waze 5.9** profile. This older version may work better on slower BYD hardware.
- Fixed **ABRP BYD Cerence TTS language support**. Cerence TTS is no longer limited to English and can now use the selected supported navigation language.
- Added a **built-in APK/APKM file browser**, particularly for older DiLink systems such as DiLink 3 where the Android system file picker may not work correctly. The option to use an external file manager such as CX File Explorer is still available.
  Thanks to **lexwah** for contributing.
- Added an optional **Debug Mode** for Waze 5.24, Waze 5.9 and ABRP. It is disabled by default. If an app is not working correctly, enable Debug Mode before patching and install the debug version. The app will collect diagnostic information for approximately 5 minutes and save the log to:
  **Downloads/BYD-Multi-Patcher/** The log can then be shared with me to help investigate the problem. Debug logging is designed to exclude location and other personal data.


# BYD Multi Patcher 2.0

## 2.0

- Renamed **AIVI Multi Patcher** to **BYD Multi Patcher**.
- Promoted the working ABRP BYD Cerence TTS integration to production.
- Keeps first-launch BYD Cerence + English selection for fresh ABRP installs when English is available.
- Exposes every Cerence TTS voice reported by the vehicle to ABRP and applies Navigation Voice changes through `ArkFramework.setTtsVoiceId(...)`.
- Keeps direct BYD battery telemetry and Automatic Day/Night default patches.
- Removed ABRP native-TTS/Cerence diagnostic test controls and their diagnostic source files.
- Removed the temporary persistent ABRP TTS diagnostic file and ABRP runtime Logcat tracing.
- Removed temporary diagnostic-only permissions from the patcher app and restored target SDK 35.

- Waze 5.9 active navigation: corrected the vehicle-anchor patch to the live D$t$a branch used by ContinuouslyTrackUserLocation; force Y to ~0.56 without changing X centring.
