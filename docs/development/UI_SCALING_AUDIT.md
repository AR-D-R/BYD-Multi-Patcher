# BYD Spotify Manager v1.0 — UI notes

## Spotify 9.1 scaling

Spotify 9.1 uses a continuous 100-150% slider in 1% steps. New 9.1 slot settings default to 115%.

For Spotify 9.1.82, classic Android resource dimensions are scaled from the stock resource baseline. Home/Library is increasingly Compose-based, so every selected value above 100% also scales the Android-backed Compose display density; this covers hard-coded Compose tile/card geometry and text. The patched Compose font scale is normalised to 1.0 so the selected interface factor is applied once through density rather than twice through density × fontScale. At 100%, those scale patches are not applied.

The legacy 9.1.78 fixed-offset BYD dimension map is not reused on 9.1.82. The newer APKM profile keeps Spotify 9.1.82's stock container geometry and applies interface scaling through resources plus the Compose density hook described above.

## Queue shuffle icon

The Queue Shuffle/Smart Shuffle vectors retain their stock 24dp intrinsic size so the Shuffle action does not become larger than Repeat and Timer at enlarged interface scales.

## Player position

RHS keeps Spotify's normal large-screen placement. LHS uses the runtime outer-coordinate mirror hook while preserving each child's normal LTR rendering. This lets Spotify keep ownership of its expanded/collapsed state machine and animations while moving the player/navigation surfaces to the opposite side.

## Launcher badge

When hue or the + badge is enabled, the 9.1 adaptive foreground is generated as a raster drawable using the same badge rendering path as 8.9. This avoids launcher-specific clipping/placement differences on BYD systems.
