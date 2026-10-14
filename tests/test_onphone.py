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
  def execute(self, *args, variant='', model='SM-S938U'):
    for name,content in [('screen','NONE'),('events',''),('keys',''),('variant',variant),('model',model),('settings','')]:
      (self.base/name).write_text(content)
    p=subprocess.run(['sh',str(SCRIPT),*args],capture_output=True,text=True,env=self.env,timeout=90)
    def lines(name): return (self.base/name).read_text().splitlines()
    return p, {'screen':(self.base/'screen').read_text().strip(),'events':lines('events'),
               'settings':lines('settings'), 'model':model}

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

  def test_apply_full_s23u(self):
    p,s=self.execute('apply','full','FULL',model='SM-S918U1')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(len(s['settings']),6)
    self.assertIn('ALL ENABLE',s['settings'])
    self.assertNotIn('tap:BACK',s['events'])

  def test_missing_permission_confirmation(self):
    p,s=self.execute('apply','full','APPLY')
    self.assertNotEqual(p.returncode,0)
    self.assertEqual(s['events'],[])

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

  def test_uiautomator_failure_stops(self):
    p,s=self.execute('apply','core','APPLY',variant='dumpfail')
    self.assertNotEqual(p.returncode,0)
    self.assertIn('uiautomator dump 失败',p.stdout)
    self.assertIn('无离线 OCR 通道',p.stderr)
    self.assertEqual(s['settings'],[])

  def ocr_probe(self, reply_factory):
    for name,content in [('screen','NONE'),('events',''),('keys',''),('variant','dumpfail'),('model','SM-S938U'),('settings','')]:
      (self.base/name).write_text(content)
    reply=self.base/'ocr-response.tsv'
    env=dict(self.env, G5G_OCR_REPLY=str(reply))
    proc=subprocess.Popen(['sh',str(SCRIPT),'probe','full'], env=env, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=1)
    logs=[]
    try:
      for line in proc.stdout:
        logs.append(line)
        if '@G5G_OCR_REQUEST@' in line:
          screen=(self.base/'screen').read_text().strip()
          reply.write_text(reply_factory(screen),encoding='utf-8')
      stderr=proc.stderr.read()
      proc.wait(timeout=30)
      return proc.returncode, ''.join(logs), stderr, (self.base/'events').read_text().splitlines()
    finally:
      if proc.poll() is None: proc.kill()

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
      'SUB':['ALL ENABLE'],
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

  def test_already_prune_skip(self):
    p,s=self.execute('apply','core','APPLY',variant='already')
    self.assertEqual(p.returncode,0,p.stdout+'\n'+p.stderr)
    self.assertEqual(len(s['settings']),4)
    self.assertNotIn('REMOVE NR5G PRUNE',s['settings'])

if __name__=='__main__': unittest.main()
