# Dynamic Island for notch / punch-hole Android phones

A Dynamic Island that grows out of your phone's notch or camera cutout. Plain Android Views, no third-party libraries.

## Features

- **Sits inside the notch**: it is drawn above the status bar (accessibility overlay), with flat top corners so it merges with the notch.
- **Notifications**: a peek with the app icon and title. Tap to expand into the full message with action buttons and a progress bar.
- **Media**: album art and a live equalizer while playing. Expand for title, artist, a seek bar with time, and prev / play-pause / next.
- **Calls**: incoming calls expand with round Decline and Answer buttons (they use the call notification's own actions).
- **Charging and low battery**, **download progress**.
- **Running activities become a dot**: music, downloads and charging shrink to a small coloured dot in the notch after 5 s. Tap the dot to expand it again. One-off messages shrink and hide in the notch.
- Spring animation, notch auto-detection, calibration sliders, haptics.

## Gestures

| Gesture | Action |
|---|---|
| Tap | Expand, or run the primary action when expanded |
| Tap the dot | Expand the running activity |
| Long press | Open the source app (or collapse when expanded) |
| Swipe up | Collapse or dismiss |
| Swipe left / right | Switch between media and notifications |

## Setup

1. Install the APK (Actions tab, latest **Build APK** run, artifact `DynamicIsland-debug`).
2. In the app grant:
   - **Accessibility service**: required. This is what lets the island draw above the status bar. It never reads your screen.
   - **Notification access**: notifications and media.
3. Turn on **Enable Dynamic Island**, then try the preview buttons (the Answer, Decline, Open and Later buttons all work).

> On Android 13+, sideloaded apps may have these switches greyed out. Open *Settings → Apps → Dynamic Island → ⋮ → Allow restricted settings*, then try again.

**Why accessibility?** A normal "display over other apps" overlay is drawn *under* the system status bar, so the bar covers the island and it cannot reach the notch. An accessibility overlay is drawn above it. The old overlay mode is still there as a fallback (needs "Display over other apps").

## Build

- **GitHub Actions**: every push builds a debug APK.
- **Android Studio**: open the folder, run `app`. JDK 17, AGP 8.5.
- **CLI** (Gradle 8.9+): `gradle assembleDebug`.

Requires Android 10 (API 29) or newer.

## Notes

- "Hide in fullscreen apps" is experimental and off by default.
- Call buttons use the phone app's call notification actions. Some dialers don't expose them.
- Notifications with inline reply show without the reply button.
- If the island sits a few pixels off, use the offset sliders in the app.

## Layout

```
app/src/main/java/com/crazestudio/dynamicisland/
  MainActivity.kt                  setup + settings
  IslandAccessibilityService.kt    preferred host (above the status bar)
  IslandService.kt                 fallback host (normal overlay)
  IslandController.kt              state machine: queue, media, call, charging, dot, gestures
  IslandView.kt                    the black shape: spring morph, flat top, gestures
  ContentFactory.kt                all layouts (compact, peek, expanded)
  PillBar.kt, EqualizerView.kt     small custom views
  MediaTracker.kt, IslandNotificationListener.kt, Notch.kt, Prefs.kt, Model.kt, Util.kt, BootReceiver.kt
```
