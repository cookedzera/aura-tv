# Aura's tests

The real Aura, started on simulated Android TVs with [Robolectric](https://robolectric.org): no TV,
no emulator. Each test drives the app the way the remote does and fails on any crash.

| Test | What it does |
|---|---|
| `BootTest` | Android 7.1 (Fire OS 6's level): start with the boot screen, move around home, Control Center, leave and come back |
| `BootAndroid11Test` | The same on Android 11 with real drawing, and the quick start after Android closed Aura in the background |
| `WelcomeTest` | A fresh install on 7.1 and 11: the whole welcome, walked with OK, Right and Down |
| `PagesTest` | Settings → Widgets, Updates, Problem reports; the stack's hold-OK menu; the screensaver making room and home coming back |
| `PhonePageTest` | The phone's problem-report page: served, escaped, limited to the report, wrong links refused |
| `LogicTest` | The Smart Stack's learning (opened, passed over, turned down), the problem log's limits, version order |

Run them with `.\tools\test.ps1` (it builds first; `-NoBuild` to skip that), or after a build with
`mvn -f tests/pom.xml test`. Needs a JDK 17+ and Maven. The first run downloads Robolectric's
Android images (a few hundred MB, once).

What each run noted (start times, the problem report it ended with) is in `tests/target/*.txt`.
`SaneMatrix` is a test-only fix for Robolectric's own simulated `Matrix` on Android 7.1, which
otherwise keeps every operation forever.
