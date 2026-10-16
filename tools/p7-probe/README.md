# P7 只读车辆接口测试

独立 Java/Dex 测试程序，不安装 App，不包含 CarPlay 认证资源。默认只在电脑上编译，只有显式指定 `--run --serial` 才会访问车机。

## 本地构建

```sh
python3 scripts/p7_vehicle_probe.py
```

输出：`build/tools/p7-probe/p7-probe.jar`。

## 回到车机后运行

确认 ADB 已连接，再把下面的 `DEVICE_SERIAL` 替换成 `adb devices -l` 显示的设备地址：

```sh
python3 scripts/p7_vehicle_probe.py --run --serial DEVICE_SERIAL
```

脚本上传到唯一命名的 `/data/local/tmp/xplay-p7-probe-*.jar`，测试结束尝试删除该文件。不会安装、卸载或更新 XPlay。报告保存到本机 `build/tools/p7-probe/report-*.txt`。

## 一次覆盖的情况

- 默认、64 位和 32 位 `app_process` 的框架加载情况。
- 正常 `Car.createCar` 的五种工厂签名：两种异步服务连接、同步连接、带 Handler 的同步连接及生命周期回调连接。
- 系统 Context 与通过正常 `createPackageContext(..., 0)` 创建的 XPlay Context。两者进程 UID 都仍是 ADB shell，不代表 XPlay 自身权限。
- 五项已知属性：电量、档位、车速、油门、刹车。
- 属性可见性、声明的读取权限。
- 标量读取、通用 `getProperty`、带类型的 `getProperty`。
- 只读 `registerCallback`，观察约两秒，然后注销。没有变化时不能据此认定监听失败。
- 最后用 `dumpsys car_service` 的五项最近上报值作对照；不保存完整诊断或其他车辆标识。

每个进程设 15 秒内部截止时间及 20 秒外部截止时间，本机等待再限制为 25 秒。连接可能提前失败，最慢整轮约五分钟。缺少某种运行时或接口时会记录失败并继续，不会猜测 Binder 事务编号，不调用 setter，不授予或绕过权限。

## 如何判断

- `BOOT`：Java 测试入口已运行，可区分启动失败和服务连接失败。
- `FRAMEWORK available`：车辆框架类可以加载。
- `CONNECTED` / `MANAGER`：正常服务连接及属性管理器已经取得。
- `READ battery ... value=49`：该调用返回了数值。以同时含 `status=0` 的通用读取为更强证据；仅标量返回 0 不足以判定实际电量为 0。
- `timestamp`：车辆框架给出的事件时间戳，不能当作墙上时钟，也不保证调用时重新采样。
- `SecurityException`：调用被拒绝；不会自动修改权限。
- `SUBSCRIBE ...=true`：注册成功。`EVENT` 是实际回调，可能是注册后的初始值。
- `TIMEOUT stage=...`：该阶段没有在窗口内完成，不直接等同于权限不足。
- 某组进程退出码为 0 只表示测试组执行完成，不表示其中每种查询都成功；看每条 `READ` / `SUBSCRIBE` 结果。

即使 shell 进程查询成功，也还需要另行验证普通 XPlay 应用能否使用同一接口。已于 2026-10-15 在 E28 / Android 9（API 28）实车运行：

- 三种运行时都能加载车辆框架。
- 普通工厂连接均在绑定服务时返回 `SecurityException: Unable to find app for caller`，不能据此断言属性读取被拒绝。
- 公开生命周期工厂在 ready 回调中成功取得属性管理器，三种查询均读到电量 37、档位 4、车速 0.0、油门 0、刹车 0，与诊断缓存相符。
- 五项 `registerCallback` 返回 true，但工厂随后仍因绑定服务异常结束，没有持续回调成功的证据。
- 部分属性对象的 `status` 超出通常的状态码范围；不能忽略这一兼容性异常，也不能把查询成功当成稳定连接已完成。
- 未修改 XPlay，临时 Dex 包已删除。原始报告保存在本机 build 输出目录。
