# XPlay 定制版维护

## 当前基线

- 定制分支：`codex/xplay-custom`
- 上游：`https://github.com/shihabal3amri/DiPlay.git`，远程名 `upstream`
- 初始上游提交：`9aa9c549`（0.2.19 public preview）
- 最近完整同步：2026-10-14，合入 `upstream/main` 的 `c30d3f0d`（36 个上游提交，含合并提交）。
- 此次合并前恢复点：`xplay-before-upstream-20261014-merge`。
- `upstream` 的 push 地址已禁用，避免把定制推到原仓库。
- 本地恢复点：`xplay-custom-20261014`
- 已同步上述上游开发代码；只编译安装包，实际功能由用户验证。

定制包括 XPlay 品牌、XPENG 车辆图标、国内构建镜像、通用设置向导、首页及设置布局、统一黑白配色、自绘应用弹窗和连接动画。现有截图是历史界面记录，不代表当前所有页面已验证。

## 2026-10-19 选择性同步

在 P7 定制提交 `b52a8a4b` 上选择性吸收 DiPlay 0.2.20 / 0.2.21 的通用修复，核对的上游为 `6d3b8a8c`。完整合并基线仍为 `c30d3f0d`，本次不合并整个发布、不升级版本号，也不标记为完整 0.2.21。恢复标签：`xplay-before-selective-upstream-20261019`。

合入的修复：

- 音频接收、播放和麦克风线程优先级；音乐时钟漂移调整及 0.2.21 的持续积压恢复；缓冲诊断限流。
- iPhone 恢复播放时重新申请音频焦点；软件 Opus 内部断言失败时丢弃单帧并重置编码器。
- USBMUX VERSION 帧兼容；USB 模式切换无响应的 10 秒复查；CarPlay 会话未启动的 30 秒超时恢复；连接已恢复时取消过时的重试，恢复后再次断线仍重试。
- 蓝牙适配器重新获取、开启中的有限等待和诊断；不支持 5 GHz 时的 Wi-Fi Direct 回退。
- 权限拒绝和连接失败原因显示；USB 用户未配置热点时仍可保存其他快捷菜单设置，切换无线时重新校验。
- USB/MFi 设备列表、媒体键、蓝牙暂停恢复、崩溃堆栈和每分钟位置/轮速摘要。

保留 XPlay 品牌、P7 设置分组、车机设置手动指引、返回键行为、车辆桥接、GPS-only/防漂移独立开关、速度参与定位及方向输出；官方应用更新仍禁用。位置摘要仅增加日志，不替换 P7 的速度回调顺序。

本次暂不引入：悬浮球、黑色圆角、临时系统栏、方向盘新映射、分离的有线/无线缓冲设置、导航音量新控件、自动扬声器路由、热点导入/写入、新热点接口配置、意大利语、Android 7.0 最低版本变更、比亚迪仪表/氛围灯/分屏改动。Android 13 热点与 Wi-Fi Direct 冲突的新增处理也留待后续同步。

提交来源（部分提交仅取相关文件或按 P7 行为适配，不是完整 cherry-pick）：

