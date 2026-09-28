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

## Xposed API: libxposed 102 + legacy api:82 dual entry

The module ships two entry points so it is recognized by both modern and
legacy Xposed frameworks:

- **Modern:** `META-INF/xposed/java_init.list` → `MainHook` (libxposed API
  102, `io.github.libxposed:api:102.0.0`). Used by current LSPosed forks
  (e.g. Vector/JingMatrix). The deprecated `de.robv.android.xposed:api:82`
  interface is no longer the primary API.
- **Legacy fallback:** `assets/xposed_init` → `LegacyMainHook`
  (`de.robv.android.xposed:api:82`). Used by legacy-only frameworks that do
  not detect `java_init.list`.

Modern frameworks ignore the legacy entry when `java_init.list` is present,
and legacy frameworks never load the modern class, so both coexist in one
APK. All hook logic is shared in `HookCore`.

## Choosing the SAF handler

Rewritten requests are routed through the module's own `InterceptActivity`,
which lists every app that can handle the SAF intent plus "system default",
then forwards the request and returns the result to the original app.

This exists because OEM resolvers (e.g. ColorOS) send implicit
`OPEN_DOCUMENT` requests straight to their own file manager, whose resolver
skips the chooser dialog entirely - neither implicit resolution nor
`Intent.createChooser` lets the user choose.

SAF sources are limited to apps implementing a `DocumentsProvider`. Gallery
apps (ColorOS 相册, Google Photos, Immich) do not, so they cannot appear as
sources; the built-in "Images" root in DocumentsUI exposes all MediaStore
photos/videos. Third-party providers (Google Drive, CIFS/SFTP document
providers, file managers) appear automatically.

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

