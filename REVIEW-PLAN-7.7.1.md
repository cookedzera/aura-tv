# Aura 7.7.1 — full code review, fix plan and roadmap

Working document for whoever picks this up next (human or agent). Every finding below was read
from the source at commit `481dd99` (branch `fix/home-loop-firetv`) and spot-verified; nothing is
a guess. Fix items carry a status box: `[ ]` open · `[x]` fixed and built · `[~]` partly done.

How this document was made: all markdown files and `HomeActivity.java` were read end to end, then
the remaining ~37k lines were reviewed in five clusters (servers/content/rendering, Settings/
Welcome/data, Control Center, Home button/ADB/Safety, media/screensaver). Reddit feedback from the
7.7 release thread is folded in. Research on AOSP, other launchers, tvOS and Chinese TV launchers
is being gathered for the roadmap at the end.

---

## 0. Ground rules (do not skip)

- Plain Java 8, no AndroidX, no Gradle. minSdk 22 (Android 5.1 / Fire OS 5), targetSdk 28.
  `java.util.function`, streams, `String.join`, `Objects`, `Map.getOrDefault` are **not** available
  on API 22/23: use small interfaces and loops. Guard every API > 22 call with `Build.VERSION.SDK_INT`.
- Build: `.\tools\build.ps1` → `launcher\Aura.apk`. A clean `javac` is the type check; there is no lint.
- Tests: `$env:Path = "$env:USERPROFILE\.aura-tools\apache-maven-3.9.9\bin;$env:Path"; .\tools\test.ps1 -NoBuild`
  (Robolectric, Android 7.1 and 11, 28 test classes). After changing string resources delete
  `tests\target\classes` and `tests\target\test-classes` first.
- Every user-facing string lives in `values`, `values-de`, `values-es`, `values-pt` (972 keys each today).
- No AI attribution anywhere: code, comments, commits, docs. Comments are plain, warm English saying *why*.
- `main` is public stable (7.7). `dev` is private (phone companion): never push `dev`, never merge it
  into `main`, never bring companion/Link code or mentions into `main`.
- Never force-push, never delete user data, never switch someone else's checkout. Work on a branch.
- Owner's instruction for this round: **do not `git commit` or `git push` until the owner has
  verified the changes.** Leave work in the tree.
- TV testing: owner's TV is `192.168.29.12:5555` (Realtek rtd2841a, Android 11, 1.5 GB). adb at
  `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`. Installs need a versionCode above the
  TV's current build. Never drive the TV's UI with adb key presses, never force-stop Aura on it,
  never uninstall/disable anything on a user's TV. Emulators: `AuraTV22`, `AuraTV`, `AuraTV34`.
- `reapk/` holds decompiled apps for learning behaviour only. Never copy code from it.

## 1. Baseline at the start of this round

- `.\tools\build.ps1` → `Built launcher\Aura.apk (1,160 KB)`, exit 0.
- `.\tools\test.ps1 -NoBuild` → `All tests passed`, exit 0.
- Strings: 954 `<string>` in each of the four locales (972 keys counting plurals), none missing.
- `git`: HEAD `481dd99`, 4 commits ahead of `origin/main`, untracked: `CLAUDE.md`, 7.6.1 notes,
  `release-7.7/`, `tools/make_reddit_video.py`, this file.

---

## 2. Fix plan — ordered batches

Severity: **Critical** = TV left without a working home, data loss, or a crash on supported
devices · **Major** = user-visible breakage, leak, destructive mis-action or sustained jank ·
**Minor** = edge case, cosmetic, efficiency.

Confidence as written by the reviewer: confirmed (read in code and platform behaviour is certain),
likely, suspect.

### Batch A — a TV with no home, destructive single presses, crashes (do first)

| # | Sev | Where | What happens | Fix | Status |
|---|---|---|---|---|---|
| H1 | Critical, confirmed | `Safety.java:79-80` (`check()`), `:277-285` (`originalDisabled()`), `:291-305` (`original()`) | `original()` queries HOME activities with `GET_DISABLED_COMPONENTS` only. A launcher retired with `pm uninstall -k --user 0` (the fallback in `retireTvHome`, `:263-267`) is invisible. So stage‑2 crash recovery returns before `restoreHome()` even though `restoreHome()` knows how to `pm install-existing` the `RETIRED` package; and `removeAura()`'s "connect debugging first" guard is skipped. Android itself clears a crashing non-system home's preferred activity, leaving nothing to fall back to. | In `check()` stage 2 run `restoreHome` when `original() != null` **or** `RETIRED` pref is set **or** `Debloat.MINE` is non-empty. In `originalDisabled()` return true for the same conditions and treat `NameNotFound` / `FLAG_INSTALLED == 0` as disabled. In `original()` add `GET_UNINSTALLED_PACKAGES` (same value as `MATCH_UNINSTALLED_PACKAGES`). | [x] |
| H2 | Major, likely | `Safety.java:70-83` | Stage 2 hands Home back but never calls `HomeKeys.setTakeover(c,false)`; if `HomeKeys.set(c,false)` failed (no `WRITE_SECURE_SETTINGS`) the service keeps `bounce()`-ing a crashing Aura over the restored launcher. | Call `HomeKeys.setTakeover(app,false)` before `restoreHome`; if `HomeKeys.set` returned false, append `settings put secure enabled_accessibility_services ''` (minus Aura) to the `restoreHome` commands. | [x] |
| H3 | Major, confirmed | `Safety.java:176-178, 195` (ARGON N3) | `pm disable --user 0 pkg/Component` on setupwraith throws on Android 9+ (shell can't change component state); the error text contains the package name so line 195 filters it out and `ok` stays true. `restoreHome()` only re-enables whole apps and never reverses it anyway. | Drop component-level disable/enable for setupwraith; use `pm disable-user --user 0` on the whole package only where the owner accepts it, else don't touch it. Stop filtering setupwraith errors. Persist what was disabled so `restoreHome` reverses exactly that. | [x] |
| H4 | Major, likely | `Safety.java:29-46` (`watch()`) | Every uncaught exception in the process is counted: screensaver (`Ambient`), `MediaWatch`, `DailyJob`. Three screensaver OOMs in 5 min switch the Home button off; three more hand Home back. User never saw the home screen fail. | Record which component crashed (stack contains `HomeActivity`/`HomeKeys` vs `Ambient` etc.). Only home-screen crashes count toward the home thresholds; screensaver crashes switch the aerial/photo scene to plain photos instead. | [x] |
| D2 | Major, confirmed | `SettingsPanel.java` `confirmRemove` (1497/1502/1507), `confirmProfile` (614-621), `confirmAdbOff` (1972-1977), `confirmSignOut` (1151-1156), `confirmKey` (1555-1559); `open()` 221-238, `close()` 275-291, `go()` 388 | "Press OK again" armed states survive closing Settings. Arm Remove Aura once, leave, come back hours later: one OK uninstalls. Same for Remove profile, ADB off, TMDB sign-out, remote-button removal. | Reset every confirm field in `open()` and in `go()`: `confirmRemove = confirmAdbOff = confirmSignOut = confirmClear = confirmClearTrouble = confirmCatDelete = false; confirmProfile = confirmPhoto = confirmKey = dbConfirm = null;` | [x] |
| D1 | Major, likely | `SettingsPanel.java:839` (`rows.addView(dropCard, cl)`), 2250 (`stopDropIdle = this::stopDropNow`), 2306-2310 (`onType` re-arms idle timer), 2353-2358 (`stopDropNow` nulls `dropCard`) | Typing from the phone while on the "From your phone" page arms the 10‑minute idle timer; when it fires, `dropCard` becomes null while `page` is still `drop`/`photos`; the next `build()` does `addView(null)` → NPE → launcher crash. | In `stopDropNow()` don't tear down while `isOpen() && (page is drop or photos)`: re-post the timer instead. Also guard `build()`: `if (dropCard == null) dropCard = dropCard(drop != null ? drop.url() : null)`. | [x] |
| S3 | Major, likely | `Card.java:126` (`Bitmap.createBitmap` in `onSizeChanged`), `:171` (`reflection()`) | Main-thread allocation with no `OutOfMemoryError` guard; it's the first allocation to fail under S1/S2 pressure → process crash → home restart loop on Home press. Other painters catch OOM; Card doesn't. | Wrap both in `try/catch (OutOfMemoryError)`: set `bitmap = null`, call `Continue.trim()` and `Tiles.trim()`; in `onDraw` fall back to `painter.paint(c, w, h)` when `bitmap == null`. | [x] |
| M4 | Major, suspect | `AerialView.java:205-242` (`surfaceDestroyed` → `releasePlayer`) | `setSurface(null)` and `release()` both go to the background executor; `surfaceDestroyed` returns with the decoder still bound to a vanishing surface. Known native-crash vector on MediaTek/Realtek/Amlogic players (Android 7–9). | `m.setSurface(null)` synchronously on main (cheap), only `m.release()` on the executor. Wrap `onPrepared` body in `try/catch (RuntimeException)` (`setVideoScalingMode` can throw on vendor players). | [x] |
| H5 | Minor→Major on Android 11+, confirmed | `HomeKeys.java:504-511, 628-637` | `onKeyEvent`/`onAccessibilityEvent` catch `RuntimeException` only. An `OutOfMemoryError` or vendor `LinkageError` kills the process; on Android 11+ the service lands on the crashed list and Home is dead until the user opens Aura by hand. | `catch (Throwable t) { Trouble.caught("keys", t); return false; }` (no rethrow). | [x] |

### Batch B — focus traps, wrong screens, logic bugs

| # | Sev | Where | What happens | Fix | Status |
|---|---|---|---|---|---|
| C1 | Critical, confirmed | `ControlCenter.java:1020-1022` (ARGON N1) | `handleKey` checks `wifiSheet`/`btSheet` but not `inputSheet`. The focused Inputs row isn't in `focusables`, so `wifi.requestFocus()` succeeds on the **INVISIBLE** board (`requestFocus` doesn't check ancestor visibility) and the key is swallowed. On TVs with ≥2 inputs only the first can ever be chosen; OK then opens Wi‑Fi over Inputs. | `if (wifiSheet.isOpen() || btSheet.isOpen() || inputSheet.isOpen()) return false;` | [x] |
| C2 | Major, confirmed | `ControlCenter.java:941-954` (`close()`), `top()` layout array `{board, wifiSheet, btSheet}`, `hideInputs()` at 1332 | `close()` never closes `inputSheet`; after picking an input once, every later Control Center opens with the stale Inputs sheet drawn over the board and focus on the hidden Wi‑Fi button. In tvOS style the sheet is mis-positioned (`top()` skips it). | In `close()`: `if (inputSheet.isOpen()) hideInputs(false);` with `hideInputs(boolean focusToggle)`. Add `inputSheet` to the `top()` array. | [x] |
| C3 | Minor, confirmed | `ControlCenter.java:130, 710, 791, 1044` | Four places `wifi.requestFocus()` on focus loss even while a sheet is open → focus on the invisible board. | One `focusBoard()` that sends focus to whichever surface is visible (board, inputs, bt, wifi); call it from all four. | [x] |
| C7 | Major, confirmed | `WifiSheet.java:227-229, 524-525, 537` | `close()` cancels the speed test but never nulls `speed`; `done` never comes for a cancelled run; `runSpeed()` bails on `speed != null`. One mid-test close kills speed test until restart; "Measuring…" text stays. | In `close()`: `if (speed != null) { speed.cancel(); speed = null; speedText = ""; }` | [x] |
| C4 | Minor, likely | `WeatherSheet.java:130-150`, `HomeActivity.java:1975-1977` | `isOpen()` is true during the 200 ms close fade; `openWeather()`'s 300 ms post then sets `behindCc()` views INVISIBLE with nobody restoring them → black screen after a quick Back. | `closing` flag: `isOpen()` = `VISIBLE && !closing`; set in `close()`, cleared at end and in `open()` after `animate().cancel()`. | [x] |
| C8 | Minor, likely | `WifiSheet.java:206-209`, `HomeActivity.java:543-546` | After granting location (API 23+) `onRequestPermissionsResult(7)` only calls `cc.refresh()`; the list stays empty until the sheet is reopened. | `WifiSheet.granted()` → rescan + rebuild; forward from `ControlCenter.wifiGranted()` in the code‑7 branch. | [x] |
| C9 | Minor, confirmed | `ControlCenter.java:1082, 1097-1098` | `Compat.onWifi(cm, true)` includes Ethernet → wired boxes light the Wi‑Fi button blue with the radio off. | Button state from `onWifi(cm,false)`; caption "Ethernet" when wired. Needs 4-locale string. | [x] |
| C11 | Minor, confirmed | `ControlCenter.java:987-994`, `BtSheet.java:338-339` | No Bluetooth adapter → dead "Off" sheet, toggle does nothing. | `showBt()`: if `BluetoothAdapter.getDefaultAdapter()==null` hide the button (GONE) or route to system settings like Wi‑Fi's `present()`. | [x] |
| A4 | Minor, confirmed | `HomeActivity.focusSide()` / `focusWidget()` (ARGON N4); `CategoryRowsNavTest.java:147, 220` skipped | Neither sets `zone` (`SIDE` / `WIDGETS`), so in touch mode focus can't move; two tests `assumeTrue`-skipped around it. | Set `zone = SIDE` and `zone = contentZone = WIDGETS` in those methods; remove the two `Assume`s and make the tests assert. | [x] |
| A5 | Minor, confirmed | `HomeActivity.onBackPressed()` `TOP/SHELF/dock&&WIDGETS` branch (ARGON N5) | Still `focusGrid(gridAt % COLS)` which is wrong in category rows mode. | Use the row-aware focus (`rowIdx/colIdx`) when rows are on. | [x] |
| A6 | Minor, confirmed | `onNewIntent` `fromSaverSettings` / `OPEN_NOTIFICATION_HANDLER_PANEL` paths (ARGON N5) | Not kids-guarded. | Route both through `guarded(...)`. | [x] |
| A7 | Minor, confirmed | `HomeKeys.launch()`, `HomeActivity.openFromSwitcher()` (ARGON N5) | `Categories.launched()` not called → "Most used" rows miss these launches. | Call `Categories.launched(c, pkg)` on both paths. | [x] |
| D7 | Minor, confirmed | `SettingsPanel.java:711` → `HomeActivity.applySetting` | `host.applied("moments")` falls to `default`; a moment already on screen stays after turning them off. | Add `case "moments":` that cancels a running sky moment. | [x] |
| D8 | Minor, confirmed | `Welcome.java:236` | Dot indicator: page order 0→8→10→1→2→3; p=10 lights the 5th dot. | `p == 8 \|\| p == 10 ? 1 : …` | [x] |
| D16 | Minor, confirmed | `Welcome.java:268, 176` | Back from "You're all set" after "Keep the defaults" lands on a page never shown. | Remember `skipped`; Back → page 0 when skipped. | [x] |
| M9 | Minor, confirmed | `AerialView.java:245-262` | Song changes during the 500 ms "ready" dip leave the black veil up until the next video is ready (can be tens of seconds). | In the `s != coming` branch `veil(shown != null ? rest : 0f, 300, null)`. | [x] |
| M10 | Minor, confirmed | `AerialView.java:62, 248` | `failures >= 3` is permanent for the view's life; in `MusicScene` one Wi‑Fi blip = no aerials all evening. | Reset `failures` in `next()` when `elapsedRealtime() - lastFailAt > 60_000`. | [x] |
| M11 | Minor, confirmed | `AerialView.java:270-275`, `Aerials.java:49-53` | Any HEVC error (even an IO timeout) sets `hevcFailed` for the process → double data forever after. | Only on `MEDIA_ERROR_UNSUPPORTED (-1010)`, `MALFORMED (-1007)`, `SERVER_DIED`; retry H.264 once for IO errors without flipping the flag. | [x] |

