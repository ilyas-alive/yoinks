# Android scope and success criteria

This document defines what "Android support" means for yoinks in this repository.

## Supported devices

- Android 8.0+ (API 26+)
- ARM64 and ARMv7 phones
- x86/x86_64 may work on emulators but are not guaranteed

## UX goals

- Phone-first flow with native controls:
  1. Paste URL
  2. Fetch formats
  3. Select format
  4. Track progress
  5. See saved file path or a clear error
- Cancel button stops active probe/download
- If app is backgrounded, active transfers are cancelled to avoid orphan processes

## Download location

- Files are saved to app-specific external downloads directory:
  - `/storage/emulated/0/Android/data/com.yoinks.app/files/Download/yoinks/`
- This avoids extra storage permissions and works on modern Android versions.

## Network and storage protections

- Probe/download is blocked when there is no validated internet connection.
- Probe/download is blocked when free space is below a minimum threshold.

## yt-dlp / ffmpeg behavior

- The app installs the correct `yt-dlp` binary on first run based on device architecture.
- If `ffmpeg` is unavailable, the app falls back to phone-safe formats that do not require merging/conversion.
- Error messages are surfaced directly in-app.

## APK outputs

- Debug APK: always produced by CI workflow.
- Signed release APK: produced by CI only when signing secrets are configured.
