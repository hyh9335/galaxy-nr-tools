import hashlib
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / 'app/src/main/assets/galaxy5g.sh'
SERVICE = ROOT / 'app/src/main/java/com/example/galaxy5gmanager/PrivilegedService.java'
ACTIVITY = ROOT / 'app/src/main/java/com/example/galaxy5gmanager/MainActivity.java'
class ProjectChecks(unittest.TestCase):
    def test_script_digest_matches_execution_allowlist(self):
        digest = hashlib.sha256(SCRIPT.read_bytes()).hexdigest()
        self.assertIn('SCRIPT_SHA256 = "' + digest + '"', SERVICE.read_text())

    def test_three_stage_launcher_and_keypad_safety(self):
        script = SCRIPT.read_text()
        self.assertIn('try_direct_activity()', script)
        self.assertIn('try_secret_broadcast()', script)
        self.assertIn('try_dialer_ocr()', script)
        self.assertIn('locate_dial_key()', script)
        self.assertIn('verify_dialed_prefix()', script)
        self.assertIn('dialer_keyevent_fallback()', script)
        self.assertIn('input keyevent KEYCODE_POUND', script)
        self.assertIn('is_dialer_foreground', script)
        self.assertNotIn('uiautomator dump', script)
        self.assertIn('DIRECT_ACTIVITY_UNAVAILABLE', script)
        activity = ACTIVITY.read_text()
        self.assertIn('[0-9]+', activity)

    def test_manifest_has_no_network_and_uses_guarded_provider(self):
        src = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        ET.fromstring(src)
        self.assertNotIn('android.permission.INTERNET', src)
        self.assertIn('android.permission.INTERACT_ACROSS_USERS_FULL', src)
        self.assertIn('moe.shizuku.manager.permission.API_V23', src)
        self.assertIn('android:allowBackup="false"', src)

    def test_no_external_command_api(self):
        src = SERVICE.read_text()
        self.assertIn('new ProcessBuilder("/system/bin/sh", scriptFile.getAbsolutePath()', src)
        self.assertIn('SCRIPT_SHA256.equals(sha256(script))', src)
        self.assertNotIn('new ProcessBuilder("/system/bin/sh", "-c"', src)
        self.assertIn('"full".equals(profile) ? "FULL" : "APPLY"', src)

    def test_shizuku_destroy_transaction_matches_user_service(self):
        aidl = (ROOT / 'app/src/main/aidl/com/example/galaxy5gmanager/IGalaxyAutomation.aidl').read_text()
        self.assertIn('void destroy() = 16777114;', aidl)

    def test_main_ui_is_not_silent_full_auto(self):
        src = ACTIVITY.read_text()
        self.assertIn('setPositiveButton(tr("Confirm restore"', src)
        self.assertIn('Probe six menu paths', src)
        self.assertIn('Cancel current task', src)

    def test_auto_return_is_foreground_guarded(self):
        source = SERVICE.read_text()
        self.assertIn('returnToManagerIfOnServiceMode()', source)
        self.assertIn('mCurrentFocus', source)
        self.assertIn('rilservicemodeapp', source)
        self.assertIn('com.example.galaxy5gmanager/.MainActivity', source)
        self.assertIn('Auto-return skipped', source)

    def test_system_themes_and_two_languages(self):
        resources = ROOT / 'app/src/main/res'
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:theme="@style/GalaxyNrTheme"', manifest)
        assert (resources/'values/styles.xml').exists()
        assert (resources/'values-night/styles.xml').exists()
        assert (resources/'values/strings.xml').exists()
        assert (resources/'values-zh/strings.xml').exists()
        self.assertIn('Galaxy NR Tools', (resources/'values/strings.xml').read_text())
        self.assertIn('Galaxy NR 工具', (resources/'values-zh/strings.xml').read_text())
        self.assertIn('getResources().getConfiguration().getLocales()', (ROOT/'app/src/main/java/com/example/galaxy5gmanager/UiText.java').read_text())

    def test_new_app_icon(self):
        resources = ROOT/'app/src/main/res'
        self.assertTrue((resources/'drawable-nodpi/ic_launcher_foreground.png').stat().st_size > 2000)
        self.assertIn('<monochrome', (resources/'mipmap-anydpi-v26/ic_launcher.xml').read_text())

    def test_android17_binder_screen_transport_is_file_descriptor(self):
        root = ROOT / 'app/src/main'
        service = (root / 'java/com/example/galaxy5gmanager/PrivilegedService.java').read_text()
        callback = (root / 'aidl/com/example/galaxy5gmanager/IGalaxyPageReader.aidl').read_text()
        activity = ACTIVITY.read_text()
        self.assertIn('ParcelFileDescriptor', callback)
        self.assertIn('callback.recognize(fd, 0, 0)', service)
        self.assertNotIn('bytes.toByteArray()', service)
        self.assertIn('cachedRecognizer', activity)
        self.assertIn('AutomationKeepAliveService', activity)

    def test_service_manifest_is_foreground_special_use(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.FOREGROUND_SERVICE_SPECIAL_USE', manifest)
        self.assertIn('android:foregroundServiceType="specialUse"', manifest)

    def test_overlay_pass_through_and_binder_progress(self):
        src = (ROOT / 'app/src/main/java/com/example/galaxy5gmanager/AutomationKeepAliveService.java').read_text()
        reader = (ROOT / 'app/src/main/aidl/com/example/galaxy5gmanager/IGalaxyPageReader.aidl').read_text()
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('FLAG_NOT_TOUCHABLE', src)
        self.assertIn('FLAG_NOT_FOCUSABLE', src)
        self.assertIn('TYPE_APPLICATION_OVERLAY', src)
        self.assertIn('onProgress(String line)', reader)
        self.assertIn('.version(4)', ACTIVITY.read_text())
        self.assertIn('SYSTEM_ALERT_WINDOW', manifest)

    def test_diagnostics_are_read_only_and_conservative(self):
        radio = (ROOT / 'app/src/main/java/com/example/galaxy5gmanager/NetworkDiagnostics.java').read_text()
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('READ_PHONE_STATE', manifest)
        self.assertIn('getDataNetworkType()', radio)
        self.assertIn('不能据此判断 5G 被锁定', radio)
        self.assertIn('不等于一定在毫米波', radio)
        self.assertNotIn('getAllowedNetworkTypesForReason(', radio)
        self.assertNotIn('getImei(', radio)

    def test_state_machine_has_strict_settings_and_bounded_recovery(self):
        script = SCRIPT.read_text()
        self.assertIn('classify_page()', script)
        self.assertIn('try_menu_back_to_main()', script)
        self.assertIn('recovery_count', script)
        self.assertIn('8&', script)
        self.assertIn('find_label "$ACTION"', script)
        self.assertNotIn('KEYCODE_BACK', script.split('try_menu_back_to_main()')[1].split('open_service_main()')[0])

    def test_reboot_gated_by_successful_apply_only(self):
        service = SERVICE.read_text()
        interface=(ROOT / 'app/src/main/aidl/com/example/galaxy5gmanager/IGalaxyAutomation.aidl').read_text()
        assert 'boolean canReboot() = 7;' in interface
        assert 'boolean rebootDevice() = 8;' in interface
        assert 'code == 0 && !cancelled && "apply".equals(mode)' in service
        assert 'if (!canReboot()' in service
        assert '"/system/bin/svc", "power", "reboot"' in service
        assert 'showRebootDialog()' in ACTIVITY.read_text()

    def test_selection_asterisk_kept_and_safe_alias(self):
        source=ACTIVITY.read_text()
        script=SCRIPT.read_text()
        assert '[^A-Z0-9 /&()*' in source
        assert 'MMWAVE ONLY ENABLE' in script
        assert '选择标记不确定' in script
        assert '已选中：无需重复点击' in script

    def test_gcf_navigation_not_relative_back(self):
        text = SCRIPT.read_text()
        self.assertIn('PROTOCOL|GCF', text)
        self.assertIn('PROTOCOL|NR5G|SUB6/MMWAVE', text)
        self.assertNotIn('input keyevent KEYCODE_BACK', text)

if __name__ == '__main__': unittest.main()