### Batch C — main-thread stalls and memory (the "bottlenecks")

| # | Sev | Where | What happens | Fix | Status |
|---|---|---|---|---|---|
| S1 | Major, confirmed | `Continue.java:193` (`previewsCached`), `:295-298`, `:580` (`trim()` clears only `ART`), `:625-627` (`want = max(480, max(w,h)/2)`); `TopShelf.java:33` (`CACHE`), `:301`; `HomeActivity.onTrimMemory` 613-635 | Up to 8 decoded ARGB posters per Watch Next app, often unsampled 1280×720 (3.7 MB each) → 40–80 MB realistic, 180 MB worst, never released by `trim()`/`onTrimMemory`/`makeRoom()`. | LRU `previewsCached` bounded to ~3 packages; clear both caches in `Continue.trim()`; in `fetch()` sample to card width (~640 px) and `inPreferredConfig = RGB_565` for web art. | [x] |
| S2 | Major, confirmed | `Backdrop.java:895-918` (`read()`), `:942-961` (`mix()`) | Photos decode ARGB_8888 at 1.2× screen: sharp 11.9 MB + steady 8.3 MB + soft + blur per slide; two slides during a fade ≈ 42 MB (40% of a 96 MB heap). Photos have no alpha. | `o.inPreferredConfig = RGB_565` for sharp/soft/steady and `mix()` output; `paint.setDither(true)`. (Coordinate with R1 below: quality tier by RAM class.) | [x] |
| C12 | Major, confirmed | `ControlCenter.java:1056-1058` (`refresh()` → `buildSegments()` on main), `:1221-1271`, `:1298-1321`; `Cec.java:27-33, 79-88` | `buildSegments()` runs on main: `getTvInputList`, `getInputState` + `label()` per input (twice plus inside the sort comparator), `loadLabel` resource loads, `Cec.refresh()` → `tryHdmiManager()` reflection + Binder that throws `SecurityException` every call because the 30 s throttle is only written on success, `TvContract` query + two `resolveActivity` per tuner. Runs on CC open, 700 ms later, every sheet close, every notification, every resume. Freezes the D-pad on 1–1.5 GB TVs and on Sony/TCL right after boot. | Cache `label(info)` in a map before sort/signature/build; move the whole read onto the existing `cc-read` thread producing `(sig, List<Source>, lead)`, apply on UI; `Cec.refresh()` sets `readAt` before the permission early-return; consider dropping `tryHdmiManager` (always fails for non-privileged apps). | [x] |
| C13 | Major, likely | `ControlCenter.java:748-750` (`notesArrived()` → full `refresh()`), `:780-782` (`refreshNotes()` unused) | Every notification add/remove triggers full `refresh()` incl. C12 + two new threads. A progress notification updating each second = one full refresh per second. | `notesArrived()` → `refreshNotes()` only (debounced 300 ms). | [x] |
| D3 | Major, confirmed | `SettingsPanel.java:1672` (`Debloat.present(act)` inside `buildDebloat()`), `Debloat.java:74-87` | `getInstalledApplications` + `getApplicationLabel` per match on main, on **every** rebuild of the Free up memory page (entry, memory arrival, each confirm, each switch). 100–400 ms stalls; `TransactionTooLargeException` possible on 5.1–7 and uncaught. | Compute once per visit on the existing `SafeThread` with `Debloat.memory()`, cache in `dbFound`, invalidate after `runDebloat`; try/catch the PM call. | [x] |
| D4 | Major, likely | `Categories.java:170-175` (A–Z sort → `label(pm,p)`), `:229-251` (`kind()` → `getApplicationInfo` per uncached app), called from `HomeActivity.populate()` (main) | N Binder calls + resource loads per populate when caches are cold (`forget(pkg)` empties them on every package change). 0.3–1 s hitch on 1 GB TVs with 60–100 apps. `AppRepo.App.label` already has the label. | Pass labels (`Map<String,String>` or the `List<AppRepo.App>`) into `Categories.members/sort`; sort with `Collator` on `a.label`; pre-warm `kind()` on the `AppRepo.load` background thread. | [x] |
| M2 | Major, likely | `MediaWatch.java:64-65` (maps keyed by `MediaController`), `:540-551` (`meta()` falls through to `m.getMetadata()`); `NowPlaying.java:76-81, 454-457`; `MusicStrip.java:56-62, 201-204` | `onActiveSessionsChanged` delivers new controller objects; `NowPlaying`/`MusicStrip` keep the old one → `meta()`/`state()` miss the cache and make synchronous binder calls (unparcelling cover art) twice a second on main. | Key `metaOf`/`stateOf` by `MediaSession.Token` (value equality) and look up by `m.getSessionToken()`. | [x] |
| M1 | Major, likely | `HomeActivity.java:336-339` (`makeRoom` guard `resumed && hasWindowFocus()`), `Ambient.java:243-248` | On Android ≤10 the dream is a window over Home; `onDreamingStarted` usually runs before Home's focus-lost message, so the OOM guard written for Fire OS 6/7 and 1 GB TVs is skipped on exactly those TVs → two Backdrops + decoder. | `if (isDestroyed()) return;` only (the runnable is only ever invoked by `Ambient` when a dream starts). | [x] |
| M3 | Major, likely | `HomeActivity.java:3894-3917` (`startScene`), `MusicScene.java:81-95` vs `Ambient.java:388-407` | Home's `Backdrop` keeps drifting, decoding a new photo every 90 s and crossfading under the opaque aerial `SurfaceView` in Music scenes. `Ambient` stops its Backdrop on `shown()`; `MusicScene` doesn't. | Give `MusicScene` `onShown/onLost` (reuse `AerialView.Listener`) and `backdrop.stop()/start()` like `Ambient`. | [x] |
| S4 | Major, likely | `Renderer.java:734-744` (`event()`), `:746-792` (`deliver()` 3 s connect + 5 s read per callback) on the shared 3-thread `Compat.pool` | A controller phone that left the network costs up to 8 s per callback per transport event; 3 dead subs saturate the pool → SSDP/SOAP deaf for minutes; queue unbounded. | Dedicated single-thread executor for NOTIFY; 1 s/2 s timeouts; drop a `Sub` after 2 consecutive failures. | [x] |
| S7 | Minor, likely | `Watched.java:57, 76` | `static synchronized note()/write()` do JPEG compress + prefs commit inside the class lock; a main-thread caller blocks 50–300 ms behind a worker. | Lock only the in-memory list mutation; compress/write outside the lock. | [x] |
| H9 | Minor, confirmed | `Trouble.java:112-117, 124, 150, 428` | `synchronized (Trouble.class)` taken on main while `save()` does `fsync` on a `THREAD_PRIORITY_BACKGROUND` thread under the same lock (no priority inheritance). | Separate small lock for `pending/known`; file I/O under its own lock that main never takes. | [x] |
| D11 | Minor, confirmed | `SettingsPanel.java:2555-2557`, `:2403` | "Your photos" page decodes every thumbnail JPEG on main in `build()`. | Decode on `SafeThread`, post into the row's `ImageView` by tag. | [x] |
| D17 | Minor, confirmed | `SettingsPanel.java:1609-1613, 1641, 1942-1945` | "Set up on this TV" page: 3–4 PM calls per rebuild, rebuilt up to ~12× during setup. | Cache `tvHome*` results per visit keyed by `adbState`. | [x] |
| C14 | Minor, confirmed | `WifiSheet.java:76-81, 265-367` | Full `rebuild()` (4 WifiManager calls + all Pads recreated) per broadcast; `NETWORK_STATE_CHANGED` arrives 5–10× while connecting. | Debounce 150 ms. | [x] |
| C15 | Minor, suspect | `ControlCenter.java:98-110` (`music()`) | `getApplicationInfo`+`getApplicationLabel` IPC on every media change, even with CC closed. | Cache label by pkg (reuse `face(pkg)[0]`). | [x] |
| S5 | Minor, confirmed | `Card.java:255, 258, 270, 276`; `Bloom.java:80`; `Splash.java:189` | Android 6.0 only: `Shader.setLocalMatrix` after `Paint.setShader` is ignored (Paint caches native shader). Parallax spot, bloom placement, splash gradient freeze on API 23. | Re-call `paint.setShader(shader)` after each `setLocalMatrix`. | [x] |
| S6 | Minor, likely | `Splash.java:169` | Full-screen alpha fade with overlapping rendering → offscreen 1080p layer per frame for 600 ms, right as shelves inflate. | `hasOverlappingRendering() { return false; }` or hardware layer for the fade only. | [x] |
| S11 | Minor, likely | `Tiles.java:27, 363` | `LOGOS` unbounded; lifted logos stored at banner size (switcher 840×473 → 1.6 MB each, one per app per size); `lift()` per-pixel work on main inside the lock. | Store marks at ≤320 px wide, scale at draw; LRU ~24; lift on `Compat.background`. | [x] |
| S13 | Minor, confirmed | `Bloom.java:134-137` | Two `PorterDuffColorFilter` allocations per frame during tint transitions. | Cache by colour int. | [x] |
| S14 | Minor, confirmed | `Ui.java` `SHADOWS` | Unbounded by size key (ALPHA_8, small). | LRU 16. | [x] |
| M5 | Minor, likely | `Continue.java:624-628` + music callers | Album art kept at ≥ half screen width for a 58–220 dp cover (9–36 MB bitmaps); `MediaWatch.byPkg` never evicted. | `maxPx` parameter; music passes `Ui.dp(COVER)*2`; evict `byPkg` older than `REMEMBER_MS`. | [x] |
| M14 | Minor, suspect | `Ambient.java:258-259` | `Warm.start()` (`cmd package compile -m speed`) while an aerial decodes on a 1 GB TV. | Skip `Warm.start` when the chosen scene is aerial. | [x] |

### Batch D — correctness, lifecycle, polish (smaller, do after A–C)

