# XPlay 定制版维护

## 当前基线

- 定制分支：`codex/xplay-custom`
- 上游：`https://github.com/shihabal3amri/DiPlay.git`，远程名 `upstream`
- 初始上游提交：`9aa9c549`（0.2.19 public preview）
- `upstream` 的 push 地址已禁用，避免把定制推到原仓库。
- 本地恢复点：`xplay-custom-20261014`
- 本次只保存定制，不合并上游新版本；实际功能由用户验证。

定制包括 XPlay 品牌、XPENG 车辆图标、国内构建镜像、通用设置向导、首页及设置布局、统一黑白配色、自绘应用弹窗和连接动画。现有截图是历史界面记录，不代表当前所有页面已验证。

## 第一次设置自己的远程备份

1. 在 GitHub 登录自己的账号，Fork `shihabal3amri/DiPlay`。
2. 将 Fork 的真实克隆地址添加为 `origin`：

```sh
git remote add origin <自己的Fork克隆地址>
git push -u origin codex/xplay-custom
git push origin xplay-custom-20261014
```

不要向 `upstream` 推送，不要将定制分支重置为上游分支。

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
