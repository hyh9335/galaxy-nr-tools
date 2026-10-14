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
        self.assertIn('setPositiveButton("确认执行"', src)
        self.assertIn('探测六条菜单路径', src)
        self.assertIn('中止当前操作', src)

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

    def test_gcf_navigation_not_relative_back(self):
        text = SCRIPT.read_text()
        self.assertIn('PROTOCOL|GCF', text)
        self.assertIn('PROTOCOL|NR5G|SUB6/MMWAVE', text)
        self.assertNotIn('input keyevent KEYCODE_BACK', text)

if __name__ == '__main__': unittest.main()
