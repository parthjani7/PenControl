# PenScroll

Hands-free scrolling and tapping in any app, controlled by the Galaxy S Pen button.

PenScroll is an Android accessibility service. It turns S Pen button clicks into slow, steady
scrolling, page turns, or recorded tap sequences ("macros"). Use it to read long articles, follow
sheet music or recipes, or page through an e-book with the pen in your hand.

## Features

- **Pen remote.** Long-press the pen button to turn it on, then click with the pen away from the
  screen. You choose what click, double-click and triple-click do:
  - start or stop auto-scroll
  - swipe one page
  - reverse direction
  - jump to the top or bottom
  - go faster or slower
- **Scroll styles.** You can scroll in three ways:
  - continuous smooth scrolling at 20–1500 px/s
  - one no-fling swipe every N seconds
  - a tap on the screen edge every N seconds, for e-readers
- **App macros.** Record taps, long-presses, swipes, Back and Home for one app.
  - **Playback:** each click runs the next step, or one click plays the whole macro.
  - **Waits:** each step waits as long as you did while recording. You can edit every wait.
- **Floating bar.** It shows the direction, speed or macro step, and has controls for speed,
  reverse, jump, record and stop. You can drag it out of the way.
- **Quick Settings tile** to start and stop scrolling.
- Scrolling stops when you touch the screen, switch apps or turn the screen off.

## Requirements

- A Samsung Galaxy phone with a Bluetooth S Pen that supports Air actions (e.g. S23 Ultra).
- Android 11 (API 30) or newer.

## Build

You need JDK 17–24 (Gradle 8.14 doesn't run on JDK 25) and the Android SDK with platform 36.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat`. If Android Studio's bundled JDK is too new, set `JAVA_HOME` to an
older JDK first.

On Samsung phones with Dual Messenger or Secure Folder, add `--user 0` to the install command. The
copy in the other profile has its own accessibility switch, and Air actions may pick it.

## Phone setup

1. **Turn on Accessibility.** Open PenScroll, tap **Open Accessibility settings**, then go to
   Installed apps → PenScroll → On.
   - Android blocks this at first for apps installed outside the Play Store. Go to App info → ⋮ →
     *Allow restricted settings*, then try again.
2. **Turn on Air actions.** Go to Settings → Advanced features → S Pen → **Air actions** → On.
3. **Pick PenScroll for long-press.** Set **Hold down Pen button to** to **PenScroll**.
4. **Give Air command notification access.** Go to Settings → Notifications → **Notification
   access** and allow **Air command**. Pen remote needs this.
5. **Allow PenScroll's notifications** when it asks.

Apps with their own Air actions, such as Camera, keep pen clicks for themselves. Turn those off in
the Air actions screen if you want PenScroll to get the clicks in that app.

Some antivirus apps warn about sideloaded apps that use Accessibility. PenScroll doesn't read
screen content and needs no internet access.

## How it works

### Long-press

Samsung only lets third-party apps use the pen's long-press, which launches an app.

- PenScroll's launcher icon opens an invisible activity.
- If the launch comes from the home screen, the activity opens settings.
- From anywhere else, the launch counts as a pen long-press.

### Clicks

Single and double clicks reach PenScroll as media buttons:

1. While Pen remote is on, PenScroll holds a `MediaSession` in the playing state and posts a media
   notification.
2. Air actions sends remote clicks to the app that owns the active media notification. It sends
   play/pause for a click and "next" for a double-click.
3. PenScroll plays half a second of silence so that it becomes the phone's media-button target
   ahead of any music app.
4. There is no native triple-click. A click that comes soon after a double-click counts as one.

While Pen remote is on, your music app doesn't get pen clicks. Play music again to hand them back.

### Scrolling

`PenScrollService` injects one long finger drag made of 100 ms `continueStroke` segments, so speed
changes and stops take effect within about 100 ms. When the finger runs out of room, it holds
still before lifting, so the app doesn't fling, then grabs again at the other end.

### Macros

Macros are stored per app as JSON. Positions are saved as fractions of the screen size.

- **Recording:** a full-screen overlay captures each touch. It then lets the touch through to the
  app, so you can move through the app's screens while recording.
- **Swipes:** a swipe keeps its path and how long the finger rested before and after moving.
  Sliders and drag handles often need that rest.

## Privacy

PenScroll has no internet permission and collects nothing. The accessibility service only listens
for window changes, to learn which app is in front. It cannot read window content
(`canRetrieveWindowContent="false"`). Settings and macros stay in the app's private storage. Android's own device backup can include
them.

## Disclaimer

PenScroll was built with the help of Claude Opus 5.5, Anthropic's AI model. It is a personal
project and is provided as is, with no guarantee or warranty of any kind. It injects touches into
other apps, so use it at your own risk, especially in apps where an unintended tap matters. It is
not affiliated with or endorsed by Samsung.

## License

[MIT](LICENSE)
