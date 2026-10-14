# User guide (English)

[简体中文](USER_GUIDE.zh-CN.md)

## Installation
Download the release APK from the GitHub Releases page. Install Shizuku independently and start it (using wireless debugging when non-rooted). Open Galaxy NR Tools, authorize Shizuku, and verify that the connected service UID reads `2000` (shell) or `0` (root). No PC terminal app is required for routine phone usage.

The app follows the current system light/dark setting and chooses Chinese for `zh` system languages; otherwise it uses English.

## Before touching modem settings
First open **5G network evidence**. A current LTE indicator is not proof that NR is disabled: low coverage, carrier policy, and 5G NSA all matter. Public Android APIs cannot verify Samsung's whitelist/PRUNE/permit-list NV values.

Ensure the phone is unlocked, the display stays awake, SIM1 is the relevant SIM, and the original Samsung dialer responds to `*#27663368378#`. Grant overlay permission if you want the small passive progress pill; the feature is optional and does not intercept taps.

## Probe only
Press **Probe six menu paths**. The app opens ServiceMode and checks each named route using offline OCR. It does not press the final radio-setting button. After the job, it *attempts* to bring Galaxy NR Tools forward, but Android background-launch policy or firmware may block that; use the notification if necessary. Read the execution log. If a submenu is missing or ambiguous, the app stops safely.

## Restore and reboot
Choose **Core restore** (5 actions) or **Full restore** (all 6, including mmWave). Confirm the warning. The app verifies page context before every tap and attempts bounded recovery by ServiceMode Menu/Back or restarting the secret-code route. If `ALL ENABLE (*)` or `SA/NSA ENABLE (*)` is already selected, it avoids reselecting it; **REMOVE SA PERMIT LIST is always a separate action**. The completed-apply dialog offers restart. A successful click does not prove modem NV state or carrier capability.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Shizuku disconnected after reboot | Start Shizuku again, then reopen the app and authorize if needed |
| Only LTE visible | Coverage/SIM/carrier policy may explain it; inspect network evidence, don't assume 5G is locked |
| OCR missing a menu | Wait for page to settle; inspect the current screen, submit only redacted logs/screenshots as an issue |
| Automatic return did not work | Android may block background launches; tap the foreground notification to view results |
| APK says “app not installed” during upgrade | Signatures differ. This beta uses an ephemeral CI debug signature; back up logs before uninstalling for a future update |
| Unsupported model | Only known US U/U1 variants allow ServiceMode automation; request support with non-sensitive read-only evidence |
| No 5G after successful apply | Reboot if advised, then verify SIM, plan, carrier, bands, coverage and local regulations; the app makes no guarantee |

## Privacy and logs
Screenshots are transient and not uploaded by app code; text interpretation runs locally. The app may write the last complete build/profile/timestamp to `/sdcard/Download/Galaxy5G/`. Technical logs can include original Samsung menu text and legacy Chinese script messages even in English UI. Avoid posting any IMEI/IMSI, phone number, carrier credentials, or location in bug reports. Google ML Kit may collect SDK telemetry as documented in [PRIVACY.md](../PRIVACY.md).

**Dialer compatibility:** The direct Activity and secret-code broadcast may be denied on stock One UI. If the dialpad OCR cannot recognize all keys, v0.10.1 uses the older Shell keyevent method *only while Samsung Dialer is foreground*, with extra delays. This may still fail on a firmware that ignores the final `#`; it never presses the call key. If it fails, capture the sanitized execution log and a screenshot of the dialer keypad, with personal phone numbers redacted.
