# P7 E28 climate parity audit (2026-10-24)

Scope: the stock climate page and its expanded menu captured on 2026-10-23. Seats remain excluded per the user. Layout changes must not change the meaning of stock controls. Interface discovery, successful readback, emulator interaction, and physical vehicle actuation are separate evidence levels.

Evidence: stock screenshot `/private/tmp/xplay-car-controls-current.png`; current-car `android.car.jar` pulled on 2026-10-23; stock `com.xiaopeng.carhvac` APK inspected offline. Vehicle is currently offline, so this audit did not actuate climate controls.

| Stock capability | Current overlay / access | Evidence and remaining limit |
| --- | --- | --- |
| Driver/passenger set temperature | Independent minus/plus | Getter readback succeeded. Native increment/decrement methods are used; no new temperature range imposed on vehicle. |
| Fan | 10 native levels, large drag target and minus/plus; one request on gesture release | Framework min/max readback was 1/10. Automatic code 14 is not displayed as a fabricated manual level. Off code 0 remains distinct from AUTO. Emulator drag and increment checked. |
| AUTO | Native auto-state toggle | Getter readback succeeded; writes await vehicle verification. |
| Main power | Original power + memory parameter | Matches CarHvacManager forwarding; writes await vehicle verification. |
| A/C / heating / natural air / auto cool-heat | Four-mode icon picker; icon follows mode readback | Stock `hvac_ac_mode_level` has four native icon levels. CarHvacManager.setAcHeatNatureButtonSt forwards to setHvacAcEnabled. Commands 1/2/3/4 differ from readback pairs 0/1, 2/3, 4/5, 6/7. Previous simple A/C toggle was incomplete and is replaced. |
| SYNC | Native driver sync | Getter modes 1/2/3; stock HvacController sends boolean 0/1. |
| Windshield/face/feet outlet selection | Three independent native outlet toggles | Restored stock arrangement. A separate exclusive face+feet mode button had removed windshield selection; it is removed. Existing native combinations are shown, not redefined. Windshield outlet selection and front defrost remain distinct. |
| Inner/outside/automatic circulation | One control cycles the three native modes; icon reflects readback | Codes 1/2/6; no independent fourth mode. |
| Front defrost | Separate native action | Readback succeeded; physical effect pending. |
| Rear window heating | Separate native BCM action | Readback succeeded; mirror-heating linkage has not been verified. Do not relabel as proven mirror control. |
| ECO | Direct control in More | Getter readback succeeded; write pending vehicle verification. |
| Exhaust protection (AQS) | Direct control in More | Getter readback succeeded; write pending vehicle verification. |
| Smart defog | Direct control in More | Getter readback succeeded; write pending vehicle verification. |
| MAX cooling | Opens stock climate controls | **Not implemented as a direct overlay toggle.** Stock uses `setHvacRapidCoolingEnable` with application state, timers and a workflow. The formerly guessed WarpSpeedCooling mapping is unsupported on this car and has been removed. |
| Smart deodorizing | Available in stock climate controls via More | **Not reproduced inside overlay.** Stock is an application workflow, not merely a similarly named vehicle setter. |
| Smart cleaning | Available in stock climate controls via More | **Not reproduced inside overlay.** Requires exact workflow verification. |
| Smart passenger air outlet | Available in stock climate controls via More | **Not reproduced inside overlay.** getHvacEavPsnWindMode readback exists; that alone does not verify the smart feature workflow. |
| Fine outlet aiming / PM2.5 information | Stock climate controls | **Not reproduced inside overlay.** Existing angle APIs and PM2.5 readings do not establish full UI parity. |

The overlay is therefore **not yet a complete in-overlay replacement** for the stock climate application. The stock entry preserves access to unreplicated workflows, but must not be reported as a completed implementation of those controls. The simulator has no stock app installed; the corresponding stock-launch actions are unavailable there rather than pretending to control local vehicle features.

Visual/interaction scope: icon-only main page, neutral light selected surfaces with dark symbols, transient ripple feedback, no gradient used as toggle state. Simulator local values do not prove real vehicle behavior. No real-car deployment in this audit; build only, no unit tests/CI/Lint.

## Bottom-row layout update (2026-10-24)

The More page and its header entry are removed. Circulation, front/rear defrost, AQS, smart defog and ECO are now circular icon-only controls in the bottom row. Smart deodorizing, smart cleaning and smart passenger airflow also have OEM icon placeholders there, but remain disabled because their full stock workflows have not been verified. They do not send guessed vehicle commands or simulate active states. The stock-app shortcuts previously listed above are no longer exposed in this overlay. MAX and fine outlet aiming remain outside this page; full functional parity is still incomplete. Button feedback has no ripple.

## Circular control regrouping (2026-10-24)

A/C now cycles cooling → heating → natural air → automatic on each tap, using the native paired readback enum to select the next allowlisted command. The mode picker is removed. Controls occupy two rows of eight: power/A/C/AUTO/SYNC, then the three outlets/circulation; front/rear defrost, then the six smart/ECO controls. All use circular surfaces. OEM ventilation and ECO pictograms replace incorrectly selected rectangular artwork resources; cleaning uses the OEM A/C drying symbol. The three unverified smart workflows remain disabled.
