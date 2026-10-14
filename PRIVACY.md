# Privacy notice (English)

[简体中文](PRIVACY.zh-CN.md)

**No account needed.** Application code contains no telemetry or upload endpoint and requests no INTERNET permission. ServiceMode screenshots are temporarily created in `/data/local/tmp`, passed through an app-private Binder callback for *on-device OCR*, and deleted. The app does not intentionally read IMEI/IMSI or phone numbers. A successful apply may write a build/version/timestamp status marker to `/sdcard/Download/Galaxy5G/`.

**Third-party caveat:** This app bundles **Google ML Kit Text Recognition**, which is governed by Google's own terms. Google states that input images and recognized outputs are processed on-device, but its SDKs may contact Google servers for fixes/model/hardware updates and may collect device, app, usage/performance metrics through Google components. We cannot truthfully promise zero vendor telemetry. See [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms) and [Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

Permissions: `READ_PHONE_STATE` for optional public telephony diagnostics; Shizuku authorization for menu automation; `SYSTEM_ALERT_WINDOW` for optional non-touchable floating progress; foreground-service permissions to support local recognition while ServiceMode is visible. Debugging logs are local and may include ServiceMode text. Please redact personal information before opening an issue.
