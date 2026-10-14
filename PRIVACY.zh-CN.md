# 隐私说明

[English](PRIVACY.md)

无需注册或登录。**应用自行编写的代码没有数据上传接口、不申请 INTERNET 权限**。ServiceMode 截图临时保存在本地 `/data/local/tmp`，使用 App 内离线 OCR 后删除；不主动读取 IMEI、IMSI 或手机号。实际恢复成功后可能在 `/sdcard/Download/Galaxy5G/` 存储固件版本和执行时间标记。

**注意第三方组件**：本应用使用 Google ML Kit 文字识别 SDK，按照 Google 独立的许可及隐私条款运行。Google 声明输入图片及文字识别结果在设备本地处理，但 SDK 可能通过 Google 组件联系服务器获取模型/修复，并发送设备与应用性能指标。不能无依据承诺“Google 不收集任何信息”。参考 [ML Kit 条款](https://developers.google.com/ml-kit/terms) 与[数据说明](https://developers.google.com/ml-kit/android-data-disclosure)。

请求的权限包括：可选电话状态检测、Shizuku 工程菜单权限、可选悬浮窗、操作期间的前台服务。公开反馈执行记录前请去掉 IMEI、IMSI、手机号和地区等敏感信息。