| # | Sev | Where | What happens | Fix | Status |
|---|---|---|---|---|---|
| H6 | Major, likely | `HomeKeys.java:92, 220-239` | Held-volume ramp: 120 ms release guard loses quick taps; `VOLUME_MAX_MS = 3000` = 33 steps = full volume on 15-step TVs; no release signal at all on TVs without `vol.Events`. | Cap ramp by steps (≈6) unless a second WindowManager `volume_changed` within ~500 ms confirms a hold; time the guard from the log line's timestamp. | [x] |
| H7 | Minor, confirmed | `ProfileButton.java:43-84` | logcat child never restarted after EOF/kill → volume hold and Realtek profile button silently stop for the session. | On reader EOF, if `HomeKeys.live && granted(c)` re-`start()` with backoff 1 s → 30 s. | [x] |
| H8 | Minor, confirmed | `SafeThread.java:20-26`; `Welcome.retire()` 794-815; `SettingsPanel.installUpdate`, `adbConnect` 1914-1930 | Swallowed `Throwable` leaves `retireBusy`/`updateState`/`adbState` stuck → dead buttons. | `SafeThread(Runnable, String, Runnable onFail)` overload posting `onFail` to main; or `try/finally` in those lambdas. | [x] |
| H10 | Minor, confirmed | `Adb.java:93-95` (`Link.open`), `AdbTls.java:315-318` (`pair`) | Socket not closed when `connect()`/`wrap()` throws (normal path with a stale TLS port). | `try { s.connect(...) } catch (IOException e) { s.close(); throw e; }`; same for `plain`. | [x] |
| H11 | Minor, confirmed | `Adb.java:479-481, 573-580` | `run()` returns null on IO failure without touching `adb_ok` → callers report success ("Home handed back") when nothing ran. `forceStop` has no own-package guard. | Count consecutive failures, `Adb.on()` false after N; `restoreHome` returns boolean, hud only on success; skip own package in `forceStop`. | [x] |
| H12 | Minor, likely | `HomeKeys.java:450, 461-465, 490-497` | `afterBoot()` early-returns when `front != null` (always, from `lastForeground()`), so the "Aura after a restart" branch is dead; `hasInputs()` returns true on the Fire Stick `getTvInputList()` NPE. | `last = front != null ? front : lastForeground(); if (last != null ? isOther(last) : !hasInputs()) openHome();`; `hasInputs()` → `!Compat.noTvInputs(this)` on exception. | [x] |
| H13 | Minor, likely | `HomeKeys.java:575-596` (`refilter()`), `:270-275` (`goHome` leaves `taken`) | Toggling `FLAG_REQUEST_FILTER_KEY_EVENTS` resets InputDispatcher (cancels held keys, drops repeats) on every app↔Aura transition; `goHome` leaving `taken` set blocks `refilter()` until the next Home UP. | Defer `refilter()` ~300 ms after the last key; clear `taken` in `goHome` from the 1000 ms safety post. | [x] |
| H14 | Minor, suspect | `HomeKeys.java:431-447` | Second `onServiceConnected` registers a second `InputDeviceListener`. | Unregister the previous one first. | [x] |
| H15 | Minor, likely | `Boot.java:27-30`, `HomeKeys.java:863` | Android 11+: user disables the service by hand while it's on the crashed list → no `onUnbind` → `keys_wanted` stays → Boot re-enables it. | Store the secure-settings string Aura wrote; in `Boot` only re-enable if unchanged; clear `keys_wanted` in `heal()` when `!on(c)`. | [x] |
| H16 | Minor, likely | `HomeKeys` / `HomeActivity.java:503-504` | Android 11+: a memory kill puts the service on the crashed list; the only heal is Home's `onResume`+4 s → Home button dead until the user finds Aura. | `JobScheduler` heal job on process start when `wanted && on && !live`. | [x] |
| H17 | Minor, confirmed | `Safety.check()` stage 1 | Writes `PARALLAX/TOP_SHELF/SAVER` to the current profile only. | Device-level `safe_mode` flag honoured by readers, or write all profiles. | [x] |
| H18 | Minor, suspect/confirmed | `Updater.java:170-207, 186-188` | Orphaned `PackageInstaller` sessions after process death; body longer than metadata `size` silently truncated and committed when `sha256 == null`. | Abandon inactive sessions on next use; fail when body overruns `total`. | [x] |
| D6 | Minor, confirmed | `Backup.java:74-82` vs `:101-122` | Validation checks shape not type; `["b","yes"]` passes, then throws mid-apply after another profile was `clear()`ed. | Coerce every value in the validation pass before touching prefs. | [x] |
| D13 | Minor, confirmed | `Profiles.java:177` | Removing a profile leaves `aura_<id>_launches`. | Clear it too. | [x] |
| D14 | Minor, confirmed | `Backup.java:28-34`, Profiles page | Kids profile restored onto a TV without a PIN → PIN row hidden, no way to set one without toggling kids. | Show the PIN row when any profile is kids. | [x] |
| D15 | Minor, suspect | `SettingsPanel.java:221-238, 1068` | `open()` doesn't reset `builtPage` → stale scroll offset re-applied. | `builtPage = ""; scroll.scrollTo(0,0);` in `open()`. | [x] |
| D12 | Minor, confirmed | `Categories.java:180` vs `:360` | "Recent" sort is TV-wide, "Most" is per profile. | Record recents per profile, or document. | [x] |
| S8 | Minor, confirmed | `Drop.java:151, 190` | Binds all interfaces; per-read 30 s timeout only (slowloris holds a pool thread). Auth is sound. | Bind to the Wi‑Fi/Ethernet address in the QR; 15 s total request deadline; cap 4 concurrent. | [x] |
| S9 | Minor, confirmed | `Cast.java:170, 322-326` | SSRF guard covers `fetch()` but not `setVideoURI`; DNS TOCTOU. | Same loopback/link-local check on the video host. | [x] |
| S10 | Minor, confirmed | `Fresh.java:248-268`, `Picks.java:662-673` | `readAll` unbounded; `check()` catches `IOException` only (`IllegalArgumentException` from a CR/LF header aborts refresh). | Cap 4 MB; catch both. | [x] |
| S12 | Minor, likely | `Continue.java:112-150` | No `WATCH_NEXT_TYPE_WATCHLIST` / age filter; no `HomeKeys.allowed` at read (kids see hidden app's cards). | Skip watchlist unless opted in; drop > 60 days; filter by `allowed`. | [x] |
| M6 | Minor, confirmed | `Gallery.java:118-124, 544-548`; `DailyJob.java:21, 51-57, 67-72` | Interrupt flag swallowed → downloads continue after `onStopJob`; `jobFinished` skipped on throw; `busy` stuck if `Thread.start()` throws. | Re-interrupt at both catch sites; `jobFinished` in `finally`; `try/catch` around `start()`. | [x] |
| M7 | Minor, confirmed | `Sayings.java:160-190, 224-264` | First quote fetched synchronously (up to ~50 s) before anything (even the clock) is drawn. | `pick()` returns pool/bundled at once; refill as a separate task when `pool < 8`. | [x] |
| M8 | Minor, suspect | `AerialView.java:277-292` | One-decoder heuristic only catches the second player failing; a silent freeze isn't detected. | Set `oneAtATime` when `shown` errors while `coming != null`; 10 s position watchdog. | [x] |
| M12 | Minor, likely | `network_security_config.xml:7-12` | Only "Apple Root CA" pinned; G2/G3 missing → silent fallback to photos if Apple rotates. | Bundle G2 and G3 too; log `SSLHandshakeException` by name on first failure. | [x] |
| M13 | Minor, confirmed | `AerialView.java:86-91` | No metered-network check for 2–3 GB/h streams. | Skip on `isActiveNetworkMetered()` unless a second preference allows it. | [x] |
| M15 | Minor, confirmed | `Weather.java:97-99, 108-111`; `Sun.java:17-28, 74-82`; `HomeActivity.java:2195` | `JSONObject.NULL` makes the whole forecast null; NaN → `0°`; `Sun.next() == -1` printed as a 1970 time; time-zone estimate frozen at class load. | `isNull` checks; skip NaN rows; guard `-1`; recompute on `ACTION_TIMEZONE_CHANGED`. | [x] |
| M16 | Minor, confirmed | `MediaWatch.java:339, 586, 671`; `NowPlaying.java:469`; `MusicStrip.java:215` | Negative speed (rewind) treated as 1× forward. | `speed != 0 ? speed : 1f`, clamped. | [x] |
| C5 | Minor, suspect | `BtSheet.java:64-79, 165-174` | Profile proxy callback after `release()` leaks the binding and the Activity. | `released` flag; close the proxy in the late callback. | [x] |
| C6 | Minor, confirmed | `ControlCenter.java:1237, 938, 753-761` | `release()` doesn't cancel the `buildSegments` retry chain. | Store the runnable; `removeCallbacks` in `release()`/`close()`. | [x] |
| C10 | Minor, confirmed | `WifiSheet.java:343-349, 576-578` | `disconnect()/reconnect()` return values ignored → false "Disconnected" HUD. | Check the boolean; fall back to system settings. | [x] |
| C16 | Minor, confirmed | `ControlCenter.java:468, 484-486` | `inState` (10 sp, no ellipsize) hard-clipped at 58 dp: "Nothing plugged in", "Nichts angeschlossen". | `setEllipsize(END)` or shorter strings in all 4 locales. | [x] |
| C17 | Minor, confirmed | `WifiSheet.java:128, 591` | `joinLabel` with a long SSID clipped. | `setEllipsize(END)`. | [x] |
| D5 | Minor, confirmed | `SettingsPanel.java:965-971, 476`; `HomeActivity.java:2620-2622` | Empty category rows are invisible on Home and Settings gives no hint ("4 rows" while 2 show). | Show `empty_not_shown` as the row's value; count only non-empty rows. Also part of the Categories redesign. | [x] |
| D9 | Minor, likely | `SettingsPanel.java:117`, `Welcome.java:600` | German notes > ~180 chars cut with no ellipsis at 720p (nine strings listed in the review). | `setEllipsize(END)` and/or 5–6 max lines; shorten the DE strings. | [x] |
| D10 | Minor, confirmed | `Welcome.java:904`, `Folders.java:56` | Hardcoded English `"<the TV's IP>"`, `"Folder"`. | Move to strings (4 locales). | [x] |

### Checked and clean (don't re-review)

Translations (972 keys × 4, identical); Java 8 library APIs (none); API > 22 guards across all
clusters; `Drop`/`Renderer` auth, CSRF, path traversal, HTML escaping; ADB shell injection
(`[A-Za-z0-9_.]+` everywhere, `Adb.type` quoting); ADB TLS/pairing; `Updater` SHA‑256 before
commit; Qr encoder; Switcher `closeAt`; Picks rotation; `MediaPlayer` state machine; `Visualizer`
permissions; Handler hygiene after detach; PIN hashing; kids reaching Settings/uninstall/ADB;
focus escaping overlays; `PendingIntent` flags; `onDraw` allocations in CC widgets.

---

## 3. Reddit feedback (7.7 thread) — confirmed in code

| # | Report | Root cause | Fix | Status |
|---|---|---|---|---|
| R1 | "Wallpapers look blurry, even my own 4K uploads (65" Bravia)" | (a) `Mine.java:44` re-encodes every phone photo to **1536×864** JPEG q90 — below 1080p; (b) `HomeActivity.java:75` `HOME_DEFOCUS = 0.38f`: Home *rests* with 38 % of a 768×432 box-blurred copy mixed over the photo (`Backdrop.mix` → `steady`); (c) Ken Burns zoom 1.05–1.2× on top. | Quality tier by RAM class: ≥2 GB store phone photos at display size (1920×1080, or 2560×1440 when the TV reports a 4K UI) and skip the 1.2× over-decode; ≤1.5 GB keep today's sizes (and RGB_565 from S2). New setting "Photo sharpness: Soft (today) / Sharp" that sets `HOME_DEFOCUS` to 0.38 / 0.12. Keep the resting blur for legibility only where the user asks. Strings in 4 locales. **Done Nov 6:** `Mine.size(c)` stores phone photos at 1920×1080 (2560×1440 when the UI reports ≥2560 wide; 1536×864 stays for `Perf.lowEnd` or sub-1080p UIs); `decode`/`compose` take the size. New `Prefs.PHOTO_SHARP` (off = today's 0.38); `HomeActivity.homeDefocus()` replaces the `HOME_DEFOCUS` constant at all 7 sites (0.12 when on); Settings → Wallpaper row "Photo sharpness: Soft / Sharp" (`what = "sharp"`) → `applySetting` re-sets `backdrop.setSteadyDefocus` + `setDefocus`; `Backdrop.setSteadyDefocus` re-mixes the on-screen slides' `steady` copy so the picture doesn't jump. Ken Burns zoom unchanged (owner can decide). Strings ×4. | [x] |
| R2 | "Sent 700 photos, only 60 stayed, nothing told me; it kept uploading and deleting" | `Mine.KEEP = 60` (`Mine.java:42`); `add()` evicts the oldest silently (line 156); `drop.html` never shows the cap or a count and never stops the queue. | Show "N of 60 on the TV" on the phone page and the Settings photos page; stop the queue at the cap with a clear message ("The TV keeps 60 — remove some or raise the limit"); setting "Keep up to: 60 / 120 / 250" with a storage-aware ceiling (free space ÷ ~1.5 MB); return a `409` with a message from `Drop` when full instead of evicting. **Done Nov 6:** `Mine.KEEP_CHOICES {60,120,250}`, `keep/setKeep` in device prefs (`photos_keep`), `keepCeiling(c)` from StatFs free + used − 300 MB at ~1.5 MB/photo; `Mine.add` throws `Mine.Full` (before decoding, and again under LOCK if a parallel upload filled it) — **no eviction any more**; `Drop` answers `409 Conflict` with the message and adds `"keep"` to `/list`; `drop.html` shows "N of keep", a hint when full, and on a 409 marks the rest of the queue "Not sent · the TV is full" (APKs still go); Settings main list + From-your-phone page show "N of M on the TV"; photos page row "Keep up to: 60/120/250 photos" cycles within the ceiling. Strings ×4 (`the_tv_keeps_n_remove_some`, `keep_up_to`, `n_of_m_on_the_tv`). | [x] |
| R3 | Owner, Nov 6: "watching a show on my low-end TV with Aura installed, the video lags; not the internet" | Live snapshot of the owner's 929 MB TV with Hotstar in front: Aura 0.0–0.4 % CPU, no wakelock/multicast lock, HomeKeys only `WINDOW_STATE_CHANGED` — clean. But Aura kept **~43 MB native heap** (Backdrop sharp+soft+steady+blur, Tiles `LOGOS`, Continue `ART`, Card bitmaps) while backgrounded; zram swap 300/512 MB, free ~50 MB. The kernel compresses/pages that heap in and out around the player — kswapd churn = stutter. Code audit (read-only) found why nothing is ever released: `HomeKeys` + `MediaWatch` are system-bound, so the process sits at foreground-service priority and **never receives `TRIM_MEMORY_BACKGROUND/MODERATE/COMPLETE`** — only `UI_HIDDEN`, which `HomeActivity.onTrimMemory` (~`:627`) ignores, and `Backdrop.trim` (`:284`) refuses anything below `MODERATE`; `:628` also skips `RUNNING_CRITICAL`. Secondary hitches that land exactly during playback: (b) `HomeKeys` preview screenshots at +4 s/+12 s after every activity change and on every Back / 30 s key press (`HomeKeys.java:563-566, 685-689`, SurfaceFlinger capture + GPU downscale + JPEG, black for protected video and discarded *after* capture); (c) `DailyJob` photo import every 6 h with `NETWORK_TYPE_ANY` and no idle constraint (`Daily.java:59-72`), 6–12 ARGB decodes at 2× target (`Gallery.java:465-495`); (d) full `AppRepo.load` + `populate()` on every Play auto-update while an app is in front (`HomeActivity.java:233-247`); (e) `ProfileButton` keeps a `logcat -b main -b events` child reading every line the player logs (`ProfileButton.java:61-84`); (f) DLNA `Renderer` holds a MulticastLock for the whole session (`Renderer.java:196-198`; live `dumpsys wifi` on the owner's TV showed only `com.google.cast.receiver` — re-check with DLNA on). `Perf.lowEnd` (`<1300 MB`) also misses "1.5 GB" TVs that report ~1.4 GB. | Ordered by impact/risk: **(1)** `HomeActivity.onTrimMemory`: on `UI_HIDDEN` when `Perf.lowEnd()` → `backdrop.trim(level)`, `Continue.trim()`, `Previews` drop `fresh` (keep `last`), `faces.clear()` if the switcher is closed; `Backdrop.trim` guard → `level < UI_HIDDEN` (it keeps the 0.4 MB `standIn`, so Home comes back to a picture); fix `:628` so `RUNNING_CRITICAL` (5) is handled; keep `Tiles.LOGOS` (switcher cards). Reload on `onStart` via the existing `homeComing` path. **(2)** `HomeKeys`: skip `settleShot`/`settleShotLate` and the 30 s key refresh when `Perf.lowEnd` or `MediaWatch.now()` says the front app is playing; keep the Home-press shot. **(3)** `Daily`: `setRequiresDeviceIdle(true)` on low-end, or `jobFinished(params, true)` when a session is playing; `Gallery.file()` decode RGB_565 with `inSampleSize` to `W` not `2W`. **(4)** `packageReceiver`: when `!resumed` set `appsDirty`, run `reloadApps()` in `onResume` (keep `HomeKeys.packagesChanged()` + `Vpn.forget()`). **(5)** `Renderer`: release/re-acquire the MulticastLock from `onPause/onResume` on low-end (HTTP port stays up for already-paired controllers) — medium risk, ask first. **(6)** `ProfileButton`: no `logcat` reader on low-end or when no Google TV home. **(7)** `Perf.lowEnd` threshold `< 1600 MB`. Targets: `dumpsys meminfo app.aura.home` ≤ 20 MB PSS while another app plays; `dumpsys gfxinfo <player>` janky-frame % before/after 3 min of Hotstar on the owner's TV (`192.168.31.111:5555`); Home back on screen within one photo decode (~300 ms). **Done Nov 6 (owner: low-end only; high-end keeps everything):** every change is gated on `Perf.light(c)` — on by itself for low-end TVs, forceable either way in Settings → Light mode. (1) `HomeActivity.onTrimMemory` `UI_HIDDEN` branch: `backdrop.trim(MODERATE)` + `Previews.dropFresh()` + `Continue.trim()` (Tiles/switcher cards kept; `Backdrop.trim` guard untouched, so nothing changes on high-end). Note: the audit's "`:628` skips `RUNNING_CRITICAL`" was wrong — `RUNNING_CRITICAL` is 15, `RUNNING_LOW` is 10, so `>= RUNNING_LOW` already covers it. (2) `HomeKeys.midVideo()`: settle shots and the Back/30 s key shot are skipped while `MediaWatch.now()` says the front app is playing; Home-press shot kept. (3) `DailyJob.onStartJob`: `jobFinished(params, true)` when media is playing (Android retries with backoff); `Gallery.file()` decodes and crops in RGB_565 (sizes unchanged — R1 is about blur). (4) `packageReceiver` sets `appsDirty` when home isn't resumed; `onResume` runs `reloadApps()`. **Not done:** (5) DLNA multicast lock — owner's call; (6) `logcat` reader — its filter runs inside logcat (`*:S`), live CPU was ~0, and it also drives held-volume smoothing on non-Google TVs, so no change; (7) `Perf.lowEnd` threshold 1300 → 1600 MB — owner's call (1.5 GB TVs that set `isLowRamDevice` are already covered). Still to verify on the owner's TV. | [ ] |

