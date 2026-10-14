# Security policy

Please **do not** post SIM identifiers, carrier credentials, IMEI/IMSI, phone numbers, location, Google/Shizuku tokens, or unredacted screenshots in public issues.

The automation uses a fixed SHA-256-approved embedded shell script and has no generic remote command entry point. It fails closed on unknown device, menu or OCR identity. Model support is experimental outside the documented S25 Ultra. Security/permission bugs or unsafe engineering menu behavior should be reported privately to the maintainer using GitHub's private vulnerability reporting if enabled, or through a private message rather than a public issue. Do not blindly retry an interrupted apply; settings may already have been changed.

Public pull requests modifying the allowlist, terminal-setting recognition, shell code, Shizuku binder or signing workflow receive extra review. Release signing keys must never be committed, pasted in logs, or uploaded as issue attachments.
