# P7 smart climate offline audit (2026-10-26)

Scope: static inspection only, using the previously saved stock climate APK at
`/private/tmp/xplay-hvac-inspect/hvac.apk` (SHA-256
`c7310dc1dc994aa01f61e4f7acee124b1aa98a557aebd56e44bb0cf381cb397a`).
No vehicle connection, actuation, installation, or UI validation occurred.
This note supplements the historical parity audit; finding an exported entry
does not establish that XPlay can call it successfully on the vehicle.

## Smart passenger airflow

The current `P7ClimateWorker` maps `SMART_PASSENGER_ON` to
`setHvacEavPsnWindMode(5)` and reads `getHvacEavPsnWindMode`. This controls
Intelligence **wind direction**, not the stock smart passenger airflow feature.

The stock feature uses `IHvacController.setHvacSingleMode(boolean)` and
`isHvacSingleModeEnable()`. `HvacController.setHvacSingleMode` persists the
function-model flag and publishes `onHvacSingleModeChanged`. The stock
`HvacSmartControl` workflow considers passenger occupancy, ignition, rapid
cooling, deodorizing, and delayed outlet closure. Simply replacing the wind
mode with a similarly named vehicle setter would bypass this workflow.

An alternative external entry exists in the saved APK:

- Exported provider: `content://com.xiaopeng.carhvac.api.publisher`, requiring
  `android.permission.LAUNCH_RESTRICTED_COMPONENT`.
- The provider's `call` looks up an API manifest and returns a Binder plus its
  manifest; it does not itself execute a climate command.
- Published service: `com.xiaopeng.carhvac.HvacSpeechObserver`, with `onEvent`
  and `onQuery`. Its constructor selects the stock `HvacControlSpeechModel`.
- Stock speech commands: `command://ac.intelligent.psn.on` and
  `command://ac.intelligent.psn.off`. The model has explicit handlers
  `openIntelligentPsn` / `closeIntelligentPsn` calling
  `setHvacSingleMode(true/false)`.
- Stock query dispatch recognizes `ac.intelligent.passenger.status`.

These are candidates for a future bounded bridge through the existing ADB
worker. Provider permission, Binder parameter serialization, asynchronous query
callback delivery, and actual state/actuation must be verified before enabling
this route. The existing `IPipeBus` / `HvacManagerServer` interface used for
deodorizing does not expose the single-mode methods in this APK.

## Smart deodorizing countdown

The stock `HvacSmartControl` has a 180-second deodorizing sequence, start-time
storage, countdown updates, and restoration logic. Its controller exposes
internal `getHvacDeodorantCountDownTimer()` and
`onHvacDeodorantTimerChanged(int)`. The stock smart-controls UI
(`pt0.S0`, code address `223658` in the saved disassembly) displays the remaining
integer followed by `s` while HVAC power and deodorizing are on and the remaining
value is positive.

XPlay currently reads only `isHvacDeodorantEnable` via the stock
`HvacManagerServer` IPC. That generated server's dispatch in the saved APK does
not expose the countdown getter or callback. The logic-event name
`logic://hvac.mode.deodorant.timer` exists, but its presence alone does not prove
external query/subscription access. The speech API publisher found above is
another channel, not evidence that it publishes the countdown.

Do not start a fabricated 180-second timer when the overlay opens or when an
already-active state is first observed: deodorizing may have started elsewhere,
may be restored, or may end early. Prefer the OEM remaining value if a supported
external route can be confirmed.

## Evidence locations in the saved offline disassembly

`/private/tmp/xplay-hvac-inspect/hvac2-dex.txt`:

- `HvacController.setHvacSingleMode`, code `1ffc04`;
  `HvacSmartControl` source alias `lv0`, especially `u(boolean)`.
- `HvacSpeechObserver_Manifest`, manifest string at `23af98`;
  `ApiPublisherProvider.call`, code `23aa28`.
- Speech model alias `nn0`: command strings at `20dc78` / `20ea5c`,
  `closeIntelligentPsn` at `20c530`, `openIntelligentPsn` at `2141d8`;
  status-query string at `210e12`.
- `HvacSmartControl` sequence/restoration uses 180 seconds at `22db38`;
  `HvacController.getHvacDeodorantCountDownTimer`, code `1fb1f4`;
  `handleHvacDeodorantCountdownTimer`, code `1fd384`.

## Implementation following the audit

The worker now sends the explicit smart-passenger on/off speech commands and
queries the stock single-mode status. The app exposes a narrow API-router
callback provider; both provider discovery and its Binder verify the stock HVAC
UID. An opaque session token, query timestamp, freshness limit, and ordering
check reject expired or previous-session callbacks. Missing permissions or
callbacks leave its state unknown; there is no fallback to EAV wind
direction, optimistic enabled state, or write replay.

The countdown UI is explicitly an **estimate**, not OEM remaining-time readback.
It starts only after an XPlay start request made from an observed off state is
followed by an observed on state within three seconds. It uses the stock
180-second duration and monotonic elapsed time. Unknown/off state or a gap of
more than three seconds in observation clears it. An already-active sequence
observed after opening the app does not get a fabricated start time. Expiry of
the estimate neither sends OFF nor claims that the OEM sequence completed.
All eight existing climate locales include the estimate label.

The app builds with `./gradlew :mobile:assembleDebug`. No unit tests, CI, Lint,
emulator visual checks, vehicle deployment, or physical verification were run.
Runtime validation remains necessary: compilation does not verify OEM IPC
permissions, callback routing, or physical airflow. Exact OEM countdown readback
remains unresolved.

## Follow-up for a grey smart-passenger button (2026-10-29)

The UI previously required a confirmed callback value before allowing any
smart-passenger action. This prevented explicit on/off commands whenever status
readback failed. With a live climate session, an unknown state now opens a choice
of explicit On and Off commands; it does not infer or optimistically change the
OEM state. Confirmed readback retains the existing one-tap toggle. A missing
climate session still disables the control.

The saved diagnostic report includes the latest provider/query stage and error
class, write acknowledgement, callback discovery/delivery, and readback status.
It excludes callback tokens, URIs, raw OEM data, and exception messages. These
diagnostics distinguish a command-access failure from a callback-access failure;
the cause of the vehicle's missing readback has not yet been observed. No vehicle
was connected during this follow-up, and no installation or actuation occurred.
