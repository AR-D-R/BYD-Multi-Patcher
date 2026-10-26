package com.bydspotifymanager.app;

final class BuildConfigData {
    static final String MANAGER_VERSION = "2.1";

    // Spotify 9.1.
    static final String SUPPORTED_VERSION = "9.1.82.2161";
    static final long SUPPORTED_VERSION_CODE = 146029335L;
    static final String STOCK_SHA256 = "4aecee00a134876fc30c44d79ef7ce3238d6cd97f856b618c9f5245c8fb3d560";
    static final String OFFICIAL_PACKAGE = "com.spotify.music";
    static final String PRIMARY_PACKAGE = "com.spotify.musia";
    static final String SECONDARY_PACKAGE = "com.spotify.musib";
    static final String APKMIRROR_URL = "https://www.apkmirror.com/apk/spotify-ab/spotify-music-podcasts/spotify-music-and-podcasts-9-1-82-2161-release/spotify-music-and-podcasts-9-1-82-2161-android-apk-download/";

    // Spotify 8.9 uses the same Primary/Secondary package slots as Spotify 9.1.
    static final String V89_SUPPORTED_VERSION = "8.9.76.538";
    static final long V89_SUPPORTED_VERSION_CODE = 119017142L;
    static final String V89_PRIMARY_PACKAGE = PRIMARY_PACKAGE;
    static final String V89_SECONDARY_PACKAGE = SECONDARY_PACKAGE;
    static final String V89_BASE_SHA256 = "fbaff920b24d8fc3bde463810651ac58509e00ad2faa0c3ed36a21b8947129c7";
    static final String V89_APKMIRROR_URL = "https://www.apkmirror.com/apk/spotify-ab/spotify-music-podcasts/spotify-music-and-podcasts-8-9-76-538-release/spotify-music-and-podcasts-8-9-76-538-android-apk-download/";

    // Waze 5.24.0.1 stable APKMirror Android 10+ bundle (arm64-v8a, 480-640dpi).
    static final String WAZE_SUPPORTED_VERSION = "5.24.0.1";
    static final long WAZE_SUPPORTED_VERSION_CODE = 1030729L;
    static final String WAZE_PACKAGE = "com.waze";
    // Exact reference hash: matching it marks the source TESTED; other valid Waze bundles use compatibility mode.
    static final String WAZE_BASE_SHA256 = "cc49acf7a9eb00d9a990e12c6d269c30dffd2780182481c8506fe5c46ebc6860";
    static final String WAZE_APKMIRROR_URL = "https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-24-0-1-release/waze-navigation-live-traffic-5-24-0-1-2-android-apk-download/";

    // Waze 5.9.5.4 legacy profile for slower BYD hardware.
    static final String WAZE59_SUPPORTED_VERSION = "5.9.5.4";
    static final long WAZE59_SUPPORTED_VERSION_CODE = 1030629L;
    static final String WAZE59_BASE_SHA256 = "98ba20d667948424f18b3dbefd78ee17160e97a1353812d0477dc95c0b4c4f51";
    static final String WAZE59_APKMIRROR_URL = "https://www.apkmirror.com/apk/waze/waze-gps-maps-traffic-alerts-live-navigation/waze-navigation-live-traffic-5-9-5-4-release/waze-navigation-live-traffic-5-9-5-4-android-apk-download/";

    // A Better Routeplanner 7.1.7 APKMirror universal 120-640dpi bundle.
    static final String ABRP_SUPPORTED_VERSION = "7.1.7";
    static final long ABRP_SUPPORTED_VERSION_CODE = 5980L;
    static final String ABRP_PACKAGE = "com.iternio.abrpapp";
    static final String ABRP_BASE_SHA256 = "01fb6a0da82ddcd904c2b96a984bd86426f0c34f975f7cc1bd348cded4be5450";
    static final String ABRP_APKMIRROR_URL = "https://www.apkmirror.com/apk/rivian-llc-2/a-better-routeplanner-abrp/a-better-routeplanner-abrp-7-1-7-release/a-better-routeplanner-abrp-7-1-7-android-apk-download/";

    // Keep the original alias so existing SpotifyPlus installs retain update compatibility.
    static final String KEY_ALIAS = "byd_spotify_manager_spotifyplus";
    private BuildConfigData() {}
}
