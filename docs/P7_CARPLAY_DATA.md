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
Gear is refreshed once a second, speed uses the existing 4 Hz subscription, and both expire
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
