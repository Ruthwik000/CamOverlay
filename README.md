# CamOverlay

Floating camera controls for rooted Android: adjust zoom, exposure, focus and white balance of the
camera session that **another app** (a video call) already owns, through an Xposed-style hook.

**Status: Phase 0 to 2 scaffold. Nothing here has been run on a device yet.** The goal of this
stage is to prove one thing in a test app we control: a hook can change the physical camera's zoom
without opening a second camera session, and undo it cleanly.

## Modules

| Module | What it is |
| --- | --- |
| `:core` | Shared models, AIDL contracts, capability reading and value clamping. No UI, no Xposed. |
| `:hook` | The LibXposed module. Runs inside the target app and rewrites capture requests. |
| `:app` | The CamOverlay APK (`com.camoverlay`). Contains `:hook`. Placeholder screen for now. |
| `:camera-lab` | CameraLab (`com.camoverlay.lab`), a plain Camera2 app used as the hook's test target. |

## Requirements

- Android Studio (recent stable) with SDK Platform 36 installed.
- A rooted phone on Android 11+ with Magisk or KernelSU, Zygisk enabled, and
  [Vector](https://github.com/JingMatrix/Vector) 2.2+ (LibXposed API 102).

## Build and install

```
gradlew.bat :app:installDebug :camera-lab:installDebug      (Windows)
./gradlew :app:installDebug :camera-lab:installDebug        (macOS / Linux)
```

Or open the folder in Android Studio and run the `app` and `camera-lab` configurations.

## Try the hook

1. In Vector, enable **CamOverlay** and tick **CameraLab** in its scope.
2. Force stop CameraLab and open it again.
3. `adb logcat -s CamOverlay` should show `loaded in com.camoverlay.lab` and `hooking ... submitCaptureRequest`.
4. Override zoom, then clear it:

```
adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.SET --es type ZOOM --ef value 2.0
adb shell am broadcast -p com.camoverlay.lab -a com.camoverlay.debug.CLEAR
```

CameraLab's readout shows `zoom app 1.0 camera 2.0` in orange while the override is active, and
returns to white after CLEAR. Other types: `EXPOSURE` (steps), `WHITE_BALANCE` and `FOCUS`
(Camera2 mode constants), `AE_LOCK` (0 or 1).

To confirm no second camera session exists: `adb shell dumpsys media.camera` should list CameraLab
as the only client.

## How the override works

`CameraDeviceImpl.submitCaptureRequest` is the private framework method every Camera2 submission
passes through. The hook copies each `CaptureRequest`, writes only the overridden keys into the
copy, and lets the call continue with the copy. The app's own request is never modified, so
restoring is: clear the override map and replay the app's last repeating request.

If anything in the hook throws, it disables itself for that process and the app's calls pass
through unchanged.

## Known unknowns (to check on the first device run)

- The hook reads three framework internals by reflection (see `RequestRewriter.kt`). If Android's
  hidden-API restrictions block them, the log says `request rewriting unavailable` and the hook
  stays in observe-only mode. The fix would be adding `org.lsposed.hiddenapibypass`.
- While an override is active, the app's capture callbacks receive the rewritten request object
  instead of its own. CameraLab ignores it; real apps may not.
- `submitCaptureRequest`'s signature can differ between Android versions. The hook matches by name
  and logs what it found.
