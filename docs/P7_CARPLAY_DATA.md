# P7 CarPlay data

The existing P7 switches stay opt-in and apply at the next connection. Location sharing carries
GPS course through GPRMC; wheel speed and P/R/N/D use PASCD only when requested by the iPhone.
GPHDT, GPGSV, PAGCD and PAACD are deprecated in Apple's Location Information requirements and
are deliberately not advertised or sent. GPS course is not the vehicle's stationary heading.

## Battery and charging

The session-owned, read-only ADB Car API subscription also reads these properties:

| Property | ID | Interpretation |
| --- | --- | --- |
| VCU_BMS_SOCDISP | 557847042 | Displayed battery percent |
| VCU_DSTBAT_DISP_WLTP_FLOAT | 559944315 | Remaining WLTP range in km |
| INFO_EV_BATTERY_CAPACITY | 291504390 | Nominal usable pack capacity in Wh; static |
| EV_BATTERY_LEVEL | 291504905 | Remaining energy in Wh, when implemented |
| VCU_CHARGE_STATUS | 557847081 | E28 SDK enum: 2 = charging; invalid/unknown stays unknown |
| VCU_CHARGE_GUN_STATUS | 557847080 | E28 SDK enum: 1 = AC, 2 = DC; not proof of charging |

The property IDs and charge enums were checked against the locally captured E28 Car SDK.
Standard energy properties require an available status; an unsupported property is omitted.
The vendor's CHARGE_BATTCAP/DISCHARGE_BATTCAP counters are not used as remaining energy.
Maximum range is not extrapolated from remaining range and SOC. Minimum charge, connector power,
and manufacturer routing models are unknown unless explicitly supplied by a provider.

The EV sender supports actual minimum/current/maximum Wh, displayed SOC, charging status,
active connector, maximum electric range, manufacturer charging/consumption model JSON,
and supported connector power. JSON is checked for size, syntax and model_id; this is not
validation of the manufacturer's complete routing model or a claim that Maps accepts it.
P7 currently supplies only the fields in the table when available. No model or power is fabricated.
ActiveConnector is sent only while charging, and Chinese-market P7 reports GB/T AC/DC.

Battery/state properties are refreshed by authoritative Car API reads every 30 seconds because
on-change properties can legitimately stay unchanged. Dynamic battery readings expire after
90 seconds without a successful refresh; bridge loss clears all readings immediately.
Gear is refreshed once a second. Speed retains its requested 4 Hz subscription, with real-property
queries after 250 ms of callback silence on E28 on-change firmware. Both expire
after three seconds. An unchanged gear refresh does not erase buffered wheel speed.
Changing gear discards earlier samples, preventing D and R readings from being combined.

## Validation

Unit tests cover missing/invalid/stale data, Wh units, charge enum interpretation, AC/DC connector
selection, D/R transitions, iPhone requests and separate NMEA parameters. UI snapshots cover
compact/full Chinese and compact Arabic at 1.6 font scale and high density.

No P7 was connected during implementation. On the car, check the switches, reconnect, and compare
SOC/range against the instruments. Check charging separately while unplugged, plugged but waiting,
actively AC charging, actively DC charging and finished. Confirm unsupported Wh properties are
omitted. Reading/sending values alone does not prove that iPhone Maps uses them or enables EV routing.

References: the local Xcode CarPlay Simulator iAP2 message definitions and Android's
[VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds).


## GPS loss with drift protection enabled

The opt-in location drift protection setting now uses explicit Android GPS only.
It never promotes network or opaque fused coordinates into a satellite fix.
Missing updates for more than 3 seconds, invalid/poor accuracy, provider disablement,
or a non-available provider status make GPS unavailable. Satellite count alone is
not used as proof of a valid position.

While unavailable, the accessory sends requested GGA quality 0 / RMC status V
sentences once per second with empty coordinate fields. It does not refresh an old
coordinate or send zero coordinates as a real position. A poll of an already-sent
but still fresh GPS sample remains silent and is not treated as signal loss.
After loss, three distinct, consecutive, accurate and coherent GPS samples are
required to restore valid position output. A bad fix or long gap resets recovery.
Parking jitter filtering remains based on fresh vehicle speed.

Vehicle speed reporting remains independent and is sent only when requested and
available. No heading, gyroscope or accelerometer capabilities are fabricated.
This reports the vehicle receiver state to iOS; it does not command an iPhone GPS
switch. iOS fallback, map behavior, and underground accuracy require physical
CarPlay validation. The setting remains opt-in and applies on the next connection;
turning it off retains the existing default provider selection.


### Receiver measurements and recovery safeguards

After an explicit no-fix response at cold startup, restoration also requires three
distinct reliable GPS updates. Impossible sample ages are rejected before advancing
the duplicate timestamp watermark, so a future timestamp cannot suppress real fixes.

GGA satellite count, HDOP, mean-sea-level altitude and geoid separation are taken
from checksum-validated GPGGA/GNGGA receiver sentences with matching UTC and fresh
monotonic receipt time. If NMEA is unavailable, those optional fields remain empty.
Android 14+ can additionally supply its explicit MSL altitude. Android WGS84
ellipsoid altitude is never substituted for MSL height. Unknown RMC speed stays
empty rather than claiming the vehicle is stopped. Receiver no-fix GGA immediately
invalidates GPS in protection mode.

This improves truthful data reporting, but does not establish full MFi/CarPlay
certification. The existing PASCD proprietary constants and Siri synthetic short
press require authoritative protocol review and physical interoperability testing;
no unverified proprietary changes are made as part of the GPS repair.


### E28 on-change speed fallback (verified on the vehicle)

The E28 VCU_RAW_CAR_SPEED property reports change mode ON_CHANGE (1), with
minimum and maximum sample rates both 0 Hz. Requesting 4 Hz does not guarantee
periodic callbacks. During active motion sharing, the shell bridge retains its
subscription and re-queries the real speed property if no value has arrived for
250 ms. Successful reads create new acquired samples, including a measured zero
while stopped; failed reads remain unavailable. No locally retained speed is
relabelled as fresh. This prevents constant speed from starving PASCD after the
telemetry freshness window, but does not guarantee underground position updates
or force iOS to accept wheel-speed dead reckoning.

### 仅传送 GPS 定位

新增独立开关，默认关闭，下次连接生效。开启后不订阅网络或融合定位；防漂移仍由原开关控制。仅 GPS 开启、防漂移关闭时，直接发送 GPS 坐标，不平滑、不锁定；超过 3 秒或接收器报告失效时发送无有效定位。两者均关闭保留默认行为。车辆速度与挡位不受此开关影响。
