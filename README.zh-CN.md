# Galaxy NR Tools（Galaxy NR 工具）

[English](README.md) · [下载](https://github.com/hyh9335/galaxy-nr-tools/releases) · [中文使用说明](docs/USER_GUIDE.zh-CN.md) · [功能说明](docs/FEATURES.zh-CN.md) · [隐私](PRIVACY.zh-CN.md)

这是一个**非官方社区开源项目**：通过 Shizuku，在手机本地用 OCR 识别三星 ServiceMode 工程菜单，检查菜单是否可到达，并按用户明确确认的方式调整部分 5G 设置。**不需要 Root，不解锁 KNOX。**

![原创应用图标](docs/assets/icon.png)

## 主要功能

- **5G 只读诊断**：分析系统公开的网络类型与显示状态，绝不因为当前为 LTE 就断定 5G 被关闭。
- **六条菜单路径探测**：只查找路径，不点击最终设置按钮。
- **基础恢复 / 完整恢复**：严格的机型与页面识别、手动确认、异常停止，完整模式包含毫米波项。
- **本机离线 OCR + 状态机**：可识别 `SUB/MMWAVE`、`NEXT PAGE -|`、`ALL ENABLE (*)`、`SA/NSA ENABLE (*)` 等实际 OCR 变体，菜单身份不明确时停止。
- **结束后尝试自动返回应用**：只在 ServiceMode 仍然位于前台时切回，系统禁止则可通过通知返回。
- **跟随系统深浅色与语言**：中文系统显示中文，其余语言默认英文；提供自适应/单色图标、悬浮进度与执行日志。
- **实际恢复成功后提示重启**：探测成功或异常退出不会开启重启按钮。

## 机型范围

| 项目 | 说明 |
|---|---|
| 公开 Android 5G 状态诊断 | 支持能提供相关公开电话接口的设备，需授权 |
| ServiceMode 自动导航 | 已列入白名单的 **美版 U/U1** Galaxy S23、S24、S25、S26 系列及部分 FE/Edge |
| 已有真实菜单操作日志 | **S25 Ultra SM-S938U / Android 17**，但尚未独立验证基带 NV 是否持久生效 |
| SIM | 目前只操作 SIM1 |
| Root / KNOX | 无需 Root，不触动解锁 |

现有美版型号前缀包括 S711/S911/S916/S918（S23）、S721/S921/S926/S928（S24）、S731/S931/S936/S937/S938（S25）、S741/S942/S947/S948（S26），需带 U/U1 后缀。**列入设备白名单并不表示对应固件经过实测！** 其他地区机型的工程操作默认拒绝，以免误点。

## 使用步骤

1. 从 [Releases](https://github.com/hyh9335/galaxy-nr-tools/releases) 下载发布版 APK；更新目前公开 Beta 使用 CI 临时 Debug 签名，下一版可能无法直接覆盖安装；卸载前请保存日志。
2. 启动 Shizuku，给 Galaxy NR 工具授权。无 Root 的 Shizuku 在重启后通常需要再次启动。
3. 建议授予悬浮窗权限；手机保持解锁与亮屏。
4. 先运行“5G 网络检测”和“六条路径探测”。探测不会点击最终配置项。
5. 查看执行记录，如确有必要，再明确确认“基础恢复”或“完整恢复”。
6. 仅在实际恢复全部完成后使用“立即重启”；如系统不允许，可手动重启。

六个独立操作：恢复默认 LTE 频段、移除 NR5G PRUNE、移除 NR 白名单、Sub6/mmWave ALL ENABLE（仅完整模式）、SA/NSA ENABLE、**REMOVE SA PERMIT LIST**。即使第五项 `SA/NSA ENABLE (*)` 已选中，第六项也不会被跳过。

**风险提示：** 工程菜单操作可能导致移动网络暂时或持续不可用。仅凭点击成功不能证明 NV 参数已改变；不能保证运营商提供 5G、5G SA 或毫米波。建议先探测，不要反复盲目执行。

## 文档、开源与版权

[详细使用说明](docs/USER_GUIDE.zh-CN.md) · [功能和技术边界](docs/FEATURES.zh-CN.md) · [隐私说明](PRIVACY.zh-CN.md) · [第三方许可](THIRD_PARTY_NOTICES.md) · [贡献说明](CONTRIBUTING.md) · [MIT LICENSE](LICENSE)

**项目自行编写的代码与原创图标采用 MIT 许可**；Shizuku API 使用自身的 MIT 许可；Google ML Kit 则遵守 Google 的专有服务/SDK 条款，不能宣称其中的模型也采用 MIT。具体见第三方说明。本项目与三星、Google、Shizuku 官方均无合作或授权关系，未使用其官方图标。

开发构建：JDK 17、Android SDK 35、Gradle 8.9；运行 `python3 -m unittest discover -s tests -v` 和 `gradle :app:assembleDebug`。保留旧包名 `com.example.galaxy5gmanager` 以兼容历史安装。
