# Android TV, Google TV and Fire TV: notes for building a launcher

What we learned while building Aura 7.6.1. It comes from Android's open-source code and docs,
from reading the manifests of the stock launchers (Amazon's `com.amazon.tv.launcher`
7313002.1 and Google TV's `com.google.android.apps.tv.launcherx` 1.0.994451607 / 1.0.436578635),
from reading installed apps' manifests on a real TV over ADB (`dumpsys package`), and from
testing in a simulator.

The stock launchers were studied **for interoperability only**: which of their screens other apps
may open, and what's locked. None of their code or assets is used in Aura.

Each point says how sure we are: **confirmed** means read from the files or code and covered by a
test, or checked on a real TV; **untested** means found but not yet tried on a real TV.

---

## 1. Where apps' "Continue watching" and rows come from

| Source | What it is | Who can read it |
|---|---|---|
| **Watch Next** (`TvContract.WatchNextPrograms`) | Android's shared "continue watching" list | Any app holding `android.permission.READ_TV_LISTINGS` (Aura's setup grants it over ADB). Android 8+ |
| **Preview channels** (`TvContract.PreviewPrograms`) | The rows an app puts on the home screen | Same permission. Query **with no selection**: the TV provider refuses a `WHERE` from non-system apps and already limits rows to what you may read. Filter by `COLUMN_PACKAGE_NAME` yourself (confirmed) |
| **Google Engage** (`com.google.android.engage.*`) | Newer way for apps to send Google TV their continue watching, featured and recommended items. The app publishes on the device to the Play Store app's `AppEngageServiceV2` | Only Google's own consumers (Google TV's launcher). **No other launcher can reach it** (confirmed on a TV: the service lives in `com.android.vending`) |
| **Google TV's "For you"** | Partly Google's own servers and account, partly Engage data from the apps | Only Google's launcher |
| **Fire TV's home rows** | Amazon's "firecard" content provider | Locked behind `com.amazon.firecard.permission.CARD_CONSUMER`, which is signature-only. **Only Amazon-signed apps** (confirmed from the manifest) |
| **Media sessions** (`MediaSessionManager.getActiveSessions`) | What any app is playing right now: title, artist, artwork, position, length | Any app that's an approved **notification listener** (the "Now Playing" permission) |

### Who publishes what (checked on a Google TV, November 2026)

Read from each app's manifest with `dumpsys package`: whether it holds `WRITE_EPG_DATA`, which
`android.media.tv.action.*` broadcasts it receives, and whether it publishes through Engage.

| App | Android TV rows / Watch Next | Engage |
|---|---|---|
| Netflix, YouTube, Prime Video, Spotify | `INITIALIZE_PROGRAMS` receiver | - |
| ZEE5 (`com.graymatrix.did`) | `INITIALIZE_PROGRAMS` and the preview / Watch Next actions | - |
| JioHotstar | Watch Next only | - |
| **SonyLIV** (`com.sonyliv` 6.29.11) | `INITIALIZE_PROGRAMS` receiver (`.receiver.RunOnInstallReceiver`) and the preview / Watch Next actions | **Yes**: `PUBLISH_CONTINUATION`, `PUBLISH_FEATURED`, `PUBLISH_RECOMMENDATION` |

### Why SonyLIV (and apps like it) show nothing in previews

- SonyLIV sends its cards to Google TV through **Engage**, which only Google's launcher can read.
- It still carries the older Android TV row code, but only publishes those rows when asked with
  `INITIALIZE_PROGRAMS`. Google TV's home normally sends that request; with Google TV's home
  switched off, nothing does.
- **`INITIALIZE_PROGRAMS` can be sent over ADB** (`am broadcast -a
  android.media.tv.action.INITIALIZE_PROGRAMS -n com.sonyliv/.receiver.RunOnInstallReceiver`):
  SonyLIV accepted it and started up, but **still published no rows** (confirmed on a TV): it
  only feeds Engage now. Any app may send it (it isn't a protected broadcast), so Aura asks each
  app once when it's installed or updated, as the stock home does, for apps that do still answer it.

**What Aura does instead (a fallback, not a true fix):** while any app plays, its media session
reports the title, artwork, position and length. Aura keeps the last 4 days of programmes
(12 minutes or longer, not music or audiobook apps, at most 12 titles) and shows them as
*Continue watching* for that app. This works for apps that report playback through a media
session, which most video players do, and only for things watched **after** Aura is installed.
It's implemented in `Watched.java` and used by Top Shelf for apps with no rows (`ShelfTest`
checks this).

If an app publishes only through Engage and ignores `INITIALIZE_PROGRAMS`, the only route to real
rows is the app's own developers publishing to Android's Watch Next / preview channels.

---

## 2. Fire TV (Fire OS)

### Versions
- Fire OS 6 = Android 7.1 (API 25): Fire TV Stick 4K (1st gen), Fire TV Stick 2nd gen, Cube 1st gen.
- Fire OS 7 = Android 9 (API 28). Fire OS 8 = Android 11 (API 30).

### Home button
- On Fire TV, Home **always opens Amazon's launcher**. Choosing another home app isn't
  supported, so a third-party launcher needs an **accessibility service** that sees Home and opens
  itself instead (Aura's `HomeKeys`).
- **Android 9 and older (Fire OS 6/7) hold every app back for 5 seconds after Home.** This is the
  "app switch" guard in `ActivityManagerService.checkAppSwitchAllowedLocked`. Only system apps,
  recents and the voice assistant are exempt. Without a workaround you see Amazon's home for up
  to 5 s first.
- **Workaround (confirmed in Android's code):** an accessibility service can add a
  `TYPE_ACCESSIBILITY_OVERLAY` window straight away. Aura draws a "cover" (the blurred
  wallpaper and the time) until its home appears, at most 7 s (`HomeCover.java`). Android 10+
  doesn't have the 5-second hold.

### Settings
- `android.settings.SETTINGS` on Fire TV resolves to a stand-in,
  `com.amazon.tv.settings.v2/...compliance.CTSDummySettingsHandler`, which bounces through
  Amazon's home.
- **Amazon's real settings page is exported with no permission:**
  `com.amazon.tv.launcher/.ui.MainSettingsActivity` (action `amazon.intent.action.MAINSETTINGS`).
  Open it directly (**confirmed** from the manifest; `FireTvTest`).
- When opening any `android.settings.*` page, let Amazon's home pass for a moment, or your own
  Home redirect fights it.

### Other open entry points in Amazon's launcher (exported, untested on a real TV)
- Inputs: action `com.amazon.tv.action.NAVIGATE_TO_INPUTS` (Fire TV Edition TVs).
- Recents: action `com.amazon.tv.action.NAVIGATE_TO_RECENTS`.
- Most other screens (search results, detail pages, Prime pages, purchase dialogs, channel pages)
  need Amazon permissions such as `com.amazon.tv.launcher.permission.SHOW_SEARCH_RESULTS`,
  `...PRIME_VIDEOS` or `...SHOW_CHANNEL_LANDING_PAGE`, which only Amazon's apps hold.

### Remote
- The Menu (☰) button arrives as `KEYCODE_MENU`. Rewind/fast-forward and channel keys reach apps.
- Home isn't delivered to apps; see the accessibility route above.

### TV inputs
- **Fire TV Sticks have no TV input service.** `getSystemService(TV_INPUT_SERVICE)` can return a
  manager whose calls throw (a `NullPointerException` inside `getTvInputList`). In Aura 7.6 this
  closed the app at launch, on Control Center's warm-up and in Settings, which is what a Fire TV
  Stick 4K user reported. **Always wrap TV-input calls**, and treat a box without
  `PackageManager.FEATURE_LIVE_TV` as having no inputs (confirmed; `FireStickTest` runs a
  simulated Fire OS 6 stick through start, scrolling, every Settings page and Control Center).

### Now Playing (notification listener) setup
- `cmd notification allow_listener <component>` only exists from **Android 8.1**. On Fire OS 6
  (7.1) and Android 8.0 use the secure setting instead: append the component to
  `enabled_notification_listeners` with `settings put secure ...` over ADB (confirmed in Android's code).
- Fire TV has **no Watch Next list**, so a launcher's "Continue watching" there has to come from
  media sessions (section 1).

### Memory
- Fire TV Stick 4K has about 1.5 GB of RAM. Decode wallpapers at screen size (`inScaled`/`inDensity`),
  free them while a screensaver runs, and catch `OutOfMemoryError` on image decoding.

---

## 3. Google TV (`com.google.android.apps.tv.launcherx`)

- **The side panel** (Dashboard: settings, profiles, inputs) lives inside Google's launcher:
  `...launcherx.dashboard.DashboardActivity`, behind an alias that needs
  `android.permission.STATUS_BAR_SERVICE` (system only). Switching Google TV's home off
  removes the panel. Warn users about this.
- **The star/"magic" button setup** (Google TV Streamer remote) is also inside it:
  `...launcherx.magicbutton.ui.MagicAppSelectionActivity`, action
  `...launcherx.magicbutton.LAUNCH_CONFIG_UI`.
- **Gemini chat** answers action `android.apps.tv.launcherx.GEMINI_CHAT` with no permission, and
  the launcher also accepts `tv.google.com` links (news briefing, show pages). **Untested**, and
  likely to depend on the device, region and account. Only offer it where it actually resolves.
- Google TV's own "For you" and apps' Engage content (section 1) have no API for other launchers.
- With Google TV's home switched off, it no longer sends apps `INITIALIZE_PROGRAMS`, so apps
  that wait for it never publish their Android TV rows (section 1).
- **The Profile button on Realtek-based TVs** (Dixon/Acer and similar): the firmware's
  `com.realtek.keyintercept` service takes key 288 (`KEYCODE_PROFILE_SWITCH`) below Android's
  key handling and the accessibility filter, and opens Google TV's profile chooser by name. No
  app can catch the key first. Aura notices the chooser starting in the system log (`READ_LOGS`,
  granted at setup) and opens its own picker instead (confirmed on a TV).

---

## 4. Android TV in general (useful for any launcher)

- **Being the home app:** `cmd package set-home-activity <component>` (Android 7+, over ADB)
  works on plain Android TV. Google TV and Fire TV keep Home for their own launcher anyway, so an
  accessibility service is needed there.
- **Android 13+ blocks the Accessibility switch** for apps installed from a file ("restricted
  setting"). `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` over ADB lifts it.
- **Permissions worth granting over ADB** for a launcher: `READ_TV_LISTINGS` (rows, Watch Next),
  `WRITE_SECURE_SETTINGS` (screensaver and similar settings), `DUMP` (Wi-Fi name, HDMI/CEC device
  names), `PACKAGE_USAGE_STATS` via app-ops (recent apps, time in apps), `READ_LOGS`.
- **Wireless debugging (Android 11+):** a launcher can pair with the TV's own ADB over TLS and run
  commands such as `am force-stop` (a real "close app"). Never force-stop your own accessibility
  service's app on Android 11: Android marks the service as crashed until it's reinstalled or the
  TV restarts.
- **Android 9 and older:** the 5-second app-switch hold after Home (section 2).
- **Exit reasons (Android 11+):** `ActivityManager.getHistoricalProcessExitReasons` tells you how
  your last runs ended (crash, low memory, killed). Good for problem reports.
- **Trim memory:** `TRIM_MEMORY_UI_HIDDEN` is sent every time another app comes to the front;
  it does **not** mean memory is low. Free caches on `RUNNING_LOW`/`CRITICAL` or `BACKGROUND`
  and above, not on `UI_HIDDEN`, or every return home rebuilds everything.
- **Thread priority at boot:** `THREAD_PRIORITY_BACKGROUND` threads get very little CPU while the
  TV is starting. Load anything the user waits for (the app list) at default priority.
- **Shut executors:** an executor that's shut down throws when given work. Late callbacks
  reaching a destroyed activity crash unless pending handler work is cancelled in `onDestroy`
  (or the executor discards late work).
- **Logcat on TVs with chatty drivers:** some Wi-Fi drivers log errors many times a second, and
  `adb logcat` stops with "reader too slow". Silence the tag (`WifiVendorHal:S`) when recording.
- **Robolectric gotchas** (for tests): run in a non-touch mode, or TV tiles can't take focus;
  pause the Choreographer with a 16 ms frame delay or endless animations hang the test; on
  SDK < 26 the legacy `Matrix` shadow leaks native memory.

---

## 5. What's locked, in one list

Signature-only or system-only, so no third-party launcher can use them:
- Fire TV home rows (`com.amazon.firecard.permission.CARD_CONSUMER` / `CARD_PRODUCER`).
- Amazon launcher's search results, detail and Prime pages, purchase and channel pages.
- Alexa directives (`SEND_ALEXA_DIRECTIVE`, `SEND_DATA_TO_ALEXA`).
- Google TV's side panel (`STATUS_BAR_SERVICE`).
- Google TV's "For you" and apps' Engage content (served by the Play Store app to Google's launcher only).
- The Profile button on Realtek-based TVs (taken by the firmware; see section 3).

Open, and worth using:
- Amazon `MainSettingsActivity` (Fire TV settings).
- Amazon Inputs and Recents actions (untested).
- Google TV Gemini chat action and `tv.google.com` links (untested).
- Android's Watch Next and preview channels (with `READ_TV_LISTINGS`).
- Sending `INITIALIZE_PROGRAMS` to an app after it's installed or updated, so it publishes its rows (any app may send it; SonyLIV ignores it).
- Media sessions (with notification listener approval).