---

## 4. Redesign phases (next chat; specs to be completed from the tvOS/Chinese research)

Principles agreed so far:
- **One sheet language.** Control Center, Notifications, Settings, Wi‑Fi, Bluetooth, Inputs,
  Folder, App menu become one component: same glass, same edge, same header (title · one-line
  explanation · Back), same focus ring, three motion durations, one owner for visibility and
  focus so a sheet can never be forgotten again (C1/C2 were exactly that).
- **Settings:** five top-level groups, ≤7 items each, every item with a one-sentence "what this
  does"; live preview — the real home behind the sheet changes as you move between options.
- **Control Center:** two rows of tiles + one context row (what's playing / what's on the input);
  notifications as a list in the same sheet, not a separate surface; a small RAM meter and real
  MB freed by "Free up memory" (honest numbers).
- **Categories:** your rows plus an "Auto" toggle per category with a visible rule ("Streaming =
  apps that publish video to Watch Next"); empty rows shown with a hint; left-overflow indicator;
  "All apps" as a grid page, not a 40-tile strip.
- **Previews / For you page:** built from what exists — Watch Next (`Continue`), TMDB (`Picks`),
  TVmaze (`Fresh`), `Watched`/`Habits`/`Taste` — a genres rail, "Where is it playing?" search
  (TMDB watch providers → open the installed app), trailers only on ≥2 GB TVs.

## 5. Feature roadmap (next chat) — ranked by shock × feasibility

1. **Resume Row with your real last frame** — fuse `Previews` screenshots + `Watched`/`MediaWatch`
   position: the exact paused frame with a progress bar for every app, one press to resume.
2. **Perceived 0 ms launch** — tile zooms into the app's last screenshot (`Switcher` zoom exists)
   while the real app starts underneath.
3. **Your photos, properly** — R1/R2 plus "On this day" (`Mine` stores `day`) and a live grid on the phone page.
4. **Share your Look as a code/QR** — `Backup` already serialises everything; import in Settings → Looks.
5. **tvOS-grade sound design** — six `SoundPool` sounds, off by default on Fire OS 5.
6. **One sheet language** (section 4).
7. **Transparent time-aware Home** — `Habits` + `Sun` + `Profiles` re-rank rows by hour with a "why" chip.
8. **Privacy report** — every network call listed with a toggle and "last contacted".
9. **Paste a link on the phone → opens on the TV** — `Drop` + `ACTION_VIEW`.
10. **Idle hero** in `Ambient` ("Press OK to continue"), **console-aware inputs** via `Cec`,
    **Perf badge** ("Home ready in 0.8 s on this TV") from `Perf`.
11. From ARGON's blueprints: Fire TV settings deep-link router, `performGlobalAction` power/notifications,
    audio-focus pulse on home return, AppWidgetHost cards, Google TV "Pair remote" intent, SSDP friendly
    name, OEM picture/sound menus, built-in tuners in the Inputs row.

## 6. Research — landed (full reports in Appendix A–D at the end of this file)

Four reports are in: **A** open-source/popular launchers, **B** Chinese launchers + low-end
know-how, **C** AOSP/Google TV internals + platform APIs, **D** tvOS design/focus engine. Read the
appendix before starting section 4/5 work. What changes the plan right now:

1. **Memory behind the player is the low-end stutter cause, not CPU.** Live check on the owner's
   929 MB TV (Nov 6): Aura in the background = 0.0–0.4 % CPU, no wakelock, no multicast lock,
   HomeKeys only on `WINDOW_STATE_CHANGED` — but **~43 MB native heap kept** (Backdrop sharp/soft/
   steady/blur + Tiles + Continue ART) while zram swap sat at 300/512 MB and free at ~50 MB.
   Google's 1 GB target is 30–40 MB graphics for the *foreground* app. → New item **R3** in
   Batch D: on `onStop` / `onTrimMemory(UI_HIDDEN)` release what `makeRoom` releases for the
   screensaver (Backdrop bitmaps, Previews, Continue/Tiles caches), re-decode on `onStart`.
   Verify with `dumpsys meminfo app.aura.home` while a video app plays (target ≤ 20 MB PSS).
2. **Zero redraws when idle** is the bar (stock Google TV home burns ~43 % of a core idling at
   60 fps; Projectivy with effects off = 0 frames/s). BeatGlow/Ambient/aerials/clock ticks must stop
   after inactivity. Measure with `dumpsys gfxinfo app.aura.home` frame deltas over 60 s.
3. **AOT over the local ADB link**: with no baseline profile, API 28+ leaves Aura at `verify`
   (interpreted). `cmd package compile -m speed -f app.aura.home` after install/update (API 24+),
   or `speed-profile` after a day of use. Check `dumpsys package dexopt`. Never set `vmSafeMode`.
4. **`HdmiControlManager` is `@SystemApi`** — `Cec.tryHdmiManager` always throws for Aura; drop it
   (C12 already throttles it). CEC children are TIF inputs: group by `TvInputInfo.getParentId()`.
5. **Watch Next read rules** (already followed in `Continue.java`: null selection, explicit
   projection): add `COLUMN_WATCH_NEXT_TYPE`, `COLUMN_LOGO_URI`, hide rows ≥ 95 % progress or older
   than N days (everyone shows stale 6-month-old cards). Fire OS has no TV provider at all.
6. **Flash-free Home**: launch `HomeActivity` with `ACTION_MAIN + CATEGORY_HOME + NEW_TASK` from the
   accessibility service on connect/boot so the task is `type=home` (stock launcher stops flashing
   on Back — Projectivy issue #605, fixed in their 4.73). Verify `dumpsys activity activities | grep type=home`.
   Also filter `KEYCODE_ALL_APPS` (284) with HOME; map `KEYCODE_TV_INPUT`/`KEYCODE_SETTINGS`.
7. **Takeover order that doesn't boot-loop**: never disable setupwraith on ≥ 28 (done, H3); never
   disable launcherx on the Google TV Streamer; on others disable setupwraith → start Aura →
   disable launcherx → `set-home-activity`, and re-run `set-home-activity` after any enable/disable.
8. **Settings deep links**: `android.settings.BLUETOOTH_SETTINGS` does not resolve on Android TV
   (C11 routes to `ACTION_SETTINGS` — correct); remote pairing is `com.google.android.intent.action.CONNECT_INPUT`.
9. **tvOS numbers for section 4** (Appendix D): safe area 40/30 dp, 6-col grid 130 dp tiles with
   20 dp gaps, focus scale 1.10 (1.06 hero), focus-in 220 ms / focus-out 350 ms, springs (0.35 s,
   bounce 0.15) snappy and (0.45 s, 0) smooth, glass-regular blur σ 8 dp tint rgba(22,22,23,.62)
   sat 1.8, never glass-on-glass, type floor 11.5 dp / body 16 dp, tab bar 34 dp at y = 23 dp.
10. **Features nobody has** (Appendix A §7, B §5): self-healing Home takeover diagnostics screen,
    type-on-phone IME over the existing HTTP page, Jellyfin/Plex/Kodi resume rows, elderly/simple
    mode, boot-to-app/HDMI, real "return home frees memory" with whitelist, public RAM number
    (Couchy wins with "80–130 MB"), purchase/licence without Play Services.

## 7. Verification checklist for every batch

1. `.\tools\build.ps1` clean (javac is the type check).
2. `.\tools\test.ps1 -NoBuild` → `All tests passed`; add/adjust a test where a finding is testable
   (D2 confirm reset, C1/C2 input sheet, H1 `originalDisabled`, D1 drop page, M2 token keys).
3. For UI changes, install on the owner's TV (versionCode above the installed one) and check the
   actual screen; never drive it with adb key presses.
4. Strings: four locales, counts identical; clear `tests\target\classes` after string changes.
5. No AI attribution, no debug prints, no commented-out code.
6. Report: files changed, build/test output pasted, anything left open.

---

# Appendix — research reports (Nov 6, 2026)

Each report was produced by reading sources (AOSP/androidx source, developer.android.com, HIG/WWDC,
GitHub issue trackers, Reddit/XDA/ZNDS threads). Licences are noted; copy behaviour, never GPL/closed code.

## Appendix A — Android TV launcher landscape (open-source and popular launchers)

### A.1 Launcher-by-launcher

**Projectivy Launcher (spocky) — the incumbent.** Closed source, not for commercial use; issue tracker
[github.com/spocky/miproja1](https://github.com/spocky/miproja1). Features: categories/rows + grid, dock/themes/folders
(4.70), Watch Next + per-app channels, "Now Playing" via NotificationListener, HDMI inputs via TIF + rename/hide,
autostart on boot / to app / to HDMI, idle→standby, video/GIF/Lottie/RTSP wallpapers with sound, Reddit/TMDB
wallpaper providers, Aerial Views integration, icon packs, parental PIN (d-pad), display profiles, "Override current
launcher" via AccessibilityService, limited remote remap, status bar battery/weather, auto backups, side-panel program
details (4.72), 50 MB image cache cap (4.72). Praised: "animations are super smooth", "way less laggy than stock",
mp4 wallpaper with sound. Complaints (by reactions): autostart broken on TCL Android 14 (#415), stock-launcher
flash on Back (#605), black Reddit wallpaper = memory leaks at the 192 MB heap (#311), parental PIN re-prompting
inside apps (#627), splash logo = OS killed the app (#24), accessibility breaks Prime Video auto-hide controls on
Fire OS (#380), idle detection wrong while an app is active (#213), killed on standby on Sony ATV10 (#354), no remote
key mapping (#286), no widgets (#264), no F-Droid/source escrow. 1–1.5 GB TVs thrash zram with it; ~400 MB RSS with
4K video wallpaper. Perf tricks in changelogs: lazy card inflate while scrolling, trim caches on `onTrimMemory`
instead of dying, dedupe programs, image cache cap, idle-mode CPU reduction.

**FLauncher → LtvLauncher → Arc Launcher (GPLv3).** Flutter; categories, row/grid toggle, own wallpaper impl
(no `WallpaperManager` on TV), nav sounds, force stop. Open issues: wallpaper exceptions/disappearing, OK key not
working on some boxes, TV inputs inaccessible, PIN for settings, VPN indicator. Praised as "basic as you can get"
for 1 GB TVs; "wish it had continue watching/channels". Arc adds video wallpaper by time of day and Wi‑Fi data usage;
top reply on its launch post: "blur the wallpaper when scrolling down, liquid glass, smooth animations… exactly
like Apple TV OS".

**ATV Launcher Pro / Wolf.** Closed, $4.99. v0.1.41: 10 fixed row arrangements (hero tiles), widgets inline in rows,
folder previews, live-preview tile configurator, status bar with input switch, Watch Next cards with progress bar,
icon packs, password folders, optional Home redirect via accessibility. Praised: ~1 s faster boot than Projectivy,
best channels editor. Complaints: A‑Z sorts by package name, 3.3★ from old freeze bugs, ANRs on wallpaper load.
Wolf dead since 2022.

**SCL4 / Chainfire Sideload / HALauncher.** SCL4: per-app PIN, multiple profiles, self-launch on boot; users moved to
it for screensaver reliability. Chainfire: tiny drawer for 1 GB. HALauncher: sidebar categories, blue-light filter.

**Leanback on Fire (tsynik).** Real Leanback codebase fork, 685★, still updated; inherits recommendation rows and
input activities. Licence unclear — study only.

**Emotn UI / Dangbei.** Emotn: "most beautiful and polished UI, all animations super smooth", weather widgets,
time-of-day live wallpapers, LAN/USB upload. Complaints: only Favorites + All Apps, no icon resize/labels/categories,
weather widget vanishing, wallpapers failing (server token), live backgrounds freeze. Dangbei Desktop (~1 MB builds):
APK installer, search, RAM cleaner, weather; Dangbei OS has kids/elder/camping modes and find-remote.

**2026 newcomers.** AT4K (tvOS layout, top shelf from focused app's channel, 5–7 col grid, folders; "feels a little
slow", no blur, 550 MB RAM). Kyu (Roku-style). Dispatch (Plex-driven Watch Next, "App Peek", #1 request Jellyfin).
**Couchy** (GPLv3, F-Droid, 2.3 MB APK, 80–130 MB RAM, <2 s cold start, glass dock, aerials via SurfaceView, JSON
config export, 18 languages). Monet (Material You palette, profiles). cleanyourtv/tv-detox/dumbtv: debloat scripts +
tiny launchers; cleanyourtv's launcher is 572 KB plain Java for 1 GB TVs, 46k★ in two weeks. nielsvanvelzen/tv-launcher
(GPLv3): "Wallpapers: not possible (no WallpaperManager on TV)".

**Takeover helpers.** Launcher Manager (system-user exploit era ended Oct 2025 at Fire OS 7 PS7706 / 8 RS8153; v2.0.0
still ships proxy-launcher mode). Home on Fire (GPLv3, aapt2+javac+d8 like Aura): accessibility redirect on Fire OS
8.1.7–8.1.8. tvQuickActions / Button Mapper TV: TCL users need `appops set … APP_AUTO_START allow`.

### A.2 Top 20 user pain points (frequency × severity, 2024–2026)

| # | Pain point |
|---|---|
| 1 | Home button won't stick / accessibility service silently turned off (ATV14 resets after reboot; sideloaded APKs need "Allow restricted settings"; force-stop drops grant) |
| 2 | Fire OS blocks custom launchers (post-Sep-2025 patch; Vega OS = no sideload) |
| 3 | Launcher not auto-starting after boot/standby (TCL AppBootPolicy, Sony kills on standby) |
| 4 | Stock launcher flashes 100–250 ms when exiting apps (launcher task is `type=standard`) |
| 5 | Disabling launcherx bricks/boot-loops (Streamer reboots; TCL black screen; setupwraith RecoveryActivity loop) |
| 6 | Netflix "continue watching" missing (Netflix publishes little; Google TV keeps it cloud-side) |
| 7 | Voice search / Assistant breaks with override; disabling launcherx kills star/YouTube buttons |
| 8 | RAM: launcher killed / splash logo / black wallpaper on 1–2 GB TVs |
| 9 | No profile switching (Google: Won't Fix, Jul 2026) |
| 10 | No global search in any third-party launcher |
| 11 | Ads + slowness in stock home (the reason to switch) |
| 12 | Accessibility side effects: Prime Video controls never auto-hide; CEC input switch flaky |
| 13 | Setup too hard (ADB, three settings menus) |
| 14 | Screensaver conflicts (Ambient Mode ads; launcher idle timer fires during playback) |
| 15 | HDMI/Live TV: OEM non-TIF switchers unsupported; HDMI1/3 swapped on AmLogic |
| 16 | Parental control bugs (PIN re-prompt; kids want true profiles + time limits) |
| 17 | Premium not buyable without Play Services |
| 18 | Remote remapping needs a second app |
| 19 | Wallpaper plumbing: no WallpaperManager, black/frozen wallpaper, no cross-dissolve |
| 20 | Privacy/telemetry of the launcher itself; demand for no-INTERNET launchers |

Explicit quotes: "blurring the background when scrolling down, liquid glass, exactly like Apple TV os"; "Not a rival
until I can use Aerial Views as my live wallpaper"; "channels follow what you are selecting as an app on the first
row?" (AT4K shipped it); "Plex in the continue watching row?"; "Dual data usage monitoring & real-time network speed";
"the white splash is painful at night"; "flip a cross dissolve from one background to another"; "24-hour time format".
Viral posts: a clean grid over a 4K aerial with no ads; before/after of parents' TV (8.6M views); "Apple TV look" +
cold-boot video; "vs" comparisons.

### A.3 Continue Watching beyond Watch Next

Publish Watch Next rows: Prime Video (continue/next/new; `intent_uri` opens profile picker on cold start), HBO Max,
Jellyfin (content:// posters, next-episode rows lack position), Crunchyroll, SkyShowtime, RaiPlay, DAZN, VLC, Netflix
sometimes. Preview channels only (no Watch Next): YouTube, Spotify, Stremio ("Continue Watching" channel with progress,
no date). Nothing visible: Disney+, Plex, Mediaset. On CCwGTV with "supported areas" the local table had 0 Watch Next
rows. Rows linger for months at 99 % → filter by `last_engagement_time` and progress. Third parties can't remove others'
Watch Next items. Projectivy "Now Playing": NotificationListener → active MediaSessions, one card. Dispatch: Plex API
directly. Nobody integrates Jellyfin/Emby/Kodi APIs — the most asked-for gap.

Deep links (Android TV): Netflix `https://www.netflix.com/title/<id>?source=30` (without `source=30` it resumes the
last title); Fire TV `intent://www.netflix.com/watch/<id>#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end`.
Prime `intent://watch.amazon.com/watch?asin=<ASIN>&time=0&territory=US#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end`;
Fire TV `amzn://com.amazon.tv.launcher/detail?provider=aiv&providerId=<ASIN>`. Disney+ `https://www.disneyplus.com/video/<id>`.
YouTube TV `https://www.youtube.com/watch?v=<id>` (SmartTube: `vnd.youtube.launch://`). Jellyfin
`am start -c LEANBACK_LAUNCHER -a VIEW -d <itemId> -e source 30 org.jellyfin.androidtv/.ui.startup.StartupActivity`.
Spotify `spotify:playlist:<id>:play`. Twitch `twitch://stream/<user>`. Hulu
`intent:#Intent;package=com.hulu.livingroomplus;action=hulu.intent.action.PLAY_CONTENT;S.content_id=<id>;end`.
Max `hbomax://urn:hbo:feature:<id>` via `com.hbo.hbonow/com.hbo.go.LaunchActivity`. Emby `embyatv://tv.emby.embyatv/startapp`.
Stremio `stremio:///`. Live Channels `com.google.android.tv/com.android.tv.MainActivity`. Discovery: read
`intent-filter` schemes from the installed APK's manifest at runtime.

### A.4 Home takeover in 2026

Google TV: HOME resolved by intent-filter priority — launcherx 2, setupwraith RecoveryActivity 1, sideloaded 0,
FallbackHome −1000. `set-home-activity` prints Success and is ignored; `RoleManager.requestRole` UI unsupported on TV.
Only `pm disable-user` of launcherx wins, and Android drops the preferred activity when the candidate set changes:
**disable setupwraith → start the new launcher → disable launcherx → set-home**, else system_server crash-loops on
MediaTek sets. Google TV Streamer reboots constantly if launcherx is disabled — don't. Flash-free redirect: launch your
own activity with `ACTION_MAIN + CATEGORY_HOME + NEW_TASK` so the task becomes `type=home`. Accessibility fragility:
ATV14 disables services after full reboot; TCL gates boot binds via `AppBootPolicy`; Xiaomi blocks `BOOT_COMPLETED`.
Mitigations: `dumpsys deviceidle whitelist +pkg`, `WRITE_SECURE_SETTINGS` so the app re-enables its own service. What
you lose without launcherx: profile picker, Assistant/far-field/star buttons on some remotes, Google Home casting on some
boxes. Fire OS 5/6: LM Mini v2 works. Fire OS 7 (PS7706+): accessibility redirect works but the Android 9 app-switch lock
delays Home-triggered launches up to 5 s. Fire OS 8 (RS8153+): redirect works with ~250 ms Amazon flash; Accessibility
settings page is blank → ADB `pm grant … WRITE_SECURE_SETTINGS` once; notification access via
`cmd notification allow_listener`; widgets via `appwidget grantbind`; Prime Video stops auto-hiding controls when any
accessibility service is on. Vega OS: not Android, no sideload.

### A.5 Coverage matrix (abridged: what Aura has that nobody else does)

App switcher with real screenshots, DLNA renderer, local ADB link (debloat), phone web page (photos/typing/QR), TMDB/
TVmaze discovery, profiles + kids PIN — none of the others have these. Gaps vs others: flash-free `type=home` launch
(Projectivy 4.73), Now Playing card (Projectivy), top shelf following focused app (AT4K), public RAM number (Couchy),
widgets (ATV Pro), icon packs, 18 languages (Couchy), licence purchase without Play Services.

### A.6 Fifteen feature ideas nobody has (or does badly), minSdk 22 feasible

1. Self-healing Home takeover with a diagnostics screen (accessibility off, HOME resolves to launcherx, task
   `type≠home`, `APP_AUTO_START` ignore, deviceidle not whitelisted, Fire OS 7 app-switch lock, Fire OS 8 blank
   Accessibility page) — one screen with the exact ADB line, sent over Aura's own ADB link when authorised.
2. Flash-free Home on every boot and after process death (`ACTION_MAIN+CATEGORY_HOME+NEW_TASK` from
   `onServiceConnected`/BOOT_COMPLETED; verify `type=home`); black `windowBackground`, never white/logo.
3. Low-RAM "survival mode" with a public number: `onTrimMemory` tiering, `Debug.getPss()` readout in Settings;
   target ≤ 120 MB on 1 GB TVs; advertise it.
4. Continue Watching from Jellyfin/Emby/Plex/Kodi/Stremio APIs (`/Users/{id}/Items/Resume`, `/Shows/NextUp`,
   `/library/onDeck`, JSON-RPC `VideoLibrary.GetInProgress*`), dedupe against Watch Next by title+S/E.
5. Watch Next hygiene: hide ≥ 95 % progress, older than N days, duplicates per app; merge Stremio's channel.
6. Now Playing card with transport controls + deep link (Projectivy only has open/stop).
7. Top shelf that follows the focused app with the poster as blurred backdrop (true tvOS).
8. Launcher-side search across apps, Watch Next, TMDB, Jellyfin/Plex, `RecognizerIntent` fallback.
9. Media-aware screensaver: never start while a MediaSession plays or a `TvView` is active.
10. Kids/senior profiles with time budgets, bedtime, allow-list, d-pad PIN, foreground block via accessibility.
11. Wallpaper cross-dissolve + memory-safe pipeline (`inSampleSize` to screen, one in flight, gradient fallback).
12. Built-in remote remapping that scopes the accessibility service per package (`packageNames`) to avoid the Prime
    Video side effect.
13. One-tap safe debloat via the ADB link with the ordered sequence from A.4 and a restore file.
14. Status bar network speed/data usage/weather/VPN (`TrafficStats`, `NetworkCapabilities.TRANSPORT_VPN`).
15. Trust: no-telemetry mode, exportable JSON config + QR, licence key via the phone page without Play Services.

Quick wins from complaints: 24 h clock toggle; A‑Z by label not package; load cards while scrolling; icon crop-to-fill;
cap preview programs per channel; never a splash logo after process death; "do nothing on first key after HDMI switch".

Risks: Google TV Streamer and several TCL/Hisense sets reboot or black-screen when launcherx/setupwraith is disabled —
keep "redirect, not replace" default; Fire OS 8 is a moving target; profile switching is Won't Fix.

## Appendix B — Chinese TV launchers, OEM UX, low-end performance, phone-remote ecosystems

### B.1 What matters most

1. #1 complaint in China = ads + slow cluttered home (JD 2025: 65.7 % want no boot ad/instant start, 60.5 % a simple UI
   parents/kids can use, 56.2 % one-tap casting; People's Daily: 72.7 % can't tolerate one second of boot ads).
2. ZNDS review rubric: look, freedom to add/reorder apps, wallpaper (incl. upload from phone), weather/clock, search,
   "clean memory". Emotn UI and Dangbei top every list; Sofa is "too complex for the elderly".
3. Elderly/kids *modes* are universal in China and nearly absent in the West: big fonts, 3–5 giant tiles, boot straight
   into live TV/HDMI, password-gated exit, time limits.
4. "Boot straight into X" (app or HDMI) is the most-loved power feature and the hardest to do (Xiaomi whitelists HOME,
   kills third-party apps 2 s after screen-off).
5. 1 GB / Mali‑450 performance is solved in principle: render nothing per frame that doesn't change, pre-bake blur/
   corners/gradients, animate only scale/alpha/translation, home PSS ≤ 60 MB, device-protected storage to draw before
   unlock. Measured: 187 → 59 MB and 34 → 5 ms median frame (cleanOS); 36–57 → 5–14 ms by pre-rendering the gradient
   window background (Tiny Launcher).
6. Phone-side table stakes in China: install APK from phone, type on phone, screenshot the TV, find-my-remote beep,
   remote assist (see/control the TV from a phone), phone-as-earphone, message board on TV, wallpaper upload by QR.

### B.2 Third-party launchers

**Dangbei Launcher (当贝桌面).** Huge clock+weather tile, 5 pinned apps, all-apps below, UP → settings. Wallpaper via
WeChat/USB/LAN. Numeric-key direct launch (0–9), folders, boot autostart, password lock (parental control at boot),
auto-clean on return home with whitelist. The one-tap "清理" button shows a random MB number — only auto-clean kills
processes. Praised: simplest, best for elderly; slim ad-free community forks have millions of views. Criticised: 5 home
slots, no direct reorder, later versions added feeds/ads.

**Emotn UI.** Clean, ad-free, weather+clock, static/video/time-of-day wallpapers, unlimited favorites, "release memory"
tile, LAN/USB upload, autostart. Western complaints: no icon resize/labels, two categories only, no widgets, token
glitches, privacy worries, no Continue Watching.

**Sofa Launcher (沙发桌面).** 10 themes incl. elderly (big tiles/font) and kids (cartoon icons), movie-poster screensaver,
auto categorization with first-letter index, auto memory clean on every app exit + scheduled with whitelist, parental
control, DLNA, LAN/SMB, hide apps ("small black room"), speed test, 0–9 launch. "Too complex for the elderly."

**Others.** Waterdrop Lite (<10 MB RAM, HDMI switch + clean in the top bar). Oxygen (content launcher — every tile
downloads). Kukan (push photos/videos/APKs from phone). **Aerial (open source, tvOS-style, closest to Aura):** glass dock,
lift-on-focus, folders, HDMI tiles, Apple aerials, phone web page via QR to reorder/rename/upload, boot-to-app/HDMI,
accessibility "home guard" with 0.5 s respawn after Xiaomi kills it, "smooth" tier auto-selected on weak TVs. Its device
docs are a goldmine on Xiaomi HOME whitelists and boot timings. Dangbei Market: batch uninstall, remote install from
phone/PC by typing the TV's IP, cloud backup of app list.

Recurring praise phrases: 开机无广告 (no boot ad), 适合老人 (good for the elderly), 干净 (clean), 不卡 (not laggy), 开机自启
(autostart), 信号源入口 (HDMI entry on the home — many third-party launchers lose it), WeChat QR wallpaper upload,
auto-clean on return home.

### B.3 OEM launcher patterns

| OEM | Distinctive |
|---|---|
| Xiaomi PatchWall / HyperOS TV | Standard/Kids/Elderly/Office modes from the pull-down bar; elderly: big fonts, inputs first, playback history; kids: per-session/daily limits, PIN or voiceprint lock; control center on Menu key; long-press HOME → task manager; "TV bar" with live PiP of a source; firmware kills all third-party apps 2 s after screen-off (whitelist); third-party HOME whitelisted by package name |
| Huawei/Honor Vision | NFC one-touch cast (no shared Wi‑Fi, 60 fps), 0.3 s cast latency; family tab: shared album, message board, video calls, remote assist (phone touches control the TV), remote home-watch, find remote (UWB), phone as touchpad/keyboard/speaker; kids distance/posture reminder; 2 s boot from standby |
| Hisense VIDAA | 2025 "Star Desktop": LEFT opens a customizable card drawer replacing the feed, half-screen mode over live content, AI reorders cards by usage; phone app: TV screenshot and TV audio to phone, reverse cast for the elderly |
| TCL/FFALCON Agile Desktop | No boot ads (brand promise); card desktop 1×1/1×2/2×2, per-member scene pages (Explore/Movies/Elderly/Kids/custom), a card per HDMI port, music card with lyrics, time-linked wallpapers dawn→night, WeChat QR upload, left drawer with search/tools, right notification center, 3 custom remote keys |
| Skyworth Coocaa | Big-font/high-contrast options, 3 custom remote keys, quick-launch bar (3 apps), one-tap accelerate, "boot start home ON/OFF" (boot to a set-top box) |
| OPPO ColorOS TV | No boot ads; UP from home → search/USB/source/kids/cast; side settings panel from any screen; NFC cast, 4-way cast, screen-off cast, privacy filter; TV as Bluetooth speaker |
| Konka YIUI | Face recognition "child too close" pause, remote parental control |

Patterns that translate globally: modes/profiles switchable from a top bar with per-mode layouts and PIN to leave Kids;
card desktop with per-person scene pages and HDMI as first-class tiles; quick-settings side panel from anywhere; boot
behaviour as a user choice (home / last input / app); phone companion beyond a d-pad; three custom remote keys;
time-of-day wallpapers.

### B.4 Fifteen low-end performance techniques (plain Java, minSdk 22)

Google's 1 GB TV guidance: total ≤ 280 MB, anon+swap+graphics ≤ 200 MB, graphics 30–40 MB, never decode above UI
resolution (720p UI on 1 GB). Stock Google TV home redraws 60×/s idle (43 % of a core + 32 % SurfaceFlinger on a 2 GB
MT9632); a bare GridView launcher idles at 0 % / 39 MB PSS.

1. Zero-redraw idle: nothing invalidates without input; clock once a minute via `postAtTime`; verify with
   `dumpsys gfxinfo <pkg>` over 60 s (target 0 frames).
2. Pre-render static content into one bitmap as the window background (dim/vignette baked in).
3. Fake frosted glass: blur the wallpaper once off-thread (downscale 1/8, upscale), cut to the dock shape; never live-blur.
4. Bake rounded corners and shadows into tile bitmaps at on-screen size (Mali clips `clipPath` without AA).
5. Focus = scale/alpha/translationZ only; never width/height/padding; transient hardware layer for the animation.
6. One moving focus frame, not N overlays.
7. `RGB_565` for opaque wallpapers/posters, `inSampleSize` to view size, `inBitmap` reuse, `Config.HARDWARE` on 26+,
   LruCache sized by `getMemoryClass()`/`isLowRamDevice()`.
8. Overdraw ≤ 2×: no covered backgrounds, no translucent full-screen layers, `windowBackground` = the wallpaper.
9. Focus-safe lists: center-scroll, never `notifyDataSetChanged()`, no item animator, manual pagination.
10. Cold-start ordering: nothing heavy in `Application.onCreate`; first frame from cached layout/icon atlas; PM on a
    background thread; network via `IdleHandler`; TTID 500–800 ms.
11. Device-protected storage for boot (`directBootAware`, `createDeviceProtectedStorageContext()`); 6.4 s blank
    otherwise on FBE TVs.
12. `isLowRamDevice()` tiers auto-selecting a "smooth" profile.
13. `onTrimMemory` + LMK awareness: drop atlas/previews on `UI_HIDDEN`; home sits at adj 600; keep PSS ≤ 60 MB so
    Netflix/YouTube stay cached (1 s vs 10 s cold start).
14. No per-frame allocations.
15. Measure on device: `am kill-all` → idle 60 s → `top -d 60`, `dumpsys gfxinfo framestats`,
    `dumpsys meminfo --package`, GPU overdraw bars; on Xiaomi `logcat -s MitvConfigParser` after boot for HOME whitelists.

HOME takeover without root (Aerial/quiet-tv): try `cmd package set-home-activity`; if refused, accessibility "home
guard" brings yours back (~0.5 s); key filtering only while your UI is foreground; never disable `com.mitv.tvhome`.
Boot/standby: wait ~1.5 s after boot/wake; don't take over if an HDMI/CEC source came to the front; Xiaomi gates
third-party start ~4.6 s after screen-on; `BOOT_COMPLETED` is for services, not activities; standby wake doesn't resend it.

### B.5 Phone-remote ecosystems

Wukong Remote: remote IME (type on phone → TV, 700 KB TV side), TV screenshot to phone, cloud-drive cast; rides ADB over
TCP on Xiaomi. Dangbei Home: find-my-remote beep, "magic same-screen" (TV mirrored to phone + reverse touch), remote
screenshot ("home watch"), message board, WeChat mini-program. LeBo: de-facto casting receiver, 6-digit cast code
pairing. Huawei Smart Life: remote assist, photo wall, find remote, mode switch, boot-source settings. Hisense: audio to
phone, reverse cast. Technical pattern: ADB over TCP (`input`, `am start`, `pm install`, `screencap -p`, `ime set`) —
the channel Aura already has; the TV-side IME trick is a tiny InputMethodService receiving text over LAN.

### B.6 Fifteen "shock" features ranked (A = app APIs · B = Aura's existing privileges · C = ADB link · D = system only)

| # | Feature | Feasibility |
|---|---|---|
| 1 | Boot straight into any app or HDMI input, incl. wake from standby | B/C |
| 2 | Type on phone → text on TV (tiny IME + `ime set` via ADB; fallback accessibility `ACTION_SET_TEXT`) | B/C |
| 3 | Install APK / drop files from the phone browser | B/C |
| 4 | Remote assist: see the TV on the phone and tap to control (screencap 1–2 fps + key injection) | C |
| 5 | Simple/elderly mode: 4–6 giant tiles, big clock, boot to live TV, PIN to leave | A |
| 6 | Kids mode with time limits + PIN (allow-list, timer overlay, UsageStats, accessibility block) | A/B |
| 7 | Quick-settings side panel from anywhere (long-press Menu/HOME) | B/C |
| 8 | Find my remote (BT FindMe alert, "if supported") | B/C |
| 9 | Real "return home = free memory" with whitelist and true before/after MB; never kill a playing media app | C |
| 10 | Numeric/colour-key direct launch + 3 custom keys | A/B |
| 11 | Upload wallpaper/photos by QR → photo-wall screensaver | B |
| 12 | TV audio to phone at night | D (system audio); only Aura's own media |
| 13 | Message board / family notes on the TV | B |
| 14 | HDMI tiles (A) / live PiP preview of a source (D on most OEMs) | A/D |
| 15 | Screen-off music / ambient dim with now-playing (black overlay + brightness 0 via ADB) | A/C |

Honorable mentions: time-of-day wallpapers, scene pages per family member, usage-time reminders, clear app caches,
network speed tile, auto-categorize new apps into folders.

## Appendix C — AOSP / Google TV internals, platform APIs, low-RAM, tvOS motion, TV quality rules

### C.1 Leanback internals worth copying

1. **Prefetch disabled on low-power chipsets**: `GridLayoutManager` calls `setItemPrefetchEnabled(false)` ("prefetch
   causes regression on low power chipset"); nested-row prefetch centres on the focused position. → Pre-bind ±1–2 cards
   around focus in `postOnAnimation`/idle handler, skip on `isLowRamDevice()`.
2. **Pending-DPAD-move scroller**: counts unhandled presses (cap 10), consumes them while the scroll runs, min 30 ms per
   window width, `DecelerateInterpolator`, focus set once at the end. → Keep `pendingMoves` per row and retarget the
   running animation instead of starting a new one.
3. **Keyline scrolling + focused child drawn last**: `FOCUS_SCROLL_ALIGNED`, `getChildDrawingOrder` 0 1 2 3 9 8 7 6 5 4.
   → `setChildrenDrawingOrderEnabled(true)`; translate the row, never re-layout cards.
4. **Focus pop on a `TimeAnimator`**: 150 ms, `AccelerateDecelerateInterpolator`, only scaleX/Y + Z + overlay dim; zoom
   106/110/114/118 %; elevation shadow on L+, static nine-patch before. No `requestLayout` in the hot path.
5. `BaseGridView.setHasOverlappingRendering(false)` → override `hasOverlappingRendering()` → false on image/text-only
   containers.
6. Field numbers: launcherx 183 MB PSS, 60 fps idle, 43 % core + 32 % SurfaceFlinger; Projectivy effects off 6 % CPU,
   0 redraws/s; a Compose launcher cut median frame 101 → 44 ms by removing live blur/glow, caching backdrops, AOT.

### C.2 Platform APIs

- **Watch Next** (API 26): `COLUMN_WATCH_NEXT_TYPE` (CONTINUE 0, NEXT 1, NEW 2, WATCHLIST 3), `LAST_ENGAGEMENT_TIME`,
  `INTERACTION_TYPE/COUNT`, `PREVIEW_VIDEO_URI`, `LOGO_URI`, `POSTER_ART_ASPECT_RATIO`, `LAST_PLAYBACK_POSITION_MILLIS`,
  `DURATION_MILLIS`, `BROWSABLE`, `SEARCHABLE`, `WEIGHT`. With `READ_TV_LISTINGS` you see your rows + `searchable`
  rows; any `selection` → `SecurityException` (filter in Java); always pass an explicit projection (Philips TPM191E
  default projection → `SQLiteException`). Fire OS: provider does not exist.
- **TIF/CEC**: `HdmiControlManager` is `@SystemApi` (`signature|privileged`) — always `SecurityException` for Aura.
  Use `TvInputManager.getTvInputList()`, `isPassthroughInput()`, `getParentId()` (CEC devices are child inputs of the
  port), `loadCustomLabel()` (24), `getInputState()`, `registerCallback(TvInputCallback)`; switch via
  `TvContract.buildChannelUriForPassthroughInput(id)`. Empty ports report "standby" on Realtek — only named CEC children
  count as connected.
- **MediaSessionManager**: `getActiveSessions(listener)` needs an enabled NotificationListener; `getSessionActivity()`
  PendingIntent to jump back; the system Now Playing card only shows sessions with `FLAG_HANDLES_TRANSPORT_CONTROLS` —
  Aura can show all.
- **Settings deep links that resolve on TvSettings**: `WIFI_SETTINGS`, `APPLICATION_DETAILS_SETTINGS`,
  `MANAGE_ALL_APPLICATIONS_SETTINGS`, `INTERNAL_STORAGE_SETTINGS`, `DATA_SAVER_SETTINGS`, `LOCATION_SCANNING_SETTINGS`,
  `SECURITY_SETTINGS`, `SETTINGS`. `BLUETOOTH_SETTINGS` does **not** resolve on Android TV; pair remote =
  `com.google.android.intent.action.CONNECT_INPUT` (extra `no_input_mode`). Fire TV components:
  `com.amazon.tv.settings/.tv.network.NetworkActivity`, `/.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity`,
  `/.tv.applications.ApplicationsActivity`; Fire OS 7/8 package `com.amazon.tv.settings.v2`; fallback
  `com.amazon.tv.launcher/.ui.SettingsActivity`. Always `resolveActivity()` first.
- **ROLE_HOME**: `isRoleAvailable(ROLE_HOME)` commonly false on TV; `ACTION_HOME_SETTINGS` doesn't resolve; HOME by
  intent-filter priority (see A.4). Shell: `cmd package set-home-activity --user 0 app.aura.home/.HomeActivity`;
  verify `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`.
  Some remotes send `KEYCODE_ALL_APPS` (284) instead of HOME.
- **Overlay**: `ACTION_MANAGE_OVERLAY_PERMISSION` often has no handler; low-RAM builds disable overlays and hide the
  notification-access screen; `appops set app.aura.home SYSTEM_ALERT_WINDOW allow`,
  `cmd notification allow_listener <component>`.
- **AppWidgetHost**: no bind/pick UI on TV since 5.1; `appwidget grantbind --package app.aura.home --user 0`.
- **Display**: `preferredDisplayModeId` + `Display.getSupportedModes()` (23); `Surface.setFrameRate` (30/31);
  `DisplayManager.getMatchContentFrameRateUserPreference()` (31); ALLM `setPreferMinimalPostProcessing` (30);
  HDR `getHdrCapabilities()` (24); window blur `setBackgroundBlurRadius`/`FLAG_BLUR_BEHIND` (31) needs a translucent
  floating window, `isCrossWindowBlurEnabled()`; AOSP guidance blur-behind ≈ 20 px, background ≈ 80 px.
- **Remote keys**: `KEYCODE_TV_INPUT` 178, `KEYCODE_GUIDE` 172, `KEYCODE_SETTINGS` 176, `KEYCODE_ALL_APPS` 284 —
  delivered as `GLOBAL_BUTTON` only to system apps; third parties see them via the accessibility key filter.
- **Launch animation**: `ActivityOptions.makeClipRevealAnimation(card, 0, 0, w, h)` (23).

### C.3 Performance on 1–1.5 GB

- 1 GB → 720p UI + 1080p video; 1.5 GB → 1080p UI + 4K video; both `isLowRamDevice()`. Foreground app targets on 1 GB:
  anon+swap < 160 MB, graphics 30–40 MB, total ≤ 280 MB. Android TV 12+ can render UI at 4K (4× fill).
- Home app adj 600 (below `PREVIOUS_APP_ADJ` 700) but still killable; smaller PSS is the only protection. No
  foreground service; release caches in `onTrimMemory(UI_HIDDEN)`; keep services in the same lean process.
- **AOT without baseline profiles**: API 22–23 `speed` at install; 24–27 interpret+JIT; 28+ `verify` at install and
  `speed-profile` ≡ `verify` with no profile → `cmd package compile -r bg-dexopt` is useless. Shell can force
  `cmd package compile -m speed -f app.aura.home` (24+) or `-m speed-profile -f` after use (ART writes
  `/data/misc/profiles/cur/0/<pkg>/primary.prof`). Verify `dumpsys package dexopt | grep -A2 app.aura.home` →
  `status=speed`. Never `vmSafeMode`.
- Cold start: `windowBackground` = first-frame colour/gradient; no `windowDisablePreview`, no `windowIsTranslucent`;
  `setBackgroundDrawable(null)` after the opaque backdrop draws.
- Bitmaps: `inBitmap` pooling (`getAllocationByteCount()`), `reconfigure()`, RGB_565 for opaque, `Config.HARDWARE`
  (26; can't `getPixels`, no software canvas, no `LAYER_TYPE_SOFTWARE`) for display copies.
- No `largeHeap`; size caches from `getMemoryClass()`; no per-frame allocations.
- GPU: ≤ 2.5× fill; alpha < 1 on overlapping views = offscreen buffer; `forceHasOverlappingRendering(false)` (24);
  `animate().withLayer()` only for the animation; `setFormat(RGB_565)` does not help HWUI; `RenderNode` (29),
  `RenderEffect.createBlurEffect` (31).
- Text: `PrecomputedText` (28), `HYPHENATION_FREQUENCY_NONE` + `BREAK_STRATEGY_SIMPLE` (23).
- Idle must be free: `ValueAnimator.areAnimatorsEnabled()` (26); `Choreographer.postFrameCallback` one-shot only.

### C.4 tvOS-like motion in plain Java

- **Closed-form spring** (Launcher3 `SpringAnimationBuilder`, Apache-2.0, ~150 lines on `ValueAnimator`): ω = √k,
  β = 2ζω, γ = ω√(1−ζ²), a = x0−target, b = βa/(2γ) + v0/γ; x(t) = e^(−βt/2)(a cos γt + b sin γt) + target; duration by
  stepping π/γ until velocity < threshold then bisecting. Constants: stiffness LOW 200 / MEDIUM 1500 / HIGH 10000;
  damping MEDIUM_BOUNCY 0.5 / LOW_BOUNCY 0.75 / NO_BOUNCY 1. Use ζ≈0.6 k≈800 for focus, ζ≈0.9 k≈400 for row settle.
- Focus pop: 150 ms, 110–114 %, translationZ bump a few dp, focused child drawn last; TV theme shadow alphas 0.15
  ambient / 0.30 spot; don't animate the outline; static 9-patch shadow on low-RAM.
- Parallax: `setRotationX/Y` with `setCameraDistance(1900 × density)`; ≤ 2 stacked layers, base opaque; disable on low-RAM.

### C.5 TV quality rules

TV-LB banner 320×180 + 160×160 icon; TV-DP D-pad reachability with visible focus; TV-DM no Menu dependence; TV-DB Back
leads toward Home; TV-TR opaque full-screen background; TV-NP Now Playing card for audio apps. Overscan 48 dp sides /
27 dp top-bottom. Material-TV type: body 16 sp, title 22 sp, headline 32 sp; keep ≥ 14 sp at 1080p.

### C.6 Top 15 actions for smoothness on 1–1.5 GB TVs

1. Zero idle redraws (measure with `dumpsys gfxinfo` deltas). 2. AOT via the ADB link (`compile -m speed -f`). 3. Kill
overdraw in the glass stack (one pre-blurred opaque backdrop + one translucent fill; `hasOverlappingRendering=false`).
4. Byte-bounded bitmap pool, RGB_565 opaque, 720p decode on 1 GB, `HARDWARE` copies on 28+. 5. Adaptive prefetch
around focus. 6. Pending-DPAD-move scroller. 7. Property-only focus animation on `TimeAnimator`. 8. Closed-form
spring. 9. Transient hardware layers only. 10. `PrecomputedText` + no hyphenation. 11. Launch theme
`windowBackground` matching the first frame. 12. Low-RAM gates (no preview video, no aerials, static shadows, no tilt,
720p blur). 13. Media-session continue-watching primary, Watch Next additive. 14. TIF inputs with CEC children; drop
`HdmiControlManager`. 15. Verified deep links; `set-home-activity` + `resolve-activity`; filter `KEYCODE_ALL_APPS`.

## Appendix D — tvOS fidelity: focus engine, Home anatomy, Control Center, materials, type, motion

Conventions: 1 Aura dp (960×540 canvas) = 2 tvOS pt (1920×1080 @1x).

### D.1 Focus engine (WWDC 2015/16/17)

Exactly one focused item; only the engine moves focus (`preferredFocusEnvironments`, `canBecomeFocused`). Candidate =
closest focusable inside a rectangle projected in the swipe direction (diagonal supported, off-screen included, hidden
excluded). Speed scaling: fast swipes skip items. Scrolling keeps the focused item fully visible (no forced centring).
Initial focus = top-left unless preferred chain says otherwise; `remembersLastFocusedIndexPath`;
`restoresFocusAfterTransition` default on. Focus guides bridge gaps so swipes never dead-end. Never move focus without
user input except when the focused item disappears (then to a neighbour within one step). Menu/Back returns focus to
the tab bar.

Five states: unfocused (no label), focused (scale, closer, deeper shadow, specular, label appears, parallax),
highlighted/pressed (presses in then releases), selected (shaded background), disabled (ghosted). Numbers: scale ≈ 1.10
(Apple's effective scale 1.07–1.14 by item size — bigger scales less); parallax bleed ~6.5 %; community recreation:
shadow black 30 % offset (0,16 pt) radius 25 pt, parallax shift ±4 pt tilt ±10° (HIG says "almost unnoticeable" — err
lower); specular = masked blurry white circle that pans. Idle: unfocused content dims, focused expands.

Timing: durations scale with swipe speed; off-screen targets wait until fully visible; **focusing is quicker and more
prominent, unfocusing slower and subtler** (visible trail). Android tv-material: 300 ms focus / 500 ms unfocus / 120 ms
press, easing (0,0,0.2,1). Sound: every move; faster = quieter; stereo-panned by x; bigger items = lower pitch.

### D.2 Home screen anatomy

Safe area 60 pt top/bottom, 80 pt sides → **30 / 40 dp**. Grid widths (40 pt gaps, ≥ 100 pt vertical): 2-col 860,
3-col 560, 4-col 410, 5-col 320, 6-col 260, 7-col 217, 8-col 184, 9-col 160 pt → Aura 6-col = **130 dp**, 20 dp gaps,
≥ 50 dp row pitch. Dock = 6 icons (5 in "Large"); icons 5:3 (400×240 pt). Top Shelf ≈ top half while a dock app is
focused: static 2320×720 pt (flipped/blurred fill), carousel actions/details (video + Play/More Info), sectioned content
row (poster 404×608, square 608×608, 16:9 908×512 pt), scrolling inset banner (1940×692, 3–8 images, auto-advance until
focused). Moving focus down dims/recedes the shelf. tvOS 26: translucent glass dock refracting the backdrop; most
shelves moved to 2:3 posters; Continue Watching = 16:9 with a thin white progress bar flush at the bottom. Tab bar 68 pt
tall at 46 pt from top → **34 dp at y = 23 dp**; translucent, only the selected tab opaque; pinned above split views.

### D.3 Control Center, notifications, Settings

Control Center: long-press TV button; since tvOS 17 a top-right anchored panel that expands from a pill (profile + time
at rest). Horizontal tabs: Profile, Controls, then conditional AirPods/Audio, Home, Now Playing, Calls, VPN. Controls:
Sleep, Wi‑Fi (name), Audio output, Do Not Disturb, Sleep Timer, Game Controller, Accessibility, Restrictions, Search.
tvOS 26 clear glass hurt legibility over busy frames — use **regular** glass for text-heavy panels.

Notifications: no notification centre; badge on the app icon; HomeKit camera = PiP top-right (TV button expands, Back
dismisses); calls/AirPods = small top banner. Research: toast in a top/bottom-right corner then a persistent small icon;
never cover subtitles.

Settings: split view primary 1/3, secondary 2/3; one title; focused row highlights, slightly scales, rounded corners;
grouped table, label left / value right, footer explanations; left list + right explanation/preview is the pattern.

### D.4 Liquid Glass and materials

Two classes: Liquid Glass (floating functional layer: tab bars, sidebars, Control Center, Top Shelf) and standard
materials (ultra-thin/thin/regular/thick, content layer). Never glass in the content layer; never glass-on-glass; on top
of glass use fills/vibrancy. Variants: Regular (adaptive, legible anywhere) and Clear (needs ≈ 35 % black dim over bright
content; only over media). Layers: edge lensing/refraction (primary cue, not blur), moving highlights, adaptive shadow,
tint + dynamic-range shift, scroll-edge dissolve, inner glow on interaction; glass "materializes" by ramping lensing not
alpha; larger glass is "thicker". Concentric corners (inner = outer − padding); capsules for controls; menus spring from
their trigger. Only Apple TV 4K 2nd gen+ gets it — Apple's cost control is hardware gating and few elements.

Measured: `.regular` veil α ≈ 0.72 warm-white (248,243,239), blur σ ≈ 5.4 pt; `.clear` veil ≈ 0.075, σ ≈ 1.4 pt; Control
Center backdrop σ ≈ 23 px; context menu dims to ×0.52; refraction band ≈ 0.6 × radius. Web approximations: saturate 1.8;
thin rgba(40,40,40,.5); regular rgba(22,22,23,.8); glass rgba(38,38,40,.6) + blur 16 px.

### D.5 Other premium TV UIs

webOS 24: Quick Cards grouping apps by category, removable rails, "seriously snappy". Tizen: Quick Menu for apps/inputs/
settings; home ads criticised. Roku: "respond immediately to every action", "never make the user wait on animations",
"carry a relevant element from one screen to the next", "one screen, one purpose". Sky Glass: warning against unstable
rail order. Netflix TV: feedback within 100 ms; complete within 1 s or show progress; user "settles" after 200–300 ms
→ then load art/metadata and prefetch; grey placeholders during fast navigation; never skip a key press for a repaint;
device performance classes; "consistent and smooth beats inconsistent and jerky even if faster". Google TV: focus by
scale 1.025/1.05/1.1, border 3 dp, glow 2–32 dp.

### D.6 Typography

tvOS styles (pt → Aura dp): Title 1 76/96 → 38/48; Title 2 57/66 → 28.5/33; Title 3 48/56 → 24/28; Headline 38/46 →
19/23; Body 29/36 → 14.5/18; Caption 1 25/32 → 12.5/16; Caption 2 23/30 → 11.5/15. Default 29 pt, minimum 23 pt; no
Ultralight/Thin/Light. Android TV overscan 48/27 dp; tv-material body 16 / title 22 / headline 32 sp; LG ≥ 20 px @1080p;
Fire TV ≥ 14 sp. Recommended Aura scale: display 38/46, title 28/34, heading 22/27, body 16/20, label 14/18, caption
12/15 (floor 11.5).

### D.7 Motion (WWDC23 springs, WWDC18 fluid interfaces)

Springs by perceptual duration + bounce: smooth 0.5 s / 0; snappy 0.5 s / 0.15; bouncy 0.5 s / 0.3; avoid > 0.4.
Conversion (bounce ≥ 0): mass 1, stiffness = (2π/d)², damping = 4π(1−b)/d. Springs keep position and velocity
continuous and retarget mid-flight; fire completions on perceptual duration. Principles: respond instantly, allow
interruption, spatial consistency (exit the way they entered; sheets spring from their trigger), amplify light input,
rubber-band at edges, no fixed focus timing.

Aura motion system: T1 120 ms press; T2 220 ms focus-in, unfocus 350 ms; T3 360 ms panel/sheet/tab; T4 600 ms scene.
Curves: spring(0.35 s, 0.15) focus-in/panel pop; spring(0.45 s, 0) sheets/settle/unfocus; cubic(0.2,0,0,1) one-shot;
linear for crossfades. Speed rule: `duration *= clamp(1 − 0.12 × movesInLast500ms, 0.5, 1.0)`; delay focus-in until the
target is fully on screen.

### D.8 25-item tvOS fidelity checklist

1 one focused element always; 2 closest-in-projected-rectangle candidate, diagonal, hidden excluded; 3 fast input skips
items and shortens animations ≥ 2× at 5+ moves/s; 4 focus-in : focus-out ≈ 1 : 1.6; 5 tile scale ≈ 1.10 (1.07 hero,
1.14 small) with two-layer shadow; 6 panning specular, tilt ≤ 6°, shift ≤ 2 dp; 7 press to ~0.95 in 120 ms, action on
key-up; 8 label only on focus for icon tiles; 9 scroll just enough to keep focus visible, spring settle; 10 Back restores
the exact tile; 11 focus guides bridge gaps; 12 focus sound speed/pan/pitch rules with mute; 13 safe area 40/30 dp (or
48/27); 14 6 columns 130 dp, 20 dp gaps, ≥ 50 dp pitch, symmetric peeking; 15 hero dims when focus moves to the grid;
16 idle dim after ~10 s; 17 tab bar 34 dp at y 23, selected opaque capsule; 18 type floor 11.5 dp, body 14.5–16, no
light weights; 19 glass only on the floating layer, never glass-on-glass; 20 regular glass σ 8 dp tint .6–.7 sat 1.8
for text panels, clear σ 3 + 35 % dim over video; 21 panels emerge from and return to their trigger; 22 concentric radii,
capsule controls; 23 focus feedback < 100 ms decoupled from data, data after 200–300 ms settle; 24 placeholder instantly,
200 ms fade only on async loads, prefetch neighbours; 25 every animation interruptible.

### D.9 Redesign specs (960×540 dp)

**Categories.** Category tab bar at y 23, 34 dp tall, centred, glass-regular; items 16 dp Medium, 16 dp padding;
selected = white capsule, black text, shadow when focused. From y 84: section title 22 dp at x 40; 6-col grid 130×78 dp
(5:3), 20 dp gaps, pitch 128 dp; "Large" mode 5-col 160×96. Backdrop crossfades (T4) per category; blur 8 → 16 dp while
the tab bar holds focus. Tile focus 1.10, label rises 6 dp (T2); row title 70 → 100 % white. Up from row 1 → tab bar via
a full-width focus guide. Tab switch: grid fades 120 ms + slides 12 dp, new grid springs in (T3). Empty: 320×120 glass
card with Headline + hint.

**Control Center + notifications.** At rest: 36 dp pill top-right (right 40, top 23): avatar 24, time 16 dp, ≤ 3 glyphs.
Expanded: pill morphs (spring 0.35/0.15, origin top-right) into 420×260 dp panel, radius 24, glass-regular, screen dims
25 %, backdrop blur +8. Row 1: 5 circular 40 dp tabs (Profile · Controls · Audio/BT · Inputs · Now Playing), 12 dp gaps.
Rows 2–3: 4×2 tiles 88×72 dp radius 16 (concentric 24−8), 10 dp gaps, white 12 % fill (20 % focused — **not** glass on
glass), icon 28, caption 12: Sleep, Wi‑Fi (name), Audio, DND, Sleep Timer, Controller, Accessibility, Search. Now Playing
tab: art 96×96, title 16/artist 14, 40 dp capsule transport group, 4 dp progress. Tile focus 1.05 + brighten (T2); tab
switch crossfade 150 ms + 10 dp slide; Back reverses the morph (T3); edge rubber-band 4 dp. Notifications: badge 20 dp
capsule top-right of a tile; transient banner 320×64 glass-regular under the pill (icon 32, title 14, body 12), slides
down 16 dp + fade (spring 0.45/0), auto-dismiss 4 s, never over the bottom 20 % (subtitles), stack ≤ 2, 2 dp time line;
rich events (camera/cast request) 240×135 PiP card, Select expands, Back dismisses.

**Settings (left list + right live preview).** Title 28 dp at (40, 30). Left list x 40, width 300 (1/3), rows 44 dp, 8 dp
gaps: icon 24 + label 16 left, value 14 at 70 % right; focused = white capsule radius 22 with black text, 1.02 scale,
8 dp shadow; selected-unfocused white 15 %. Section headers 12 dp uppercase 60 %. Right pane x 380, width 540: preview
card 540×304 (16:9, radius 20) rendering the live result of the focused row; footer Body 16/20 ≤ 3 lines at 70 %;
optional value control (segmented capsule/slider) reachable with Right. Preview updates after 150 ms settle with 180 ms
crossfade + 8 dp rise; Back from pane → list row; Back from list pops (slide 24 + fade, T3). Every row has a one-line
explanation ≤ 90 chars; destructive rows red with a confirm sheet springing from the row.

**Previews / For you.** Hero 960×330 full-bleed; video/aerial autoplays 1 s after settle; block at (40, 190): eyebrow
12 dp uppercase, title 38 dp, synopsis 16 dp, two 44 dp capsule buttons (Play · More Info). Left/right swaps 3–8 hero
items, auto-advance 8 s until focused, 6 dp dots. Scrim black 0 → 70 % over the lower 45 %; hero dims to 60 % + blur 6
when focus moves to rows. Row 1 "Continue": 16:9 196×110 radius 10, 20 dp gaps, 3 dp white progress flush bottom, title
14 + meta 12 below (on focus only). Row 2 "Genres": 160×72 glass-regular chips with genre gradient, 16 dp label. Rows
3+: 2:3 posters 130×195 or 16:9 196×110; Top 10 with 48 dp numerals. Cards 1.10, 12 dp shadow, specular; hero backdrop
follows the focused card after 300 ms (T4). Row titles 22 dp at x 40; rows peek 20 dp; pitch = card height + 70. Prefetch
±3 cards; neutral placeholder instantly; 200 ms fade only for network loads.

### D.10 Unified tokens

```text
DUR_PRESS 120 · DUR_FOCUS_IN 220 · DUR_FOCUS_OUT 350 · DUR_PANEL 360 · DUR_SCENE 600
DELAY_SETTLE 250 · DELAY_IDLE_DIM 10000 · speedFactor = clamp(1 − 0.12·movesLast500ms, 0.5, 1)
SPRING_SNAPPY (0.35 s, 0.15) · SPRING_SMOOTH (0.45 s, 0) · SPRING_PRESS (0.20 s, 0)
EASE_DECEL cubic(0.2,0,0,1) · EASE_STANDARD cubic(0.4,0,0.2,1) · EASE_LINEAR
FOCUS_SCALE_TILE 1.10 · HERO 1.06 · ROW 1.02 · PRESS_SCALE 0.95
PARALLAX_SHIFT 2 dp · TILT 6° · LAYER_RATIO 1.0/0.6/0.3 · LABEL_RISE 6 dp
SHADOW_REST y2 b4 α.20 · FOCUS y8 b12 α.35 + y2 b4 α.20 · PANEL y12 b24 α.45 · PRESS y4 b8 α.30
SPEC radius 0.7×w, white α 0→.22, tracks (t, dpad), additive
GLASS_REGULAR_DARK blur 8 tint rgba(22,22,23,.62) sat 1.8 edge 1 dp white α.18 top/.06 bottom
GLASS_REGULAR_LIGHT blur 8 tint rgba(248,243,239,.72) sat 1.6 edge α.35
GLASS_CLEAR blur 3 tint rgba(255,255,255,.08) sat 1.8 + DIM .35 when backdrop luma > .45
MATERIAL_THIN blur 16 rgba(40,40,40,.50) · MATERIAL_THICK blur 24 rgba(45,45,45,.88)
BACKDROP_BLUR_HOME 8 → 16 (panel/tab focused) → 24 (Control Center/sheets)
SCRIM_PANEL black .25 · SCRIM_HERO 0 → .70 over bottom 45 %
R_TILE 10 · R_CARD 16 · R_PANEL 24 · R_CAPSULE h/2 · BORDER_FOCUS 2 dp white α.9 (dark art only)
DIM_UNFOCUSED_IDLE .70 · DIM_HERO_WHEN_GRID .60 + blur 6
```

Caveat: Apple publishes layout, type, safe area, Top Shelf, tab bar, material variants and spring values plus the
qualitative focus rules; it does not publish the focus scale, focus durations, parallax amplitudes or glass blur/tint —
those are community-measured or derived and must be tuned on a real TV from ~3 m.
