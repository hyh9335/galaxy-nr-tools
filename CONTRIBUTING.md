# Contributing

We welcome improvements to UI localization, conservative OCR recognition, documentation, and regression tests. Please open an issue with **redacted** ServiceMode menu names, model family, Android version, and reproduction steps (not IMEI/serial or phone numbers). Run `python3 -m unittest discover -s tests -v` before PRs. CI must also build the APK. For menu aliases, require a page signature and provide at least one positive and negative case; **do not introduce fuzzy/coordinate-only taps or silent modification**.

Translations default to English, with Simplified Chinese supplied separately. Preserve the existing application ID for update compatibility. Do not commit private signing keys, keystores, source credentials, Android diagnostic identifiers, or binary vendor components. Third-party code must carry its original notices/licenses. Release signers and model additions require maintainer approval.