- `656577fe` Wi-Fi Direct without 5 GHz, Bluetooth pause and vocoderInfo in the report, audio thread priority
- `19649861` Read a VERSION reply after a reply trailer; USB title on the home card
- `799693af` Say when Android Bluetooth is missing or will not turn on, instead of asking again
- `e3b6c58c` Improve audio reliability diagnostics and queue recovery
- `0abc0712` Cap audio rebuffer and queue full diagnostic report events at 5
- `dfd7c3d6` Keep music playing when the head unit's audio clock drifts
- `7544723c` Report media keys and media-key focus changes
- `47b78c66` Report what Android lists while waiting for the CH341
- `b9679df4` Name the refused wireless permission on the CarPlay screen
- `ec99c2d2` Name Nearby devices only when its permissions are missing
- `21d6832b` Retry transient media focus loss only when iPhone playback resumes
- `15ee0cab` Bound sustained stale music backlog without impairing short Wi-Fi bursts
- `4aee9fa7` Fix Android 8 Bluetooth adapter acquisition and add diagnostics (#750)
- `b274c86f` Refine Bluetooth radio state handling and diagnostic report (#750)
- `ab15f847` Wait for Bluetooth That Is Turning On and Report Adapter State (#822)
- `2c808c07` Report what Android lists while waiting for the iPhone over USB
- `0f00e8a4` Name the wireless startup failure on the CarPlay screen
- `4791f5d0` Let a failed check in the software Opus encoder cost one frame, not DiPlay
- `1d3cd00f` Save CarPlay menu settings when an unused hotspot is not set up
- `940c362a` Check the hotspot again when the menu switches to wireless
- `ab324016` Stop waiting forever for an iPhone that ignores the mode request; lower guidance in the PCM
- `b6c2db84` Leave a wired attempt alone when it recovers before its retry
- `fd004ac5` Restart a recovered attempt that is lost again before its retry
- `6270e992` Retry wired CarPlay when the iPhone never opens its session
- `89810087` Report once a minute what location and wheel speed reached the iPhone

崩溃记录另取 `b52a8a4b...6d3b8a8c` 中 `CrashRecord.kt` 及对应上游测试的差异。随修复保留相应上游测试源码，但按 AGENTS.md 本次不运行测试或 Lint。

验证：`./gradlew :mobile:assembleDebug` 编译通过；未安装到车辆，P7 的连接、音频和通话效果尚待实车验证。音乐积压修复不等于短视频端到端音画同步已解决。

## 自己的远程备份

- 个人 Fork：`https://github.com/laiqidong/XPlay`
- `origin`：`git@github.com:laiqidong/XPlay.git`，使用已有 SSH 登录。
- 定制分支：`codex/xplay-custom`；GitHub 的 `main` 保留 Fork 创建时的上游代码。
- 定制分支直接入口：`https://github.com/laiqidong/XPlay/tree/codex/xplay-custom`

后续提交定制后，在当前分支运行 `git push` 即可备份。恢复标签可用 `git push origin <标签名>` 单独推送。
不要向 `upstream` 推送，不要将定制分支重置为上游分支。

## 应用内更新策略

XPlay 已停用 DiPlay 的应用内自动/手动更新检查、提示与安装包下载。不要安装原版更新覆盖定制；后续通过以下 Git 合并流程更新源码，再生成并手动安装 XPlay。

## 同步上游正式版本

先提交当前修改，并确认工作区干净。先获取版本，选定正式发布的真实标签，再合并：

```sh
git switch codex/xplay-custom
git fetch upstream --tags
git status --short
git tag --list
# 将下面的 <正式版本标签> 替换成确认过的真实标签。
git merge <正式版本标签>
```

也可以明确选择同步开发分支：`git merge upstream/main`。

合并前创建唯一的恢复标签，例如 `git tag xplay-before-update-YYYYMMDD-HHMM`。若有冲突，逐处保留定制并吸收上游修复，不要整体选择“我们的”或“他们的”。放弃未完成的合并可用 `git merge --abort`。

合并完成后生成安装包，由用户验证连接、设置保存与界面。验证后提交尚未提交的合并结果，再推送自己的 `origin`。不要使用 force push。

## 本地离线备份

`.local-backups/xplay-custom-20261014.bundle` 保存此次提交及 Git 历史。它位于当前项目内，建议另外复制到其他磁盘。恢复示例：

```sh
git clone /绝对路径/xplay-custom-20261014.bundle XPlay-restored
git -C XPlay-restored switch codex/xplay-custom
```

Bundle 只保存已提交的内容，不包含 `.private/` 私有运行素材、签名密钥、本地 SDK 配置、生成的 APK 或后续未提交修改。这些内容不会上传到 GitHub；需要时单独安全保管。
