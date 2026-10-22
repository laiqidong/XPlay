# XPlay 全局字体

用户要求使用本机 Mac 中的苹方字体。当前本地安装包采用 PingFang SC Regular、Medium 和 Semibold 三个字重；来源是本机 `/System/Library/AssetsV2/com_apple_MobileAsset_Font8/` 下的 PingFang.ttc。

- 全局主题和原生控件使用 `res/font/pingfang.xml`。
- `AppFonts` 缓存字体，供设置控件、弹窗、自绘卡片、歌词等显式使用。
- Compose 各文字层级使用同一字体族。
- 歌词当前句保持 500 字重，下一句保持 400；字号、布局及切换动画设置不变。
- Web 设置页面优先使用访问设备上的 PingFang SC，不通过网页分发字体。
- iPhone 投放的 CarPlay 视频、系统权限界面和外部应用界面由其各自系统控制。

本机字体二进制不纳入 Git。新的本地构建环境需提供本地字体：在带有 `fontTools` 的 Python 环境中运行 `scripts/prepare_local_pingfang.py --source /path/to/PingFang.ttc`。该脚本不下载字体，也不修改系统字体文件。提取不改变原字体权利信息；本机系统字体的存在不是公开再分发授权的证明。

此次验证仅编译打包，未截图、启动模拟器或更新车机。真实显示和字形间距由用户实车确认。
