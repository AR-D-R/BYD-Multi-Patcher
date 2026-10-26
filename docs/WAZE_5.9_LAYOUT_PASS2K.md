# Waze 5.9 layout pass 2k — horizontal safe area

This pass ports the successful Waze 5.24 landscape side-clearance principle to the exact tested Waze 5.9.5.4 base.

Waze already calculates navigation-bar/safe-content start and end padding. In landscape it also carries a 16dp fallback value, but the legacy build branches to 0dp whenever Android reports no *side* navigation-bar inset. BYD/BlueStacks uses a bottom navigation bar, so the side inset is zero and edge overlays sit flush against the display edges.

Pass 2k NOPs only those four zero-fallback branches:

- active navigation start: `classes5.dex @ 0x3F57E6`
- active navigation end: `classes5.dex @ 0x3F580E`
- general/pre-drive start: `classes5.dex @ 0x45DFE4`
- general/pre-drive end: `classes5.dex @ 0x45E07E`

The result is a 16dp minimum start/end clearance, with any real `safeContent` inset still added on top. At 240dpi this is approximately 24px per side.

This is intentionally a shared layout change, not individual X offsets for the hamburger, music/audio button, report button or drawer. The map remains edge-to-edge. Existing pass 2j speedometer, vehicle-selector and vertical-control tuning is unchanged.
