# BYD Multi Patcher v2.0

BYD Multi Patcher modifies supported Spotify, Waze and A Better Routeplanner builds for BYD Android head units.

The project started as BYD Spotify Patcher. Version 2 expands it into one patcher for Spotify, Waze and ABRP.

## What changed in v2

- Added **Waze** patching for the BYD wide-screen layout.
- Added **ABRP** with direct BYD battery SOC and native BYD navigation TTS.
- Updated **Spotify 9.1** version.
- Project now uses APKM files instead of APK. APKMs are smaller and easier to find.
- Renamed the project from **BYD Spotify Patcher** to **BYD Multi Patcher**.

## Supported profiles

- **Spotify 9.1.82.2161**
- **Spotify 8.9.76.538**
- **Waze 5.24.0.1**
- **ABRP 7.1.7**

![app](docs/images/app.png)

## Spotify 9.1 options

Spotify 9.1 currently supports:

- BYD wide-screen layout fixes
- Interface scale: 100%-150%
- 115% default interface scale
- Player position: LHS or RHS
- Custom visible app name
- Launcher icon hue adjustment
- Optional `+` launcher badge
- Optional optimised performance profile
- v2 Improved playback restoration after vehicle restart

![Spotify 9.1 on BYD](docs/images/spotify-9.1.png)

## Spotify 8.9 options (recommended for slower BYD hardware)

Spotify 8.9 currently supports:

- Stock, Moderate or Large font size
- Left / LHD side panel
- Right / RHD side panel
- BYD landscape/orientation fix
- Custom visible app name
- Launcher icon hue adjustment
- Optional `+` launcher badge
- v2 Improved playback restoration after vehicle restart

![Spotify 8.9 on BYD](docs/images/08-app-list2.jpg)

## Waze

Waze is patched specifically for the wide BYD display rather than simply scaling the normal phone/tablet interface.

Main changes include:

- Centres the map and car position for the full-width display.
- Repositions the speedometer around the vehicle marker.
- Resizes the navigation instruction panel.
- Moves the top navigation controls down so they sit correctly on the BYD screen.
- Adjusts route-preview and active-navigation map positioning.
- Reduces the bottom drawer height to leave more map visible.
- Hides the Waze audio/music button by default.
- Applies EV defaults including CCS2.
- Optional microphone button removal.

![Waze 1 on BYD](docs/images/waze-map.png)

![Waze 2 on BYD](docs/images/waze-navigation.png)

## ABRP

ABRP 7.1.7 gets direct integration with the BYD head unit.

Main changes include:

- Centres the map and car position for the full-width display.
- Resizes the navigation instruction panel. Adjusts buttons so they are not blocked by BYD top and bottom panels
- Reads the BYD traction-battery SOC directly from the car — no separate link app or OBD adapter required.
- Uses the native BYD/Telenav **Cerence** TTS engine for navigation instructions.
- Uses the built-in English **Zoe** voice and selects it automatically on a fresh install.
- Defaults Appearance to **Automatic (Day/Night)** on a fresh install.
- Keeps live BYD battery data available to ABRP while driving.

![ABRP 1 on BYD](docs/images/abrp-home.png)

![ABRP 2 on BYD](docs/images/abrp-navigation.png)

## Building

Open the top-level project folder in Android Studio and build the `app` module normally.

## Installing patched apps

Patched apps are locally re-signed. The first patched installation normally requires uninstalling the official version of that app because Android will not allow an APK signed with a different certificate to replace it.

Later versions patched by the same BYD Multi Patcher installation can normally update the existing patched app because the same local signing identity is reused.

## Notes

The patches are tied to specific tested app versions. Using an unsupported version may fail to patch, fail to launch or behave incorrectly.

The existing patcher package ID and signing-key alias are intentionally retained from the original BYD Spotify Patcher for update compatibility.
