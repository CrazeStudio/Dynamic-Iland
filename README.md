# Dynamic Island for notch / punch-hole Android phones

A Dynamic Island that grows out of your phone's notch or camera cutout. Built with plain Android Views and no third-party libraries.

## Features

- **Notifications**: the island pops out with the app icon and title. Tap it to expand into the full message, action buttons and progress bar.
- **Media**: while music or video plays, the island shows album art on the left and a live equalizer on the right. Expand it for title, artist, a seek bar and prev / play-pause / next controls.
- **Calls**: incoming calls expand automatically with Decline and Answer buttons (they use the call notification's own actions).
- **Charging and low battery**: a peek with a bolt icon and battery percentage, expandable to a battery bar.
- **Progress notifications**: downloads and uploads show a live progress bar.
- **Spring animation** with a smooth morph between pill, peek and expanded card.
- **Notch detection**: width, height and position are read from the display cutout, with manual calibration sliders.
- Settings for auto-dismiss time, haptics, hide in landscape, optional always-visible pill, and more.
- Boots with the phone (when enabled).

## Gestures

| Gesture | Action |
|---|---|
| Tap | Expand, or run the primary action when expanded |
| Long press | Open the source app (or collapse when expanded) |
| Swipe up | Collapse or dismiss the current activity |
| Swipe left / right | Switch between media and notification activities |

## Setup

1. Install the APK (download it from the **Actions** tab, under the latest "Build APK" run, or build it yourself).
2. Open the app and grant the three requirements:
   - **Display over other apps**: draws the island.
   - **Notification access**: reads notifications and media sessions.
   - **Post notifications** (Android 13+): shows the small "service running" notification.
3. Turn on **Enable Dynamic Island** and tap the preview buttons to test.

> On Android 13+, a sideloaded APK may have **Notification access** greyed out. Open *Settings → Apps → Dynamic Island → ⋮ → Allow restricted settings*, then try again.

## Build

- **GitHub Actions**: every push builds a debug APK (workflow `Build APK`).
- **Android Studio**: open the folder and run the `app` configuration. Requires JDK 17 and Android Gradle Plugin 8.5.
- **CLI** (with Gradle 8.9+ installed): `gradle assembleDebug`.

## Requirements

Android 10 (API 29) or newer, with a notch or punch-hole display. Phones without a cutout also work, using a default pill size you can adjust in the app.

## Notes and limitations

- Android doesn't allow third-party apps to draw inside the system status bar. The island is an overlay window placed over the notch area.
- "Hide in fullscreen apps" is experimental and off by default. Behaviour depends on the device's system UI.
- The call buttons trigger the actions in the phone app's call notification. Some dialers don't expose them.
- Notifications that offer inline reply are shown without the reply button, because replying needs an input field.

## Project layout

```
app/src/main/java/com/crazestudio/dynamicisland/
  MainActivity.kt                  settings and setup screen
  IslandService.kt                 foreground service that owns the island
  IslandController.kt              state machine: queue, media, call, charging, gestures
  IslandView.kt                    the black pill: spring morph, clipping, gestures
  ContentFactory.kt                builds each layout (compact, peek, expanded)
  MediaTracker.kt                  active media session tracking
  IslandNotificationListener.kt    notification access
  Notch.kt                         cutout detection
  EqualizerView.kt, Prefs.kt, Model.kt, Util.kt, BootReceiver.kt
```
