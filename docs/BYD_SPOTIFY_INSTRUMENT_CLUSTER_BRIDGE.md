# BYD Spotify instrument-cluster integration — v2.1

BYD Multi Patcher can mirror the current Spotify track to supported BYD instrument clusters for both Spotify 8.9.76.538 and Spotify 9.1.82.2161.

## Package model

The factory Spotify package remains `com.spotify.music` and is not removed or modified. Patched Spotify uses the existing clone slots:

- Primary: `com.spotify.musia`
- Secondary: `com.spotify.musib`

## How it works

The patcher injects a small foreground media relay into the selected Spotify clone. The relay connects to that clone's own MediaSession/MediaBrowserService and sends the same BYD instrument values recovered from the factory BYD Spotify integration:

- source: Spotify (`18`)
- track title
- playback state: playing / paused / stopped
- playback progress: 0-100%

The relay uses the BYD instrument API from inside the clone, so the stock Spotify package does not need to be replaced.

## User option

Enable **Show current Spotify track on instrument cluster** in either Spotify 8.9 or Spotify 9.1 before patching. The option works with both Primary and Secondary clone slots.

The temporary v2.1 test logger has been removed. The release helper does not write cluster diagnostic files or Logcat traces.
