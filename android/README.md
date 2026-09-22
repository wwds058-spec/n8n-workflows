# ECI NDX Scanner — Android app

A native Android wrapper around the ECI NDX Scanner web app (BLO Verification 2026 —
"Not Collected" checker). Point the camera at an NDX number, tap **SCAN**, and the app
tells you whether to **separate** the form or **keep it in the bundle**.

## Works fully offline

The original HTML pulled Tesseract.js from a CDN, so it needed a live internet
connection. This build bundles everything into the APK:

| Asset | Purpose |
| --- | --- |
| `app/src/main/assets/index.html` | the scanner UI |
| `app/src/main/assets/tess/tesseract.min.js` | OCR library |
| `app/src/main/assets/tess/worker.min.js` | OCR web worker |
| `app/src/main/assets/tess/tesseract-core-simd-lstm.wasm.js` | OCR engine (SIMD) |
| `app/src/main/assets/tess/tesseract-core-lstm.wasm.js` | OCR engine (fallback) |
| `app/src/main/assets/tess/eng.traineddata.gz` | English language model |

Nothing leaves the device and no network permission is declared — useful for field
work with no signal.

## How the shell works

`MainActivity` hosts a single `WebView` and serves `assets/` through
`WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/`. The HTTPS
origin matters: `getUserMedia()` (the camera) and web workers both refuse to run from
`file://`.

Camera access is a two-step handshake — the WebView asks via
`WebChromeClient.onPermissionRequest`, the app asks Android for the `CAMERA`
permission, and the answer is forwarded back. If the user declines, the page falls
back to its manual NDX entry field.

The screen is kept awake while the app is open, since scanning a bundle of forms means
long stretches without touching the display.

## Changes to the web app

* OCR now runs on a single long-lived Tesseract worker (created once, warmed up on
  load) instead of spinning up a new one per scan — the second and later scans are
  much faster.
* Character whitelist narrowed to `NDX0123456789` and page-segmentation mode set to
  "single text line", which suits the cropped scan strip.
* Scan history persists in `localStorage`, so it survives closing the app.

## Building

Requires JDK 17 and the Android SDK (compileSdk 34).

```bash
cd android
./gradlew assembleDebug      # or: gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and
install it (you will need "install unknown apps" enabled for your file manager).

CI builds it too: **Actions → Build ECI NDX Scanner APK**, then download the
`eci-ndx-scanner-debug` artifact. That is the easiest route if you do not have Android
Studio installed.

For a Play Store or long-lived build, sign a release: add a `signingConfigs` block to
`app/build.gradle.kts` and run `gradle assembleRelease`.

## Updating the NDX list

The 35 "Not Collected" numbers live in the `NOT_COLLECTED` set near the top of the
`<script>` block in `app/src/main/assets/index.html`. Edit that list, bump
`versionCode`/`versionName` in `app/build.gradle.kts`, and rebuild. Remember to update
the "35 NDX Numbers Loaded" badge and the list-toggle button text in the same file.

## Third-party licenses

Tesseract.js and tesseract.js-core are Apache-2.0. The English `traineddata` model is
Apache-2.0 (Google / tessdata).
