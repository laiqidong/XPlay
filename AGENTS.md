# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

For local changes, packaging and deployments, verify compilation/build only by default.
Do not run CI, unit tests, instrumentation tests or Lint unless the user explicitly requests them.
This replaces the previous requirement to run the Android CI command before reporting completion.

Build the affected application; for the main XPlay app use:

```sh
./gradlew :mobile:assembleDebug
```

For a standalone car build, use `:mobile:assembleStandaloneDebug` or
`:mobile:assembleStandaloneRelease` with the required local authentication/signing inputs.
Release builds must exclude the automatic `:mobile:lintVitalRelease` task unless Lint was explicitly requested:
`./gradlew :mobile:assembleStandaloneRelease -x :mobile:lintVitalRelease`.
Build other application modules only when affected by the change.
A documentation-only change does not require a build.

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.
The workflow in `.github/workflows/android.yml` remains available when explicitly requested;
this local policy does not disable GitHub-hosted workflow triggers.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` in
`common/src/main/java/com/shilapi/xcertplay/SettingsInformationArchitecture.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`, `（実験的機能）`.

### Add the code

1. To extend an existing card, add the control inside its builder method
   (for example `buildLocationSection`) and its `filteredSection(...)` block in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Add a builder method using `filteredSection(content, SettingsSection.X, …)` and register it
   in the `settingsSectionRenderer` map in `DiPlayActivity.kt`. `allSettingsSections()` delegates
   to `SettingsSectionRenderer`, which calls only the requested builders in category order.
   A plain `section(...)` call in a card builder MUST NOT be used: it bypasses section filtering.
4. A setting MUST NOT appear in two categories.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

P7 has no Overview category. Connection is the default page; its connection-method card
holds inline settings and the Connect action. Do not add a separate status card or hotspot
instructions action. Wide windows use the category rail;
narrow windows use a category choice row. Do not restore a separate Overview or quick settings.

### P7 connection and instrument scope

P7 has exactly three user-facing connection methods: car hotspot, existing Wi-Fi and USB.
The selected method shows its configuration inline in Connection; do not add a separate setup
page or expose Wi-Fi Direct/local-only hotspot there. Hotspot details use one summary row:
a popup Confirm only updates the draft, and an external Apply button appears only for changes.
Persist hotspot edits only through that Apply action. Save changes for the next connection
and use `markReconnectNeeded()` without dropping the running session.

Instrument and Lyrics are separate categories. Instrument only projects the independent
CarPlay map channel; lyrics belong to the floating overlay. Map preferences use
`InstrumentMapSettings` and preserve the existing storage migration.

### About, backup and language

About contains version/project information only. Backup and restore belong to Diagnostics.
Do not reintroduce direction comparison or vehicle-information reader/subscriber panels.
XPlay follows the head unit language, retiring old app locale overrides; do not add an app language selector.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every available P7 category MUST be reachable from the category rail or compact category selector.
   Overview and Vehicle are legacy entries, mapped to Connection and Navigation for P7.
5. UI and layout changes are visually checked by the user. Agents MUST NOT capture,
   open, inspect or review screenshots/images for UI validation, or start an emulator
   solely for visual checks, unless the user explicitly requests it.
   Verify compilation/build only by default and report that visual inspection is left
   to the user. This replaces the previous compact/full/Arabic screenshot requirement.
