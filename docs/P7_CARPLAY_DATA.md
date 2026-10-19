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


## GPS loss with GPS-only mode and drift protection enabled

When GPS-only mode is enabled, drift protection uses explicit Android GPS only.
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

### 原车导航方向对比

P7 设置 → 高级 → 诊断工具 → 方向对比（实验性）提供手动开始/停止的只读订阅。
通过原车导航公开的 LocationService，分别显示 GPS_LOCATION 和 DR_LOCATION 的角度。
仅在诊断页面前台采样，离开页面或应用进入后台即取消订阅；不保存开关、不修改原车设置、
不向 CarPlay 输出这些方向。时间显示为本机接收后经过的秒数，超过 3 秒应视为旧值；
DR 源时间为 0，不能把收到回调当成底层测量更新的证明。服务缺失或断开时清除读数。
停车时 GPS 行进方向可能与车头相反，DR 也需要与地图指南针及转弯后的变化分别核对。

### DR 方向输出开关（实验性）

P7 设置 → 高级 → P7 CarPlay 数据中可开启“使用 DR 车头方向”，默认关闭，
下次连接生效并提示手动重连。需要定位共享。开启后仅替换有效 RMC 的方向字段，
使用原车导航 DR 角度，不翻转倒车方向；坐标、速度、定位有效性及其他句子保持原路径。
DR 未到达、服务断开或接收间隔超过 1.5 秒时，方向字段留空，不回退到 GPS。
订阅随 iPhone 的定位订阅启停，保留后台 CarPlay 生命周期。
这是将车头朝向放入 RMC 行进方向字段的显式实验，不宣称新增了独立 heading 能力；
iPhone 是否使用该值、停车/倒车显示及转弯响应仍需实车对比。


### 自动定位来源筛选（2026-10-19）

本节取代旧版“开启防漂移就只订阅 GPS”的行为。仅 GPS 默认关闭，关闭时允许显式 GPS、
融合（包括 E28 Android 9 上的 `fused`）及网络提供器，不自动修改用户已保存的仅 GPS 开关。
自动模式始终检查单调源时间和报告精度：GPS 最长 3 秒/30 米，融合 5 秒/50 米，网络 5 秒/100 米。
这些是初始保守阈值，不代表实测精度或已验证的车库车道定位能力。
防漂移开启时，各来源需要 3 个连续可靠样本，并检查跳点、切换时的位置连续性；只有新鲜车速
确认停车时才限制小范围位置抖动。不开启防漂移时不锁定位置，但仍拒绝过期、未来时间和精度不足的数据。
GPS 有效时优先，否则依次评估融合和网络；没有合格来源时明确报告无有效定位。
接收器报告 GPS 无效只撤销 GPS，不撤销其他来源。不会刷新旧坐标的源时间。

非 GPS 来源用 GGA quality=6、RMC mode=E 表达估算定位，不宣称卫星数量或 HDOP。
参考 [u-blox NMEA 定位标记说明](https://content.u-blox.com/sites/default/files/products/documents/u-blox6_ReceiverDescrProtSpec_%28GPS.G6-SW-10018%29_Public.pdf)。
这是来源如实标记，不证明 iPhone Maps 已接受非卫星定位；仍需实车验证。
诊断工具内可查看当前选用来源、源数据年龄、精度和最近拒绝原因，报告不记录实际坐标。

原车 DR 坐标的短时实车采样返回源时间 0、精度 0；仅有坐标范围合法不足以证明有效。
本版不接入 DR 坐标，也不推算/制造它的有效状态或精度。DR 车头角度开关仍独立保留。

### DR 输出卡顿修正

原车导航 APK 加载、接口反射及订阅启停改到独立 HandlerThread，CarPlay DR 包装器不再
持锁调用下游定位接口，也不在主线程执行导航接口初始化。每次定位订阅结束清理监听并退出线程。
该修正消除了代码中的主线程重工作和外层锁风险；是否解决实际 CarPlay 卡顿需要实车复测。
