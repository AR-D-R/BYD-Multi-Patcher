# Waze APKM patch flow

Stable tested patch profile: Waze `5.24.0.1` (`versionCode 1030729`) from the linked APKMirror Android 10+ `arm64-v8a`, `480-640dpi` APKM bundle.

Waze `5.24.4.900` is also retained as a known binary profile because its 14 patch regions were previously re-derived. It is not the stable target and remains labelled **UNTESTED** in the UI. Other Waze versions use the closest available profile and relaxed compatibility/force mode.

## What the wide-screen patch changes

Compared with stock Waze, the stable 5.24.0.1 patch modifies 16 DEX regions to implement the BYD layout:

- Main driving map/car geometry is centred across the full display instead of being biased by the stock phone/tablet layout.
- The driving vehicle vertical inset is derived from viewport height (`height / 5`) rather than the original placement path.
- Speedometer uses the landscape Row weighting rather than `screenWidthDp` or raw pixels. In the 5.24.4.900 profile the horizontal correction is derived from Waze's own geometry: shared MidDrive `-8dp`, plus a PreDrive-only `-8dp` caller offset; Y remains `-24dp`. The stable 5.24.0.1 profile retains its previously tested `-12dp` X / `-24dp` Y placement.
- The landscape side-panel width multiplier changes from `1.0` to approximately `0.449` in active navigation and route preview.
- BYD landscape top controls use targeted +16dp Y offsets: the actual `NavigationInstructionsBarColumn` moves the black manoeuvre card down; the top-right live `ButtonPack` (music/audio + speaker) is shifted after its `TopEnd` alignment; and the optional `CompassV1` gets an explicit 16dp top padding in addition to its existing 16dp start padding. The map remains edge-to-edge.
- Landscape safe-area branches are neutralised so Waze follows the side-clearance path consistently on the BYD display.
- The wide-screen map-geometry copy/coroutine path is patched so the centred geometry is retained through layout updates.
- Route-preview/overview vehicle anchoring changes from `height / 4` to `height / 3`.
- The vehicle-type pill gets 20dp of Compose padding.
- Active-navigation final viewport placement is set to `0.56f`.
- The start-state drawer collapsed peek height changes from 80dp to 60dp.
- The audio/music button starts hidden/off instead of being shown by default.

## APKM flow

The `.apkm` file is treated as a container, not as an installable APK:

1. Open the APKM as ZIP, read the `base.apk` manifest, confirm package `com.waze`, version/versionCode and the required arm64/density split structure.
2. Extract `base.apk` and all split APKs to app-private temporary storage.
3. Patch only `base.apk`.
4. Repair modified DEX signatures/checksums while preserving APK ZIP entry sizes and alignment.
5. Sign `base.apk` and every split APK with the same AndroidKeyStore certificate used by the Patcher.
6. Install all signed APKs together in one `PackageInstaller` full-install session.

## Compatibility and force mode

For an untested Waze build the Patcher selects a profile and processes each region in that profile as follows:

1. Try the profile's original offset and verify the stock bytes.
2. If they moved, search for the unique stock signature/anchor and relocate the patch.
3. If the signature is missing or ambiguous and the user accepted the untested-version warning, **force fallback** writes the profile's replacement bytes at the profile offset anyway.
4. Repair DEX checksums/signatures and continue signing/installing.

Force fallback is deliberately permissive. It only checks that the target offset exists inside the DEX; it does **not** prove that the surrounding method is still the same. A forced build may crash, fail Dalvik/ART verification, fail to launch, or behave incorrectly. This mode exists specifically so minor Waze revisions can be tried without waiting for a new patch profile.

Waze remains package `com.waze`. Because the official Waze build is signed by Waze/Google, Android will not allow the locally signed patched build to replace an official installation. The first patched installation therefore requires uninstalling an existing official Waze. Later builds produced by the same Patcher installation use the same local signing identity and can update in place subject to Android version-code rules.
