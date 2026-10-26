# Waze 5.9.5.4 widescreen layout pass 1

This pass targets the exact supported Waze 5.9.5.4 APKM profile.

Changes in this pass:

- Free-drive map geometry: derive `carIndicationToBottom` from live map height (`height / 5`).
- Free-drive map geometry: remove the legacy horizontal left margin.
- Free-drive / pre-drive speedometer only: `TopStart` -> `TopCenter`.
- Free-drive / pre-drive top-left overlay: +16 dp vertical offset.
- Free-drive / pre-drive top-right ButtonPack: +16 dp vertical offset, density-aware.
- StartState collapsed bottom-sheet anchors: 80 dp -> 60 dp.
- Existing Waze 5.9 side-panel, report-menu and landscape-lock patches are retained.

Intentionally not changed:

- MidDrive/navigation speedometer. It was visually centred during testing.
- MidDrive/navigation instruction card and top-right controls. Their 5.9 code paths are separate and are not changed in this pass.
- Navigation Stop/Cancel control. It may still be obscured by the BYD-height system bar; test after the drawer/inset change before adding a separate MidDrive patch.

Binary validation performed against the exact stock Waze 5.9.5.4 `classes5.dex` and `classes6.dex`: all patch signatures matched at their expected offsets, replacements are same-length, DEX SHA-1/Adler32 were regenerated on test copies, and both patched DEX files parsed structurally.
