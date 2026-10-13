# Waze 5.24.0.1 stable port notes

Source bundle: APKMirror Android 10+ / arm64-v8a / 480-640dpi  
Version: 5.24.0.1  
Version code: 1030729  
APKs in APKM: 28 (base + 27 splits)  
`base.apk` SHA-256: `cc49acf7a9eb00d9a990e12c6d269c30dffd2780182481c8506fe5c46ebc6860`

## Port status

The full BYD wide-screen patch map was ported from the working 5.24.4.900 beta profile to the stable 5.24.0.1 branch. The corresponding Waze methods were matched by class/method signature and aligned code structure; version-specific field, type and method references were translated to the 5.24.0.1 DEX pools.

All 20 patch regions use stock-byte signatures taken from the exact 5.24.0.1 APKMirror `base.apk`. The tested offsets are tried first, then unchanged signatures may be relocated. Short constants use unique surrounding anchors. From v1.1.7, an untested build can also use the explicit force fallback after the user warning: if a signature is missing or ambiguous, the profile replacement bytes are written at the profile offset as long as that offset still exists inside the DEX. This is intentionally unsafe and may crash.

### classes5.dex
- `0x473510` — main map geometry: adaptive vertical vehicle inset and full-width horizontal centring.

### classes6.dex
- `classes6.dex @ 0x37DBE4` — `LandscapeSideMapContent` → `ButtonPackWrapper` (`MidDriveScreen.kt`): the live music/audio + speaker button pack is anchored with `Modifier.align(TopEnd)` and previously had no top clearance. Append `OffsetKt.offset(0dp, +16dp)` to that aligned modifier before it is passed to `ButtonPackWrapper` / `ButtonPackLandscape`.
- `classes6.dex @ 0x37F7CC` — `MidDriveOverMapComponents` → `CompassV1`: the optional compass is a separate control, not part of the button pack. Its modifier was `align(TopStart).padding(start=16dp)`; change the existing Compose padding defaults so both start and top are explicitly 16dp.
- The earlier `classes7.dex @ 0x450C8E` `MapOverlayTopButtons` patch was removed. DEX tracing showed that composable owns route/search overlay buttons (`onBack`, `onClose`, `onSearchArea`) and is not the live navigation compass/music/speaker layer.
- `0x37D2E6` — `NavigationInstructionsBarColumn` input modifier: apply a fixed +16dp Y offset to the landscape manoeuvre/instruction column. This is the visible black navigation card path (`MidDriveScreen.kt:920`).
- `0x358E80` — audio/music button startup state defaults to off.
- `0x3672E4` — speedometer AndroidView uses `fillMaxWidth(0.55078125)` + `wrapContentWidth(Alignment.CenterHorizontally)`, then offset `-12dp` on X and `-24dp` on Y. The 0.55078125 factor is `1 - 0.44921875`, matching the patched left-panel Row weight so the marker lands on the midpoint of the complete two-section landscape Row; no `screenWidthDp` positioning is used; the fixed -12dp trim aligns the speedometer with the observed vehicle marker.
- `0x37AC1C` — landscape side-panel width ratio.
- `0x37D6C0` / `0x37D6D2` — active-navigation landscape safe-area path.
- `0x3811F0` — wide-screen map geometry copy path.
- `0x393878` / `0x39390C` — route-preview landscape safe-area path.
- `0x3938A2` — route-preview side-panel width ratio.
- `0x396570` — route-preview/overview vehicle anchor.
- `0x3A8156` — vehicle-type pill 20dp Compose padding.
- `0x3BC4FA` — active-navigation final viewport placement (0.56f).

### classes7.dex
- `0x419EC8` — start-state drawer collapsed peek height, 80dp to 60dp.

The speedometer replacement contains five Compose reference placeholders that the Patcher resolves from the selected `classes6.dex` field/method pools immediately before writing the region. The new ButtonPack top-offset patch likewise resolves `OffsetKt.offset-VpY3zN4` dynamically instead of hard-coding its DEX method index.

## Verification performed

- The supplied APKM reports Waze `5.24.0.1`, `versionCode 1030729`, Android 10+, arm64-v8a, 480/640dpi and contains 28 APK splits.
- The exact APKM passes `base.apk` SHA-256 validation.
- Every patch region's **before** signature was verified against the stock 5.24.0.1 DEX files.
- All 20 stable regions were applied in a local binary simulation with no signature mismatches.
- Modified `classes5.dex`, `classes6.dex` and `classes7.dex` were repaired and re-parsed successfully with valid DEX SHA-1 signatures and Adler-32 checksums.

The binary profile is therefore internally consistent with the supplied stable APKM. Final visual geometry and installation behaviour still require the normal emulator/head-unit smoke test.

## v1.1.29 pre-drive top clearance

The v1.1.28 controls patch only targeted MidDrive. The pre-drive screen uses separate Compose call sites in `com.waze.main_screen.s.al`:

- `0x395F64`, method O / LandscapeMainContent: the Box containing HamburgerButtonAndPromotionalChip and notifications retains full width and receives `offset(0dp,16dp)`. The replaced default wrapContentHeight is redundant under the enclosing Column's zero minimum child height. Register v10 remains zero for the following Box measure policy.
- `0x3964E6`, method P / LandscapeMapSideContent: preserve the aligned ButtonPack modifier, callbacks, state, and surrounding register values; append `offset(0dp,16dp)`. Passing changed=0 lets Compose calculate child parameter changes normally.
- `0x3978D6`, method U / PreDriveOverMapComponents: retain 16dp start padding, add 16dp top padding when the existing v29 landscape flag is true. The explicit TopStart alignment is redundant with the enclosing Box's TopStart policy, which supplies room for the conditional without changing method size. Portrait top padding remains zero.

The two new offset templates resolve OffsetKt.offset-VpY3zN4 dynamically. All replacements preserve code-item size and APK entry alignment. Existing active-navigation and beta regions are byte-for-byte unchanged.

Validation used the production Java patchBaseApk function on the exact supplied source, with force fallback disabled: 20/20 regions matched. All 157,722 method bodies across the three patched DEX files decoded; DEX SHA-1 and Adler-32 checks passed. Changed methods passed branch-target and move-result adjacency checks. Mocked execution of the new blocks confirmed +16dp offsets, preserved ButtonPack arguments, and compass portrait/landscape branching. These are binary/static checks, not a head-unit visual test.

### v1.1.30 crash correction

The v1.1.29 menu replacement left `v3` as zero after replacing its local
`wrapContentHeight$default` sequence. `LandscapeMainContent` later reuses `v3`
as the default-argument mask for another `wrapContentHeight$default` call at
`0x3960BE`. With a zero mask, the null alignment argument was treated as an
explicit value, producing `NullPointerException` in
`Alignment.Vertical.align()` during initial layout. The corrected replacement
ends with `const/4 v3, 3`, preserving the later call's original semantics.

The supplied launch log directly confirmed this failure mode. The corrected
production patch again matched 20/20 regions with force fallback disabled, and
the straight-line regression test now asserts both the +16dp menu offset and
`v3 == 3` after the replacement block.
