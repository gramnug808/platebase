# PlateBase v1.0 — art.phoenix.platebase

Brand-new minimal ALPR. One job only: read plates, make a database (GPS + plate).

## What it does
- **Scan tab:** live Camera2 preview (back camera, 1280x720 YUV), live ML Kit OCR every ~1 s, big red ● REC + ticking timer while scanning, Stop button, torch, pinch zoom.
- **Plates tab:** searchable list, newest first, tap a plate to see its sightings (time + GPS). Delete one plate, clear all (both with confirm).
- **Test tab:** ML Kit status, GPS status (FRESH/STALE), DB counts, self-test on bundled ABC123 / 123ABC plates, last-crash display.
- **Export:** CSV (`plate,time,lat,lon,accuracy`) and JSON via SAF file picker. No import.
- **Database:** local SQLite `platebase.db`, table `sightings` (plate, seen_at, lat, lon, accuracy, gps_age_ms, gps_stale) + minimal `sessions` table for crash recovery. A plate is written the moment it is found; a repeat read of the same plate within 10 s is deduped. GPS: a plate is NEVER dropped for lack of GPS and a location is NEVER falsified — no fix means NULL lat/lon + gps_stale=1; a fix is fresh only if <120 s old and accuracy ≤200 m.

## What it does NOT do
No recording, no video import, no second pass, no map, no radio/WiFi/BLE/cell, no stingray, no grouping, no alerts, no network, no accounts. No Tesseract anywhere — ML Kit only, Test tab says so.

## Reused proven pieces (only these)
- `PlateLogic.kt` — PocketFlocker, as-is (Hawaii ABC123/123ABC, split-token merge, positional repair 8→B never C, vanity 4–8, EXIT/GATE/SPEED/MPH/year blocklist).
- `OcrPreprocess.kt` — PocketFlocker, as-is (Otsu, rotate, upscale2x, centerCrop). Binarize is now actually wired in: crop-first from the Y plane → rotate → Otsu binarize → 2x upscale → ML Kit. The full 1280x720 frame is never run through a per-pixel loop un-cropped.
- Camera2 pattern from PocketFlocker's CameraController — activity owns the preview surface, 2-surface session (preview + YUV), fallback to YUV alone with a status line.
- ML Kit closure + manual build pipeline from ALPR Signal v1.1 — ML Kit Text Recognition 16.0.1 bundled, explicit MlKitContext registrars, collection-jvm 1.3.0, javax.inject. No Gradle (blocked in this VM).

## Build
`./build.sh` (kotlinc + aapt2 + d8 + zipalign + apksigner). Gates: JVM unit tests, then v2 dex reachability audit on the final dex (fails on any reachable missing type).

## v1.0 build result (2026-11-20)
- APK: `~/workspace/your_files/platebase-v1.0.apk`, 14 MB
- sha256: `0e1a960f046028faa778c83195f3bcff7aa5ab6aae1e2858f9b48f822336f640`
- Tests: **54/54 pass** (PlateLogic, OcrPreprocess, Export, GPS freshness, PlateSaver dedupe)
- Dex audit v2: **7,353 defined / 5,921 reachable / 0 missing**
- apksigner verify: v2+v3 pass. No Tesseract in the APK.

## On-phone test (not yet run — live behaviour unverified)
1. Install, open Test tab — must say "ML Kit ready" + a GPS status line.
2. Tap "Run self-test" — expect `ABC123: OK; 123ABC: OK`.
3. Scan tab → Start scanning → point camera at a printed plate ABC123.
4. Plate appears in Plates list with a GPS lat/lon — or a STALE flag if indoors with no fresh fix.
