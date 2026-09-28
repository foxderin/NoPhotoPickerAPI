# NoPhotoPickerAPI (Android 16 fork)

[![GitHub license](https://img.shields.io/github/license/yureitzk/NoPhotoPickerAPI)](https://github.com/yureitzk/NoPhotoPickerAPI/blob/main/LICENSE)

> Fork of [yureitzk/NoPhotoPickerAPI](https://github.com/yureitzk/NoPhotoPickerAPI)
> with Android 16 (SDK 36) compatibility.

This is an Xposed module. The function of this module is to "bypass" the
limitations of the new
[Photo picker API](https://developer.android.com/training/data-storage/shared/photo-picker)
and allow users to the classic document/file picker.

> [!IMPORTANT]
> Tested on a limited number of devices. Your experience may vary, and bugs are possible.

## Android 16 changes (this fork)

Android 16 may redirect image/video `ACTION_GET_CONTENT` requests back to the
system Photo Picker. This fork uses `ACTION_OPEN_DOCUMENT` (SAF / DocumentsUI)
instead, so the request resolves to the classic file picker or any installed
SAF-capable file manager / DocumentsProvider.

Additionally, Photo Picker intents are now matched by action regardless of
SDK version, covering:

- `MediaStore.ACTION_PICK_IMAGES`
- `androidx.activity.result.contract.action.PICK_IMAGES`
  (AndroidX `PickVisualMedia` system-fallback picker)
- `com.google.android.gms.provider.action.PICK_IMAGES`
  (Google Play services photo picker backport)

## Xposed API: libxposed 102

The legacy `de.robv.android.xposed:api:82` interface is deprecated. This fork
targets the modern libxposed API (`io.github.libxposed:api:102.0.0`):

- Entry point moved from `assets/xposed_init` to
  `META-INF/xposed/java_init.list`.
- Requires an LSPosed implementation with libxposed API 102 support
  (current actively maintained LSPosed forks, e.g. Vector/JingMatrix).
  Legacy-only Xposed frameworks cannot load this module.

## Screenshots

<details>
<summary>View Screenshots</summary>

### Without the module
<img src='img/before.png' alt='Before' width='300'>

### With the module
<img src='img/after.png' alt='After' width='300'>

</details>

## Supported OSes

- Android 11-16
- Android 16 support added, device testing recommended.
- Custom ROMs are **not** supported by upstream; ColorOS/OxygenOS 16 untested.

## Usage

1. Enable the module in LSPosed.
2. Recommended scope rollout on Android 16:
   - **Phase 1:** enable the module for a **single target app only** (per-app scope).
   - Verify: single image pick, multi image pick, video pick, `image/*`,
     `video/*`, mixed MIME, `ActivityResultContracts.PickVisualMedia`,
     and legacy `startActivityForResult`.
   - **Phase 2:** only after per-app hooks work, enable **System Framework**
     (reboot required).
3. When an app calls the Photo Picker, SAF / DocumentsUI should appear instead.

## Build

```bash
./gradlew assembleDebug        # installable debug APK
./gradlew assembleRelease      # release APK (debug-signed if no keystore env vars)
```

CI (GitHub Actions) builds both variants on every push and uploads them as
workflow artifacts (`NoPhotoPicker-debug-*` / `NoPhotoPicker-release-*`).

## Important notes

- This module bypasses Android's Photo Picker privacy model.
- Apps may gain broader access to media files than intended by the system.
- Use at your own risk.

