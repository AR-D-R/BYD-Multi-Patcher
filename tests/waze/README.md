# Waze production patch regression

Run `validate.ps1 -Apkm <stock 5.24.0.1 APKM> -JavaHome <JDK directory>`.

This compiles the production Waze engine and its real binary utilities into a standalone harness, checks the exact source hash, and calls the production `patchBaseApk` method with force fallback disabled. It tests EV + CCS2 on/off and the new microphone-removal option on/off. Output contains unsigned test APKs and the untouched base for comparison; none is intended for installation.

Expected result for v2.1: existing fixed binary patches remain **27/27** recognized with EV enabled and **20/20** with EV disabled. The stable profile must also locate the `ReportsMenuLayout.kt:447` action lambda in `classes7.dex` and raise its trailing submenu spacer from 8dp to 64dp. With microphone removal enabled, the engine must locate `SearchFieldIdleAction` in `classes6.dex` and redirect both voice enum cases to its existing no-action branch. With microphone removal disabled, those original voice branches must remain unchanged.

Final layout positioning still requires a device test in landscape.
