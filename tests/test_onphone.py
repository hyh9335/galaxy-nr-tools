import os, pathlib, subprocess, tempfile, unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / 'app' / 'src' / 'main' / 'assets' / 'galaxy5g.sh'
MOCK = pathlib.Path(__file__).resolve().parent / 'mock_android.sh'

class ServiceModeTests(unittest.TestCase):
  def setUp(self):
    self.temp=tempfile.TemporaryDirectory(prefix='galaxy5g-test-')
    self.base=pathlib.Path(self.temp.name)
    self.fakebin=self.base/'bin'; self.fakebin.mkdir()
    for command in ['getprop','id','dumpsys','am','input','uiautomator','sleep']:
      (self.fakebin/command).symlink_to(MOCK)
    self.env={**os.environ, 'PATH': str(self.fakebin)+':'+os.environ.get('PATH',''),
      'MOCK_DIR':str(self.base), 'G5G_TMPBASE':str(self.base),'G5G_STATE_DIR':str(self.base/'output')}

  def tearDown(self): self.temp.cleanup()
  def mock_ocr_text(self, screen, variant):
    if screen == 'DIAL':
      if variant == 'dialer_keyboard_missing': return 'CONTACTS\t200\t200\n'
      rows = []
      for row, digits in enumerate(['123','456','789','*0#']):
        for col, key in enumerate(digits):
          if key.isdigit():
            rows.append(f'{key}\t{100+col*100}\t{650+row*100}\n')
      entered=(self.base/'keys').read_text()
      if entered:
        rows.append(f'{entered}\t200\t310\n')
      return ''.join(rows)
    menu={
      'MAIN':['UE SETTING & INFO'],
      'UE':['SETTING'],
      'SETTING':['SYSTEM','PROTOCOL'],
      'SYSTEM0':['NEXT PAGE','PAGE ZERO'], 'SYSTEM1':['NEXT PAGE','PAGE ONE'],
      'SYSTEM2':['NEXT PAGE','PAGE TWO'], 'SYSTEM3':['NEXT PAGE','PAGE THREE'],
      'SYSTEM4':['NEXT PAGE','SET LTE BAND CONFIG','PAGE FOUR'],
      'LTE':['SET DEFAULT LTE BAND'], 'PROTOCOL':['NR5G','GCF'],
      'NR5G':['PRUNE STATUS','SUB6/MMWAVE','SA/NSA MODE CONTROL'],
      'PRUNE':['RESTORE NR5G PRUNE' if variant=='already' else 'REMOVE NR5G PRUNE'],
      'GCF':[] if variant=='missing' else ['REMOVE ONLY NR WHITELIST'],
      'SUB':['ALL ENABLE (*)' if variant=='selected' else 'ALL ENABLE ()' if variant=='uncertain' else 'ALL ENABLE',
             'SUB6 ONLY ENABLE','MMWAVE ONLY ENABLE','DISABLE'],
      'SA':['SA/NSA ENABLE (*)' if variant=='sa_selected' else 'SA/NSA ENABLE ()' if variant=='sa_uncertain' else 'SA/NSA ENABLE',
            'SA DISABLE','NSA DISABLE','REMOVE SA PERMIT LIST'],
      'AFTER':['COMPLETED']
    }
    if screen=='UE' and variant=='ambiguous': menu['UE']=['SETTING','SETTING']
    if variant=='ocr_realistic':
      menu['MAIN']=['UE SETTING 8& INFO']
      for key in ('SYSTEM0','SYSTEM1','SYSTEM2','SYSTEM3','SYSTEM4'):
        menu[key][0]='NEXT PAGE -|'
      menu['NR5G']=['PRUNE STATUS','SUB/MMWAVE','SA/NSA MODE CONTROL','RRC CAP FEATURE PHY']
      menu['SUB']=['ALL ENABLE (*)','SUB6 ONLY ENABLE','MMWAVE ONLY ENABLE','DISABLE']
      menu['SA']=['SA/NSA ENABLE (*)','SA DISABLE','NSA DISABLE','REMOVE SA PERMIT LIST']
    return ''.join(f'{x}\t210\t{150+i*100}\n' for i,x in enumerate(menu.get(screen,[])))

  def execute(self, *args, variant='', model='SM-S938U'):
    for name,content in [('screen','NONE'),('events',''),('keys',''),('variant',variant),('model',model),('settings','')]:
      (self.base/name).write_text(content)
    reply = self.base/'ocr-response.tsv'
    env={**self.env, 'MOCK_ANDROID_RELEASE':'17', 'G5G_OCR_REPLY':str(reply)}
    if variant=='no_ocr': env.pop('G5G_OCR_REPLY',None)
    proc=subprocess.Popen(['sh',str(SCRIPT),*args],
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env,bufsize=1)
    lines=[]
    try:
      for line in proc.stdout:
        lines.append(line)
        if '@G5G_OCR_REQUEST@' in line:
          page=(self.base/'screen').read_text().strip()
          reply.write_text(self.mock_ocr_text(page,variant),encoding='utf-8')
      err=proc.stderr.read()
      proc.wait(timeout=30)
      class Outcome: pass
      result=Outcome();result.returncode=proc.returncode;result.stdout=''.join(lines);result.stderr=err
      def contents(name):return (self.base/name).read_text().splitlines()
      return result,{'screen':(self.base/'screen').read_text().strip(),'events':contents('events'),
                     'settings':contents('settings'),'model':model}
    finally:
      if proc.poll() is None:proc.kill()
      if proc.stdout:proc.stdout.close()
      if proc.stderr:proc.stderr.close()

  def test_direct_activity_success_skips_dialer(self):
    proc, st = self.execute('probe', 'full', variant='direct_ok')
    self.assertEqual(proc.returncode, 0, proc.stdout+'\n'+proc.stderr)
    self.assertEqual(st['events'].count('direct_ok'), 6)
    self.assertNotIn('dialer_open', st['events'])
    self.assertNotIn('broadcast_attempt', st['events'])
    self.assertEqual(st['settings'], [])

  def test_secret_broadcast_success_skips_dialer(self):
    proc, st = self.execute('probe', 'full', variant='broadcast_ok')
    self.assertEqual(proc.returncode, 0, proc.stdout+'\n'+proc.stderr)
    self.assertEqual(st['events'].count('broadcast_ok'), 6)
    self.assertEqual(st['events'].count('direct_attempt'), 1)
    self.assertNotIn('dialer_open', st['events'])
    self.assertEqual(st['settings'], [])

  def test_both_permission_denied_then_dialer_ocr_keypad(self):
    proc, st = self.execute('probe', 'full', variant='')
    self.assertEqual(proc.returncode, 0, proc.stdout+'\n'+proc.stderr)
    self.assertEqual(st['events'].count('direct_attempt'), 1)
    self.assertEqual(st['events'].count('broadcast_attempt'), 1)
    self.assertEqual(st['events'].count('code_ok'), 6)
    self.assertIn('dial_tap:#', st['events'])
    self.assertNotIn('keyevent_pound', st['events'])
    self.assertEqual(st['settings'], [])

  def test_missing_dialpad_grid_stops_before_any_key(self):
    proc, st = self.execute('probe', 'full', variant='dialer_keyboard_missing')
    self.assertNotEqual(proc.returncode, 0)
    self.assertIn('无法从截图确认完整三星数字键盘', proc.stderr)
    self.assertEqual([x for x in st['events'] if x.startswith('dial_tap:')], [])
    self.assertEqual(st['settings'], [])

  def test_status_readonly(self):
    p,s=self.execute('status')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(s['settings'],[])
    self.assertEqual(s['events'],[])

  def test_probe_full_never_write_leaves(self):
    p,s=self.execute('probe','full')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(s['settings'],[])
    self.assertEqual(s['events'].count('code_ok'),6)
    self.assertEqual(s['events'].count('tap:NEXT PAGE'),4)
    self.assertTrue((self.base/'output').exists() is False)

  def test_apply_core_skips_mmwave(self):
    p,s=self.execute('apply','core','APPLY')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(len(s['settings']),5)
    self.assertNotIn('ALL ENABLE',s['settings'])
    self.assertEqual(s['events'].count('code_ok'),5)
    self.assertEqual((self.base/'output'/'last-SM-S938U.txt').read_text().splitlines()[0],'build-101')

  def test_sa_enable_selected_still_removes_sa_permit_list(self):
    p, st = self.execute('apply', 'full', 'FULL', variant='sa_selected')
    self.assertEqual(p.returncode, 0, p.stdout+'\n'+p.stderr)
    self.assertIn('[5] SA/NSA ENABLE (*) 已选中', p.stdout)
    self.assertIn('REMOVE SA PERMIT LIST', st['settings'])
    self.assertNotIn('SA/NSA ENABLE (*)', st['settings'])
    self.assertEqual(len(st['settings']), 5)

  def test_sa_selected_probe_checks_both_actions(self):
    p, st = self.execute('probe', 'full', variant='sa_selected')
    self.assertEqual(p.returncode, 0, p.stdout+'\n'+p.stderr)
    self.assertIn('[5] SA/NSA ENABLE (*) 已选中', p.stdout)
    self.assertIn('[6] 可访问：REMOVE SA PERMIT LIST（未点击）', p.stdout)
    self.assertEqual(st['settings'], [])

  def test_sa_uncertain_marker_refuses_apply(self):
    p, st = self.execute('apply', 'full', 'FULL', variant='sa_uncertain')
    self.assertNotEqual(p.returncode, 0)
    self.assertIn('选择标记不确定', p.stderr)
    self.assertNotIn('REMOVE SA PERMIT LIST', st['settings'])

  def test_sa_uncertain_probe_still_reaches_permit_list(self):
    p, st = self.execute('probe', 'full', variant='sa_uncertain')
    self.assertEqual(p.returncode, 0, p.stdout+'\n'+p.stderr)
    self.assertIn('[6] 可访问：REMOVE SA PERMIT LIST（未点击）', p.stdout)
    self.assertEqual(st['settings'], [])

  def test_apply_full_s23u(self):
    p,s=self.execute('apply','full','FULL',model='SM-S918U1')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(len(s['settings']),6)
    self.assertIn('ALL ENABLE',s['settings'])
    self.assertNotIn('tap:BACK',s['events'])

  def test_real_s25u_ocr_aliases_all_six_paths(self):
    p,st=self.execute('probe','full',variant='ocr_realistic')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertIn('[4] ALL ENABLE',p.stdout)
    self.assertIn('[5] SA/NSA ENABLE (*) 已选中',p.stdout)
    self.assertIn('[6] 可访问：REMOVE SA PERMIT LIST',p.stdout)
    self.assertEqual(st['events'].count('tap:NEXT PAGE'),4)
    self.assertEqual(st['settings'],[])

  def test_real_s25u_sa_permit_independent_apply(self):
    p,st=self.execute('apply','full','FULL',variant='ocr_realistic')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertIn('REMOVE SA PERMIT LIST',st['settings'])
    self.assertNotIn('SA/NSA ENABLE',st['settings'])
    self.assertNotIn('ALL ENABLE',st['settings'])

  def test_ocr_only_no_uiautomator_even_android16(self):
    p,st=self.execute('probe','core')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertIn('不调用 UIAutomator',p.stdout)

  def test_missing_permission_confirmation(self):
    p,s=self.execute('apply','full','APPLY')
    self.assertNotEqual(p.returncode,0)
    self.assertEqual(s['events'],[])

  def test_new_us_s23_s24_s25_s26_models_allow_read_only(self):
    for model in ['SM-S911U','SM-S916U1','SM-S711U','SM-S921U','SM-S926U1',
                  'SM-S928U','SM-S931U','SM-S937U1','SM-S936U','SM-S938U',
                  'SM-S942U','SM-S947U1','SM-S948U','SM-S741U1']:
      with self.subTest(model=model):
        p, st = self.execute('status', model=model)
        self.assertEqual(p.returncode, 0, p.stdout+'\n'+p.stderr)
        self.assertEqual(st['settings'], [])

  def test_s22_and_non_us_still_blocked(self):
    for model in ['SM-S901U','SM-S908U1','SM-S938B','SM-S948W','SM-F966U']:
      with self.subTest(model=model):
        p, st = self.execute('probe','core',model=model)
        self.assertNotEqual(p.returncode,0)
        self.assertEqual(st['settings'], [])

  def test_reject_non_us_device(self):
    p,s=self.execute('probe','core',model='SM-S938B')
    self.assertNotEqual(p.returncode,0)
    self.assertEqual(s['events'],[])

  def test_stuck_next_page_does_not_continue(self):
    p,s=self.execute('apply','core','APPLY',variant='stuck')
    self.assertNotEqual(p.returncode,0)
    self.assertIn('NEXT PAGE',p.stderr)
    self.assertEqual(s['settings'],[])

  def test_ambiguous_menu_stops(self):
    p,s=self.execute('apply','core','APPLY',variant='ambiguous')
    self.assertNotEqual(p.returncode,0)
    self.assertIn('多个不同位置',p.stderr)
    self.assertEqual(s['settings'],[])

  def test_missing_action_stops_after_first_two(self):
    p,s=self.execute('apply','core','APPLY',variant='missing')
    self.assertNotEqual(p.returncode,0)
    self.assertEqual(len(s['settings']),2)
    self.assertNotIn('REMOVE ONLY NR WHITELIST',s['settings'])

  def test_user_switches_app_stops_without_write(self):
    p,s=self.execute('apply','core','APPLY',variant='focuslost')
    self.assertNotEqual(p.returncode,0)
    self.assertIn('前台不是 ServiceMode',p.stderr)
    self.assertEqual(s['settings'],[])

  def test_without_ocr_channel_stops_safely(self):
    p,st=self.execute('apply','core','APPLY',variant='no_ocr')
    self.assertNotEqual(p.returncode,0)
    self.assertIn('无离线 OCR 通道',p.stderr)
    self.assertEqual(st['settings'],[])

  def ocr_probe(self, reply_factory):
    for name,content in [('screen','NONE'),('events',''),('keys',''),('variant','dumpfail'),('model','SM-S938U'),('settings','')]:
      (self.base/name).write_text(content)
    reply=self.base/'ocr-response.tsv'
    env=dict(self.env, G5G_OCR_REPLY=str(reply), MOCK_ANDROID_RELEASE='17')
    proc=subprocess.Popen(['sh',str(SCRIPT),'probe','full'], env=env, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=1)
    logs=[]
    try:
      for line in proc.stdout:
        logs.append(line)
        if '@G5G_OCR_REQUEST@' in line:
          screen=(self.base/'screen').read_text().strip()
          reply.write_text(self.mock_ocr_text(screen,'') if screen=='DIAL' else reply_factory(screen),encoding='utf-8')
      stderr=proc.stderr.read()
      proc.wait(timeout=30)
      return proc.returncode, ''.join(logs), stderr, (self.base/'events').read_text().splitlines()
    finally:
      if proc.poll() is None: proc.kill()
      if proc.stdout: proc.stdout.close()
      if proc.stderr: proc.stderr.close()

  def test_android17_ocr_fallback_six_paths_probe(self):
    menus={
      'MAIN':['UE SETTING & INFO'],'UE':['SETTING'],
      'SETTING':['SYSTEM','PROTOCOL'],
      'SYSTEM0':['NEXT PAGE','PAGE ZERO'],
      'SYSTEM1':['NEXT PAGE','PAGE ONE'],
      'SYSTEM2':['NEXT PAGE','PAGE TWO'],
      'SYSTEM3':['NEXT PAGE','PAGE THREE'],
      'SYSTEM4':['NEXT PAGE','SET LTE BAND CONFIG','PAGE FOUR'],
      'LTE':['SET DEFAULT LTE BAND'],
      'PROTOCOL':['NR5G','GCF'],
      'NR5G':['PRUNE STATUS','SUB6/MMWAVE','SA/NSA MODE CONTROL'],
      'PRUNE':['REMOVE NR5G PRUNE'],
      'GCF':['REMOVE ONLY NR WHITELIST'],
      'SUB':['ALL ENABLE','SUB6 ONLY ENABLE','MMWAVE ONLY ENABLE','DISABLE'],
      'SA':['SA/NSA ENABLE','REMOVE SA PERMIT LIST'],
    }
    def make(screen):
      return ''.join(f'{label}\t210\t{150+100*i}\n' for i,label in enumerate(menus[screen]))
    code,out,err,events=self.ocr_probe(make)
    self.assertEqual(code,0,out+'\n'+err)
    self.assertEqual(events.count('code_ok'),6)
    self.assertEqual(events.count('tap:NEXT PAGE'),4)
    self.assertNotIn('SET DEFAULT LTE BAND',(self.base/'settings').read_text())
    self.assertIn('@G5G_OCR_REQUEST@',out)
    self.assertIn('Android 17',out)
    self.assertNotIn('uiautomator 不可用',out)

  def test_android17_s25u_sub6_ocr_selected_marker_probe(self):
    # Matches the screenshot's options and the real OCR suffix mismatch.
    menu = {
      'MAIN':['UE SETTING & INFO'], 'UE':['SETTING'], 'SETTING':['SYSTEM','PROTOCOL'],
      'SYSTEM0':['NEXT PAGE','PAGE ZERO'], 'SYSTEM1':['NEXT PAGE','PAGE ONE'],
      'SYSTEM2':['NEXT PAGE','PAGE TWO'], 'SYSTEM3':['NEXT PAGE','PAGE THREE'],
      'SYSTEM4':['NEXT PAGE','SET LTE BAND CONFIG','PAGE FOUR'],
      'LTE':['SET DEFAULT LTE BAND'], 'PROTOCOL':['NR5G','GCF'],
      'NR5G':['PRUNE STATUS','SUB6/MMWAVE','SA/NSA MODE CONTROL'],
      'PRUNE':['REMOVE NR5G PRUNE'], 'GCF':['REMOVE ONLY NR WHITELIST'],
      'SUB':['NR5G ENABLE/ISABLE VENU','ACTIVESTACK SUB 1 &2',
             'ALL ENABLE ()','SUB6 ONLY ENABLE','MMWAVE ONLY ENABLE','DISABLE'],
      'SA':['SA/NSA ENABLE','REMOVE SA PERMIT LIST'],
    }
    def page(screen):
      options=menu[screen]
      # Screenshot-like OCR includes headers above the tap targets; our mock
      # navigation indices assume row N has y=150+100*N, so supply recognized
      # sibling menu labels only in SUB for stable simulated tap geometry.
      if screen=='SUB':options=options[2:]
      return ''.join(f'{v}\t210\t{150+100*i}\n' for i,v in enumerate(options))
    code,out,err,events=self.ocr_probe(page)
    self.assertEqual(code,0,out+'\n'+err)
    self.assertIn('选择标记',out)
    self.assertEqual((self.base/'settings').read_text(),'')

  def test_selected_sub6_apply_skips_existing_without_toggle(self):
    p,s=self.execute('apply','full','FULL',variant='selected')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertIn('已选中',p.stdout)
    self.assertNotIn('ALL ENABLE',s['settings'])
    self.assertEqual(len(s['settings']),5)

  def test_uncertain_sub6_selection_refuses_apply_and_reboot_flag(self):
    p,s=self.execute('apply','full','FULL',variant='uncertain')
    self.assertNotEqual(p.returncode,0)
    self.assertIn('选择标记不确定',p.stderr)
    self.assertNotIn('ALL ENABLE',s['settings'])
    self.assertFalse((self.base/'output'/'last-SM-S938U.txt').exists())

  def test_all_enable_out_of_context_does_not_match(self):
    # Regression: never use only the string ALL ENABLE to infer the SUB6 page.
    def page(screen):
      if screen=='SUB':return 'ALL ENABLE (*)\t210\t150\n'
      options={
        'MAIN':['UE SETTING & INFO'],'UE':['SETTING'],'SETTING':['SYSTEM','PROTOCOL'],
        'SYSTEM0':['NEXT PAGE','PAGE ZERO'],'SYSTEM1':['NEXT PAGE','PAGE ONE'],
        'SYSTEM2':['NEXT PAGE','PAGE TWO'],'SYSTEM3':['NEXT PAGE','PAGE THREE'],
        'SYSTEM4':['NEXT PAGE','SET LTE BAND CONFIG','PAGE FOUR'],
        'LTE':['SET DEFAULT LTE BAND'],'PROTOCOL':['NR5G','GCF'],
        'NR5G':['PRUNE STATUS','SUB6/MMWAVE','SA/NSA MODE CONTROL'],
        'PRUNE':['REMOVE NR5G PRUNE'],'GCF':['REMOVE ONLY NR WHITELIST'],
        'SA':['SA/NSA ENABLE','REMOVE SA PERMIT LIST']
      }[screen]
      return ''.join(f'{v}\t210\t{150+100*i}\n' for i,v in enumerate(options))
    code,out,err,events=self.ocr_probe(page)
    self.assertNotEqual(code,0)
    self.assertIn('重新定位后仍未完成',err)
    self.assertEqual((self.base/'settings').read_text(),'')

  def test_android17_next_page_arrow_ocr_all_four_pages(self):
    menus={
      'MAIN':['UE SETTING & INFO'],'UE':['SETTING'],
      'SETTING':['SYSTEM','PROTOCOL'],
      'SYSTEM0':['NEXT PAGE -|','PAGE ZERO'],
      'SYSTEM1':['NEXT PAGE -|','PAGE ONE'],
      'SYSTEM2':['NEXT PAGE -|','PAGE TWO'],
      'SYSTEM3':['NEXT PAGE -|','PAGE THREE'],
      'SYSTEM4':['NEXT PAGE -|','SET LTE BAND CONFIG','PAGE FOUR'],
      'LTE':['SET DEFAULT LTE BAND'], 'PROTOCOL':['NR5G','GCF'],
      'NR5G':['PRUNE STATUS','SUB6/MMWAVE','SA/NSA MODE CONTROL'],
      'PRUNE':['REMOVE NR5G PRUNE'], 'GCF':['REMOVE ONLY NR WHITELIST'],
      'SUB':['ALL ENABLE','SUB6 ONLY ENABLE','MMWAVE ONLY ENABLE','DISABLE'], 'SA':['SA/NSA ENABLE','REMOVE SA PERMIT LIST'],
    }
    code,out,err,events=self.ocr_probe(lambda page: ''.join(
      f'{s}\t210\t{150+100*i}\n' for i,s in enumerate(menus[page])))
    self.assertEqual(code,0,out+'\n'+err)
    self.assertEqual(events.count('tap:NEXT PAGE'),4)
    self.assertEqual((self.base/'settings').read_text(),'')

  def test_s25u_android17_ocr_ampersand_8_artifact_full_probe(self):
    menu = {
      'MAIN':['PROTOCOL STATE','UE SETTING 8& INFO'],
      'UE':['SETTING'], 'SETTING':['SYSTEM','PROTOCOL'],
      'SYSTEM0':['NEXT PAGE -|','PAGE ZERO'],
      'SYSTEM1':['NEXT PAGE -|','PAGE ONE'],
      'SYSTEM2':['NEXT PAGE -|','PAGE TWO'],
      'SYSTEM3':['NEXT PAGE -|','PAGE THREE'],
      'SYSTEM4':['NEXT PAGE -|','SET LTE BAND CONFIG','PAGE FOUR'],
      'LTE':['SET DEFAULT LTE BAND'], 'PROTOCOL':['NR5G','GCF'],
      'NR5G':['PRUNE STATUS','SUB6/MMWAVE','SA/NSA MODE CONTROL'],
      'PRUNE':['REMOVE NR5G PRUNE'], 'GCF':['REMOVE ONLY NR WHITELIST'],
      'SUB':['ALL ENABLE','SUB6 ONLY ENABLE','MMWAVE ONLY ENABLE','DISABLE'], 'SA':['SA/NSA ENABLE','REMOVE SA PERMIT LIST'],
    }
    # Mock tap centers use row indices; the harmless header is NOT a tappable
    # mock menu item, so return only the OCR-distorted actual root menu label.
    def fake(page):
      labels = menu[page] if page != 'MAIN' else ['UE SETTING 8& INFO']
      return ''.join(f'{v}\t210\t{150+100*i}\n' for i,v in enumerate(labels))
    code,out,err,events=self.ocr_probe(fake)
    self.assertEqual(code,0,out+'\n'+err)
    self.assertIn('NEXT PAGE',out)
    self.assertEqual(events.count('tap:NEXT PAGE'),4)
    self.assertEqual((self.base/'settings').read_text(), '')

  def test_android17_next_page_arrow_ambiguous_stops(self):
    def page(screen):
      if screen == 'SYSTEM0':
        return 'NEXT PAGE -|\t210\t150\nNEXT PAGE >\t220\t250\n'
      menus={'MAIN':'UE SETTING & INFO','UE':'SETTING','SETTING':'SYSTEM'}
      return f'{menus.get(screen,"NOT FOUND")}\t210\t150\n'
    code,out,err,events=self.ocr_probe(page)
    self.assertNotEqual(code,0)
    self.assertIn('多个不同位置',err)
    self.assertEqual((self.base/'settings').read_text(),'')

  def test_android17_next_page_wrong_text_does_not_match(self):
    def page(screen):
      menus={'MAIN':'UE SETTING & INFO','UE':'SETTING','SETTING':'SYSTEM','SYSTEM0':'NEXT PAGE 2'}
      return f'{menus.get(screen,"NOT FOUND")}\t210\t150\n'
    code,out,err,events=self.ocr_probe(page)
    self.assertNotEqual(code,0)
    self.assertIn('NEXT PAGE',err)
    self.assertEqual((self.base/'settings').read_text(),'')

  def test_android17_ocr_uncertain_stops_before_setting(self):
    code,out,err,events=self.ocr_probe(lambda screen:'ERROR: 受保护窗口')
    self.assertNotEqual(code,0)
    self.assertIn('离线 OCR 失败',err)
    self.assertEqual((self.base/'settings').read_text(),'')

  def test_android17_ocr_ambiguous_stops(self):
    def page(screen):
      if screen == 'UE': return 'SETTING\t200\t150\nSETTING\t300\t300\n'
      return 'UE SETTING & INFO\t200\t150\n'
    code,out,err,events=self.ocr_probe(page)
    self.assertNotEqual(code,0)
    self.assertIn('多个不同位置',err)
    self.assertEqual((self.base/'settings').read_text(),'')


  def test_android17_unrecognized_service_menu_logs_text_and_stops(self):
    def screenshot(_screen):
      return 'RIGHT-HARDKEY BACK\t240\t410\nPLEASE USE THE MENUKEY\t270\t510\n'
    code,out,err,events=self.ocr_probe(screenshot)
    self.assertNotEqual(code,0)
    self.assertIn('不是可确认的主菜单',out)
    self.assertIn('没有打开 ServiceMode',err)
    self.assertEqual((self.base/'settings').read_text(),'')
    self.assertLessEqual(out.count('@G5G_OCR_REQUEST@'),3)  # dialpad + prefix + ServiceMode

  def test_already_prune_skip(self):
    p,s=self.execute('apply','core','APPLY',variant='already')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(len(s['settings']),4)
    self.assertNotIn('REMOVE NR5G PRUNE',s['settings'])

if __name__=='__main__': unittest.main()
