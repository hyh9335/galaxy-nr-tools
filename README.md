# Galaxy NR Tools

[简体中文](README.zh-CN.md) · [Download releases](https://github.com/hyh9335/galaxy-nr-tools/releases) · [User guide](docs/USER_GUIDE.md) · [Features](docs/FEATURES.md) · [Privacy](PRIVACY.md)

**A community-built, unofficial Android companion for observing 5G network evidence and navigating Samsung ServiceMode radio settings with Shizuku.** No root required. Designed for US Galaxy S23 and newer **U/U1** variants. **Experimental beta; only Galaxy S25 Ultra (SM-S938U, Android 17) has been observed in real-world menu walkthroughs.**

![Original Galaxy NR Tools icon](docs/assets/icon.png)

## Why it exists

Samsung's ServiceMode contains engineering menus that differ across firmware releases and carriers. Galaxy NR Tools uses offline OCR on screenshots and a strictly bounded, page-aware navigation state machine instead of screen coordinates or `uiautomator dump` (which can be killed on recent One UI/Android versions). It distinguishes menu *reachability* from settings *being active*, and never equates LTE coverage with a disabled 5G radio.

## Highlights

- **Read-only 5G diagnostics** from public Android telephony APIs. A 5G indicator is not proof of current NR attachment or modem NV state.
- **Six-path probe**: locates engineering menu actions without pressing their final setting buttons.
- **Core/full restore**: deliberate, confirmed flows with guardrails; full mode includes mmWave.
- **Offline OCR** in the app process; screenshot data is handled locally. Page aliases are context checked (for example `SUB/MMWAVE`, `NEXT PAGE -|`, `SA/NSA ENABLE (*)`).
- **Automatic return attempt** to the app when finished, only while ServiceMode is still foreground; a notification fallback is available where the OS blocks the return.
- **Follows system light/dark mode and system language**. English is the default for other languages; simplified Chinese is provided.
- **Responsive compact interface**, adaptive/monochrome launcher icons, floating progress (optional), detailed logs, and restart request after a complete *apply*.

## Compatibility and safety

| Category | Availability |
|---|---|
| Android | Android 11+ build target; Android 17 OCR path included; exact behavior depends on firmware |
| Read-only network diagnostics | Any Android device providing required public telephony APIs, with permission |
| Automated ServiceMode navigation | Listed **US U/U1** Galaxy S23/S24/S25/S26 S-series variants; other regions excluded |
| Most tested physical device | Galaxy S25 Ultra **SM-S938U / Android 17** (user menu logs; **not full independent radio NV verification**) |
| Root / KNOX unlock | **Neither required** |
| SIM | SIM1 only |

Supported U/U1 SKU families in the current allowlist: **S711/S911/S916/S918** (S23 / FE), **S721/S921/S926/S928** (S24 / FE), **S731/S931/S936/S937/S938** (S25 / FE / Edge), and **S741/S942/S947/S948** (S26 / FE). *These are recognized model identifiers, NOT a claim that every firmware's secret code and submenu is compatible.* Unknown variants fail closed. Support requests for additional models should include redacted, read-only menu evidence.

**IMPORTANT:** Incorrect cellular engineering settings can cause connectivity loss. A successful tap is not modem NV verification. This app does not modify arbitrary baseband bytes, bypass carrier provisioning, guarantee 5G reception, or independently prove that PRUNE/whitelist/mmWave restrictions were removed.

## Quick start

1. Download the APK from [GitHub Releases](https://github.com/hyh9335/galaxy-nr-tools/releases). This initial public beta is signed with an ephemeral CI debug key. Future updates may require uninstalling the previous beta first; save logs before doing so.
2. Install and start [Shizuku](https://shizuku.rikka.app/download/); authorize Galaxy NR Tools. Non-root Shizuku usually requires restarting its service after a phone reboot.
3. Optionally allow **Display over other apps** for non-interactive floating progress. Keep your device unlocked with the screen awake.
4. Run **5G status** (optional/read-only), then **Probe six menu paths**. Probe should finish without changing settings.
5. Review the log. Only if necessary, choose **Core restore** or **Full restore**, read the explicit confirmation, and monitor progress.
6. Following a successful *apply*, optionally reboot; Shizuku shell initiates the request and manual reboot remains a fallback.

**Six independent actions:** (1) Default LTE band, (2) remove NR5G prune, (3) remove NR whitelist, (4) Sub6/mmWave ALL ENABLE (**full only**), (5) SA/NSA ENABLE, (6) **REMOVE SA PERMIT LIST**. Actions (5) and (6) remain independent even when SA/NSA already shows `(*)`.

## Documentation

- [User guide (English)](docs/USER_GUIDE.md) · [中文使用说明](docs/USER_GUIDE.zh-CN.md)
- [Detailed feature and safety boundaries](docs/FEATURES.md) · [功能说明与限制](docs/FEATURES.zh-CN.md)
- [Privacy](PRIVACY.md) · [隐私说明](PRIVACY.zh-CN.md)
- [Third-party code & licenses](THIRD_PARTY_NOTICES.md)
- [Security / responsible reporting](SECURITY.md) · [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md) · [License](LICENSE)

## Build from source

JDK 17, Android SDK platform 35, Gradle 8.9, and Android Gradle Plugin 8.7.3:

```bash
python3 -m unittest discover -s tests -v
gradle --no-daemon :app:assembleDebug
```

The app ID remains `com.example.galaxy5gmanager` for continuity with existing installs. **Release-signing limitation:** This first public beta uses a temporary CI debug signature. A long-lived upgrade-compatible signing key is **not** configured. Future APKs may require removing the old beta first. Any future persistent signing key must be stored in encrypted CI secrets, not in this repository. This project does not ask for or store your carrier passwords, IMEI or Shizuku credentials.

## Licensing & affiliation

Own project sources and original icon artwork are licensed under the [MIT license](LICENSE). **Third-party dependencies are not relicensed under MIT.** Shizuku-API is MIT; Google ML Kit is governed by Google's terms, and some bundled model components are proprietary. See [third-party notices](THIRD_PARTY_NOTICES.md) and [privacy](PRIVACY.md).

Not affiliated with, endorsed by, or sponsored by Samsung, Google, or the Shizuku team. Samsung and Galaxy are respective owners' trademarks. This app intentionally uses an original icon, not Samsung/Shizuku artwork.
