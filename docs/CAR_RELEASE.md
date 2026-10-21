# 本地车机打包与更新

在项目根目录执行（需要 Python 3.11+、JDK、Android SDK 和 adb）：

```sh
./build.sh
./deplory.sh
```

`build.sh` 仅构建签名 standalone release，排除 Lint，不运行 CI 或测试。
使用本地 `.private/runtime-assets` 与 `.private/xplay-signing/` 签名输入；
也支持通过现有 `DIPLAY_AUTH_ASSETS_DIR`、`ANDROID_KEYSTORE_*`、
`ANDROID_KEY_PASSWORD`、`ANDROID_KEY_ALIAS` 环境变量覆盖，不输出密码。

每次成功构建后归档 APK、SHA-256，并更新本机的
`artifacts/releases/latest-car-build.json`。开始新构建时先清除旧部署记录，只有成功后才写入新记录。
构建失败后部署入口会拒绝安装，避免误装旧包。

`deplory.sh` 不重新编译，验证归档包的哈希、签名及应用 ID，再连接车机，
执行保留数据的覆盖安装，显示版本和更新时间，最后打开 XPlay。
不会卸载、清除设置、截图或操作 CarPlay。
签名不兼容时安装失败，脚本不会通过卸载绕过。
安装和启动成功不代表实际 CarPlay 画面、导航效果已验证。

默认设备为 `192.168.43.1:5050`，可覆盖：

```sh
./deplory.sh --device 192.168.43.1:5050
./deplory.sh --no-launch
XPLAY_ADB_DEVICE=192.168.43.1:5050 ./deplory.sh
```

脚本名称沿用约定的 `deplory` 拼写。
