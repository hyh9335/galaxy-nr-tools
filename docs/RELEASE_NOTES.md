## Galaxy NR Tools v0.9.0-beta.1

**First public beta. Unofficial community software — use at your own risk. The APK uses an ephemeral GitHub CI debug signing key; future updates may require uninstalling this beta first.**

- Follows Android system light/dark appearance and locale (English fallback, Chinese available).
- Original modern adaptive icon and new app name; existing application ID retained.
- Best-effort automatic return to Galaxy NR Tools when ServiceMode is foreground, with notification fallback.
- Recognizes listed Galaxy S23–S26 US `U`/`U1` model families (**experimental** beyond tested S25 Ultra, SM-S938U / Android 17).
- Offline OCR page identification, strict settings confirmation and bounded recovery retained.
- Six actions remain distinct: **SA/NSA ENABLE does not skip REMOVE SA PERMIT LIST**.
- Read-only 5G evidence analysis, explicit restore confirmation, and optional reboot following successful apply.

### Installation
1. Download the signed APK attached to this release.
2. Install and authorize Shizuku; keep screen unlocked and awake.
3. **Probe first**. Only run a restore when menu routes match your firmware and you accept the risk.

### Known limitations
- Modem NV / actual mmWave/5G SA provisioning cannot be independently verified.
- UI navigation and automatic return depend on current firmware and Android background-launch restrictions.
- Carrier/regional menu restrictions are not bypassed. No root or KNOX changes required.
- Some raw technical log messages are in Chinese regardless of the UI locale.

Full docs: [English](README.md) · [中文](README.zh-CN.md) · [Privacy](PRIVACY.md) · [Third-party notices](THIRD_PARTY_NOTICES.md).
