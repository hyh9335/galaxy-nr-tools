# 功能、原理与风险边界

[English](FEATURES.md)

普通 Android App 调用本机 ML Kit 拉丁文字 OCR；Shizuku 通过独立 UserService 提供经授权的 shell 身份、当前 ServiceMode 的临时截图。截图通过文件描述符送到普通应用进程识别，状态机再判断当前页面与下一步目标。执行脚本内置 SHA-256 白名单，不对外提供通用命令执行接口。

**六项独立操作**：① 恢复默认 LTE 频段，② 移除 NR5G PRUNE，③ 移除 GCF NR 白名单，④ Sub6/mmWave 选择 ALL ENABLE（仅完整模式），⑤ SA/NSA ENABLE，⑥ **REMOVE SA PERMIT LIST**。步骤五即使已选中，步骤六仍需独立执行。

**安全限制**：每次点击根据当前菜单识别文字计算坐标，不使用固定坐标；只容许经上下文验证的 OCR 变体；`(*)` 不确定时不盲目修改；不能识别、多重匹配、前台丢失则停止。返回使用 ServiceMode Menu/Back 或重新进入，不盲目发送 Android 返回键。

**做不到的**：不具备直接验证 NV、运营商套餐、SA 核心网接入或毫米波实际连接的能力；Android 5G 图标不能代表 NR 实际驻留。不能保证改动使所有地区开通 5G；无法保证每台 S23–S26 固件菜单一致。只有美版 U/U1 列表允许工程流程，其他设备仅能用普通只读检测。无 Root 的 Shizuku 在重启后需重新启动；自动返回应用可能被 Android 后台 Activity 限制。

测试涵盖模拟菜单导航、OCR 常见误识别、异常状态、审批与重启的条件验证，但仍不能替代真机无线参数与网络测试。
