# Android test repair — 2026-10-15

## Baseline and result

The starting common-module report contained 1,347 cases and 132 failures.
The final common report contains 1,344 cases, zero failures, zero errors and zero skips.
The required AGENTS.md CI command completed successfully (`BUILD SUCCESSFUL in 2m 46s`).
Shared reports contain 1,429 cases with zero failures/errors and one environment-dependent skip:
`AirPlayPortSelectorTest.fallsBackWhenAnotherListenerOwnsTheWildcardPort` uses an existing
assumption because this host permits overlapping wildcard and loopback listeners.
No skip or ignore was added by this repair.

## Root causes and changes

- Switch accessibility announcements include both title and description. Keep those announcements;
  select controls through a keyed title tag instead of matching the complete announcement.
- Settings widgets and forms own their layouts. Use real dialog instances, form buttons and TimePicker
  views rather than deleted fields or assumptions about Android's stock dialog implementation.
- Explicit Settings entry opens Overview. Tests navigate to the category under test, and model a BYD
  device when testing BYD-only controls. A new test protects the non-BYD audience boundary.
- Keep phone image upload, its existing crop URI grants, cancellation and host-state cleanup covered.
  Do not restore the retired system document-picker entry to satisfy old UI tests.
- The upstream DiPlay update installer is retired. Its 13 historical installer tests are replaced with
  nine API 25/28/33 provider-isolation tests; two home tests now verify that cached upstream offers
  and background checks are retired. Together with the new non-BYD test this accounts for the
  three-case change in the total. No active installer entry was removed by this repair.
- Japanese translation checks exclude source strings explicitly marked `translatable="false"`,
  including localized copies of those intentionally untranslated strings.
- Align XML window/dialog colors with the current page palette. Fix foreground, button-label and
  switch-track contrast while keeping the existing backgrounds and existing contrast thresholds.
- Give custom dialog actions the standard Android button IDs. Compact/full, large-font screenshot
  checks verify the scrolling panel at the top and complete action visibility at the bottom.
- Fix two production vehicle-settings issues: show the cluster-song fallback based on navigation
  service availability; run a full vehicle-data check from the vehicle page's ADB-check button so
  pending settings can resume once readings become available.

## Verification boundaries

This run verifies source builds, JVM/Robolectric tests, generated layout screenshots and lint.
It does not establish real head-unit ADB, Bluetooth, iPhone association or CarPlay compatibility.
The emulator was disconnected when installation was attempted; the generated APK was not installed.
