# Galaxy 5G Manager （开发验证版 0.2）

针对美版 Galaxy S23 Ultra（SM-S918U/U1）和 S25 Ultra（SM-S938U/U1）的一款**手机本地** 5G ServiceMode 菜单自动化 Android 应用。使用 Shizuku 官方 UserService 接口，以 ADB Shell 权限执行内置、SHA-256 校验过的菜单导航脚本；**不要求 Root，不需要 Termux、aShell You 或电脑端 ADB 参与手机日常运行**。

> ⚠ 目前交付的是完整 Android 工程，不是已经编译并在真机验证的 APK。这里的 12 项导航模拟测试和 6 项项目静态测试均不等价于 Android 构建通过或真机证明。工程操作有蜂窝网络设置风险；请在 S23U/S25U 真机先检查 ServiceMode 暗码是否可用，再使用探测功能。

## 功能

- 「连接 / 授权 Shizuku」：请求官方 Shizuku 权限，仅在服务确实具备 UID 2000/0 时允许运行。
- 「设备状态」：识别三星美版机型、安卓和固件，不修改参数。
- 「探测六条菜单路径」：通过模拟拨号、UI Automator 层级 XML 和当前实际位置查找菜单，不点击各项设置的最终按钮。
- 「恢复基础 5G」：执行 LTE 默认频段、NR PRUNE、NR 白名单、SA/NSA ENABLE 和移除 SA permit list 共 5 项，跳过 mmWave。
- 「完整恢复」：六项，包括 Sub6/mmWave ALL ENABLE。**执行前会显示二次确认弹窗**。
- 「中止」「查看执行记录」：终止剩余自动化；已经提交的设置无法自动撤销。

## 已知限制

- 只能处理 **SIM1**；不自动操作其他型号、SIM2、其他厂商或未知菜单。
- Shizuku 无 Root 启动后通常在手机**重启后需要再次启动**；没有实现开机全自动静默运行。
- 系统更新可能改变 ServiceMode 名称或禁用工程码；无法可靠读取菜单时会停止，不会根据坐标盲点。
- 设备必须**解锁并保持屏幕亮着**。自动操作会调起拨号器和 ServiceMode，因此 App 可能暂时进入后台；完成后返回应用看日志。
- 仅能确定操作是否成功导航到菜单以及点击最终项，**不能独立读取 NV 确认修改已持久化**。恢复成功与否还依赖手机硬件、运营商、SA 开通及实际信号。
- 无任何联网、上传或读取 IMEI/IMSI 的逻辑。状态记录写入 `/sdcard/Download/Galaxy5G`。

## 构建 APK（Android SDK 35 / JDK 17 / Gradle 8.9）

1. 安装 Android Studio（或 Android SDK CLI）、JDK 17。安装 SDK Platform 35、Build Tools 35.0.0。
2. 打开本目录作为 Gradle 工程。项目使用 AGP `8.7.3`、Shizuku API/Provider `13.1.5`。
3. 运行 `gradle :app:assembleDebug`，或在 Android Studio 中选择 Build APK。
4. 产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

如果在自己的 GitHub 私有仓库上传此工程，`.github/workflows/android-debug.yml` 可通过 Actions 的 **Run workflow** 构建并保存 debug APK；此步骤需要仓库的 Actions 权限和网络。注意 CI 的临时 debug 签名密钥可能在后续构建时变化，更新时需注意签名一致性；不要将私钥上传到公开仓库。

首次安装 APK 后，在 Shizuku 中确认服务已运行，打开此应用授权。建议顺序：**查看设备状态 → 探测菜单（不提交）→ 检查日志 → 再考虑基础/完整恢复**。修改基带设置前应确保能承担短时无信号或网络丢失风险。

## 自动测试

```bash
python3 -m unittest discover -s tests -v
```

其中 12 项为模拟 Android ServiceMode 页面和 ADB shell 的离线测试，另 6 项为工程静态/安全约束测试。

## 授权与归属

- 本应用原生 Java/Gradle 工程及菜单脚本使用 MIT 许可。
- Shizuku API / Provider 为 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 项目，MIT 许可；**本应用不是 Shizuku 官方产品**，不复制其商标资源。
- 项目仅依赖 Shizuku 的运行时授权；不修改 Shizuku、Knox、Bootloader 和系统基带固件。


## 云端构建验证（2026-10-14）

- GitHub Actions 构建成功：`https://github.com/hyh9335/galaxy5g-manager/actions/runs/38506467653`
- 18 项离线测试通过，`assembleDebug` 成功，上传的 APK 已校验 ZIP 完整性与内置脚本 SHA-256。
- 仍未在 S23U/S25U 上完成真机运行验证，首次只能使用只读状态和菜单探测。

## Android 17 UIAutomator 兼容修复 (0.2)

SM-S938U Android 17 上，部分 ServiceMode 页面会让 `uiautomator dump` 返回失败。新版保留 UIAutomator 文字控件识别，失败时在记录里显示该命令的原始错误摘要，并通过 Shizuku shell 的 screencap 截图，在本应用内调用打包的 ML Kit Latin OCR 离线识别菜单文字和坐标，不新增应用、不上传截图。截图在临时目录中随即删除。

OCR 无法取图、没有识别到菜单、重复相同文字或点击后页面没有发生预计变化时终止，不按预设屏幕坐标猜点击。首次使用仍只建议“探测六条菜单路径”，未获真机验证之前不要执行任何实际基带写入。
