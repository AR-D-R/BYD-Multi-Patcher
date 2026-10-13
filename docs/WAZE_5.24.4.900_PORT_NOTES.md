# Waze 5.24.4.900 port notes

Source bundle: APKMirror Android 10+ / arm64-v8a / 480-640dpi  
Version: 5.24.4.900  
Version code: 1030731  
APKs in APKM: 28 (base + 27 splits)  
`base.apk` SHA-256: `fa6eda7b54c58e3200ccbd55d95b7782c442c07918b4969651521b9a1fd3b4fb`

## Port status

The 5.23 patch map cannot be offset-shifted into 5.24.4.900. Waze moved and rewrote several of the target methods, so the patch regions were re-derived against the exact 5.24.4.900 APKMirror `base.apk`.

All 14 patch regions now have stock-byte signatures taken from 5.24.4.900 and are checked before modification. No 5.23 DEX offsets are used by the active Waze patch profile.

### classes5.dex
- `0x4DEE5C` — main map geometry: adaptive vertical vehicle inset and full-width horizontal centring.

### classes6.dex
- `0x36F058` — audio/music button startup state defaults to off.
- `0x37D4BC` — shared speedometer AndroidView uses `fillMaxWidth(0.55078125)` + `wrapContentWidth(Alignment.CenterHorizontally)`, then `-8dp` X / `-24dp` Y. The `-8dp` is derived from Waze's real MidDrive map viewport: 16dp right margin with the vehicle at 50% of the remaining viewport. No `screenWidthDp` or screenshot-fitted X value is used.
- `0x3ADF68` — PreDrive-only caller patch replaces a redundant `BoxScope.align(TopStart)` on a fresh Modifier with `offset(-8dp, 0dp)`. Combined with the shared `-8dp`, this yields `-16dp` for PreDrive, accounting for the 16dp Row gap plus the 16dp right viewport margin.
- `0x390DF4` — landscape side-panel width ratio.
- `0x393898` — active-navigation landscape safe-area path.
- `0x3938AA` — active-navigation landscape safe-area path.
- `0x3973C8` — wide-screen map geometry copy path.
- `0x3A9FB0` — route-preview landscape safe-area path.
- `0x3A9FDA` — route-preview side-panel width ratio.
- `0x3AA044` — route-preview landscape safe-area path.
- `0x3ACCA8` — route-preview/overview vehicle anchor.
- `0x3BE88E` — vehicle-type pill 20dp Compose padding.
- `0x3D2C32` — active-navigation final viewport placement (0.56f).

### classes7.dex
- `0x3F5304` — start-state drawer collapsed peek height, 80dp to 60dp.

The shared speedometer replacement contains five Compose reference placeholders. The PreDrive caller patch contains one additional `OffsetKt.offset-VpY3zN4` placeholder. The Patcher resolves all of them from the selected `classes6.dex` field/method pools immediately before writing the regions.

## Verification performed

- The exact APKM passes version, split-count and `base.apk` SHA-256 validation.
- Every patch region's **before** signature was verified against the stock 5.24.4.900 DEX.
- All regions were applied in a local binary simulation with no signature mismatches.
- Modified `classes5.dex`, `classes6.dex` and `classes7.dex` were repaired with valid DEX SHA-1 signatures and Adler-32 checksums.
- The updated `WazePatchEngine.java` was syntax-compiled independently.

This establishes binary/profile compatibility with the supplied APKM. Final UI geometry and installation behaviour still need the normal device/emulator smoke test because those cannot be proven from static DEX validation alone.
