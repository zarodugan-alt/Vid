# VidDownloader

Android video downloader built with Jetpack Compose: paste-a-link extractor, a
built-in browser with media sniffing, a background download manager, a gesture
video player and a media library.

Downloads from web pages (YouTube, TikTok, Instagram, X, Facebook, …) are
resolved by a **yt-dlp engine that runs on the device** — no third-party
extraction service is required.

## Build

```bash
# Debug APK
./gradlew :app:assembleDebug        # or: gradle :app:assembleDebug
```

Requirements:

| Tool   | Version                                                   |
| ------ | --------------------------------------------------------- |
| JDK    | 17                                                          |
| Gradle | 9.3.1 (minimum required by AGP 9.1.x, see `gradle/wrapper`) |
| AGP    | 9.1.1 (built-in Kotlin, no `kotlin-android` plugin)         |

CI (`.github/workflows/build-apk.yml`) builds the debug APK on every push and
uploads it as the `vid-debug-apk` artifact.

Signing keystores are intentionally not committed. `app/build.gradle.kts` only
registers a signing config when the keystore file exists, so a clean checkout
builds with AGP's auto-generated debug key. To sign a release build, provide:

```bash
export KEYSTORE_PATH=/path/to/my-upload-key.jks   # defaults to ./my-upload-key.jks
export STORE_PASSWORD=…
export KEY_PASSWORD=…
```

## Download engines

| Engine  | Used for                                             | Notes                                                    |
| ------- | ---------------------------------------------------- | -------------------------------------------------------- |
| `HTTP`  | direct media URLs (`.mp4`, `.mp3`, …) and sniffed media | OkHttp with `Range` resume                                |
| `YTDLP` | web pages, HLS/DASH manifests (`.m3u8`, `.mpd`)      | bundled python + yt-dlp, ffmpeg muxing, resumable, cancellable |

### The yt-dlp engine

* Provided by [`youtubedl-android`](https://github.com/yausername/youtubedl-android)
  (`io.github.junkfood02.youtubedl-android`), which ships a python runtime, the
  yt-dlp script and ffmpeg as native libraries.
* `YtDlpEngine` unpacks it on first launch (`ensureReady`), exposes a status
  `StateFlow` for the UI, extracts metadata with `--dump-json`, downloads with
  live progress/ETA, cancels via `destroyProcessById`, and can self-update the
  yt-dlp binary from the stable channel (Settings → Download Engine).
* Quality options map onto yt-dlp format selectors, e.g.
  `bestvideo[height<=1080]+bestaudio/best[height<=1080]/best`; separate video and
  audio streams are merged with ffmpeg, so 1080p and above actually work.
* Audio-only options use `-x --audio-format m4a|mp3`.
* Native binaries only ship for `arm64-v8a` and `armeabi-v7a`
  (`defaultConfig.ndk.abiFilters`) and require `useLegacyPackaging = true` so the
  `.so` payloads are extracted and executable at runtime.

If the engine cannot start on a device (unsupported ABI, unpack failure), the app
falls back to an optional yt-dlp HTTP service when `YTDLP_API_URL` was set at
build time, and otherwise reports the failure instead of pretending to download.

## Downloads location

Files land in the app-specific external directory
(`Android/data/<package>/files/Download`), so no storage permission is needed.

## Licensing note

`youtubedl-android`, yt-dlp and ffmpeg are GPL-licensed. Bundling them makes the
distributed application subject to the GPL; keep that in mind before publishing.
